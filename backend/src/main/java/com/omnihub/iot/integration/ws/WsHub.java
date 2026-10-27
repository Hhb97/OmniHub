package com.omnihub.iot.integration.ws;

import com.omnihub.iot.common.Jsons;
import com.omnihub.iot.security.LoginUser;
import java.io.IOException;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;

/** Tracks WebSocket sessions and their subscriptions, and pushes messages to the interested ones. */
@Component
public class WsHub {

    private static final Logger log = LoggerFactory.getLogger(WsHub.class);
    private static final long DASHBOARD_MIN_INTERVAL_MS = 2000;

    private final Map<Long, Set<WebSocketSession>> deviceSubs = new ConcurrentHashMap<>();
    private final Set<WebSocketSession> dashboardSubs = ConcurrentHashMap.newKeySet();
    private final Map<Long, Long> lastDashboardPush = new ConcurrentHashMap<>();

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

    public void pushToDevice(Long deviceId, Object payload) {
        Set<WebSocketSession> set = deviceSubs.get(deviceId);
        if (set == null || set.isEmpty()) {
            return;
        }
        TextMessage msg = new TextMessage(Jsons.toJson(payload));
        set.forEach(s -> send(s, msg));
    }

    /** Tells dashboard subscribers of the tenant to refetch; coalesced to at most one signal every 2 seconds. */
    public void signalDashboard(Long tenantId) {
        long now = System.currentTimeMillis();
        Long last = lastDashboardPush.get(tenantId);
        if (last != null && now - last < DASHBOARD_MIN_INTERVAL_MS) {
            return;
        }
        lastDashboardPush.put(tenantId, now);
        TextMessage msg = new TextMessage("{\"type\":\"dashboard\"}");
        dashboardSubs.forEach(s -> {
            if (s.getAttributes().get(WsAuthInterceptor.ATTR_USER) instanceof LoginUser u
                    && u.tenantId().equals(tenantId)) {
                send(s, msg);
            }
        });
    }

    static void sendTo(WebSocketSession s, Object payload) {
        send(s, new TextMessage(Jsons.toJson(payload)));
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
}
