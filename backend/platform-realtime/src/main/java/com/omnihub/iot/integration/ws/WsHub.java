package com.omnihub.iot.integration.ws;

import com.omnihub.iot.common.Jsons;
import com.omnihub.iot.config.IotProperties;
import com.omnihub.iot.identity.core.LoginUser;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.annotation.PreDestroy;
import java.io.IOException;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;

/**
 * Tracks WebSocket sessions and their subscriptions, and pushes messages to the interested ones.
 *
 * Nothing here runs on an ingestion thread: the MQTT lane only puts the payload into a small map / queue. Property
 * pushes of one device are merged within {@code iot.ws.coalesce-millis} (a high-rate device would otherwise produce
 * one JSON serialisation and one blocking socket write per message), and the actual writes happen on a small pool.
 * If the pool's queue is full (slow clients) pushes are dropped and counted instead of stalling ingestion.
 */
@Component
public class WsHub {

    private static final Logger log = LoggerFactory.getLogger(WsHub.class);
    private static final long DASHBOARD_MIN_INTERVAL_MS = 2000;

    private final Map<Long, Set<WebSocketSession>> deviceSubs = new ConcurrentHashMap<>();
    private final Set<WebSocketSession> dashboardSubs = ConcurrentHashMap.newKeySet();
    private final Map<Long, Long> lastDashboardPush = new ConcurrentHashMap<>();

    /** newest property payload per device, written by the flush task below */
    private final Map<Long, Object> pending = new ConcurrentHashMap<>();
    private final ThreadPoolExecutor sender;
    private final ScheduledExecutorService coalescer;
    private final long coalesceMillis;
    private final AtomicLong dropped = new AtomicLong();
    private final Counter droppedCounter;

    public WsHub(IotProperties props, MeterRegistry meters) {
        IotProperties.Ws cfg = props.getWs();
        this.coalesceMillis = Math.max(0, cfg.getCoalesceMillis());
        int threads = Math.max(1, cfg.getSenderThreads());
        this.sender = new ThreadPoolExecutor(threads, threads, 0, TimeUnit.SECONDS,
                new ArrayBlockingQueue<>(Math.max(64, cfg.getQueueCapacity())), r -> {
                    Thread t = new Thread(r, "ws-send");
                    t.setDaemon(true);
                    return t;
                });
        this.coalescer = coalesceMillis > 0 ? Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "ws-coalesce");
            t.setDaemon(true);
            return t;
        }) : null;
        if (coalescer != null) {
            coalescer.scheduleWithFixedDelay(this::flushPending, coalesceMillis, coalesceMillis, TimeUnit.MILLISECONDS);
        }
        Gauge.builder("iot_ws_push_pending", sender, s -> s.getQueue().size() + pending.size())
                .description("WebSocket pushes waiting to be written").register(meters);
        this.droppedCounter = Counter.builder("iot_ws_push_dropped_total")
                .description("WebSocket pushes dropped because the senders could not keep up")
                .register(meters);
    }

    public void subscribeDevice(Long deviceId, WebSocketSession s) {
        deviceSubs.computeIfAbsent(deviceId, k -> ConcurrentHashMap.newKeySet()).add(s);
    }

    public void unsubscribeDevice(Long deviceId, WebSocketSession s) {
        Set<WebSocketSession> set = deviceSubs.get(deviceId);
        if (set != null) {
            set.remove(s);
        }
    }

    public void subscribeDashboard(WebSocketSession s) {
        dashboardSubs.add(s);
    }

    public void unsubscribeDashboard(WebSocketSession s) {
        dashboardSubs.remove(s);
    }

    public void remove(WebSocketSession s) {
        deviceSubs.values().forEach(set -> set.remove(s));
        dashboardSubs.remove(s);
    }

    public int sessionCount() {
        return deviceSubs.values().stream().mapToInt(Set::size).sum() + dashboardSubs.size();
    }

    /** Pushes an event for a device; with coalescing enabled only the newest payload of the window is sent. */
    public void pushToDevice(Long deviceId, Object payload) {
        if (!hasSubscribers(deviceId)) {
            return;
        }
        if (coalesceMillis == 0) {
            submit(deviceId, payload);
        } else {
            pending.put(deviceId, payload);
        }
    }

    /** Pushes an event that must not be merged or delayed (status, command, alarm). */
    public void pushToDeviceNow(Long deviceId, Object payload) {
        if (!hasSubscribers(deviceId)) {
            return;
        }
        submit(deviceId, payload);
    }

    /** Tells dashboard subscribers of the tenant to refetch; coalesced to at most one signal every 2 seconds. */
    public void signalDashboard(Long tenantId) {
        long now = System.currentTimeMillis();
        Long last = lastDashboardPush.get(tenantId);
        if (last != null && now - last < DASHBOARD_MIN_INTERVAL_MS) {
            return;
        }
        lastDashboardPush.put(tenantId, now);
        boolean hasAny = !dashboardSubs.isEmpty();
        if (!hasAny) {
            return;
        }
        TextMessage msg = new TextMessage("{\"type\":\"dashboard\"}");
        try {
            sender.execute(() -> dashboardSubs.forEach(s -> {
                if (s.getAttributes().get(WsAuthInterceptor.ATTR_USER) instanceof LoginUser u
                        && u.tenantId().equals(tenantId)) {
                    send(s, msg);
                }
            }));
        } catch (RejectedExecutionException e) {
            dropped("dashboard");
        }
    }

    static void sendTo(WebSocketSession s, Object payload) {
        send(s, new TextMessage(Jsons.toJson(payload)));
    }

    private boolean hasSubscribers(Long deviceId) {
        Set<WebSocketSession> set = deviceSubs.get(deviceId);
        return set != null && !set.isEmpty();
    }

    private void submit(Long deviceId, Object payload) {
        try {
            sender.execute(() -> {
                Set<WebSocketSession> set = deviceSubs.get(deviceId);
                if (set == null || set.isEmpty()) {
                    return;
                }
                TextMessage msg = new TextMessage(Jsons.toJson(payload)); // once per device, not once per session
                set.forEach(s -> send(s, msg));
            });
        } catch (RejectedExecutionException e) {
            dropped("device");
        }
    }

    private void flushPending() {
        if (pending.isEmpty()) {
            return;
        }
        try {
            for (Map.Entry<Long, Object> e : pending.entrySet()) {
                if (pending.remove(e.getKey(), e.getValue())) {
                    submit(e.getKey(), e.getValue());
                }
            }
        } catch (Exception e) {
            log.debug("WebSocket coalescing failed: {}", e.toString());
        }
    }

    private void dropped(String what) {
        long n = dropped.incrementAndGet();
        droppedCounter.increment();
        if (n % 1000 == 1) {
            log.warn("WebSocket {} push dropped (queue full): {} pushes dropped so far", what, n);
        }
    }

    private static void send(WebSocketSession s, TextMessage msg) {
        try {
            if (s.isOpen()) {
                synchronized (s) {
                    s.sendMessage(msg);
                }
            }
        } catch (IOException | IllegalStateException e) {
            log.debug("WebSocket send failed: {}", e.getMessage());
        }
    }

    @PreDestroy
    void close() {
        if (coalescer != null) {
            coalescer.shutdownNow();
        }
        sender.shutdownNow();
    }
}
