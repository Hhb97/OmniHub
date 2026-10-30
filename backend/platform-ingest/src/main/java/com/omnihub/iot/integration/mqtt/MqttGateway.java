package com.omnihub.iot.integration.mqtt;

import com.omnihub.iot.config.IotProperties;
import com.omnihub.iot.messaging.DownlinkPublisher;
import com.omnihub.iot.messaging.Topics;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.eclipse.paho.client.mqttv3.IMqttDeliveryToken;
import org.eclipse.paho.client.mqttv3.IMqttToken;
import org.eclipse.paho.client.mqttv3.MqttCallbackExtended;
import org.eclipse.paho.client.mqttv3.MqttClient;
import org.eclipse.paho.client.mqttv3.MqttConnectOptions;
import org.eclipse.paho.client.mqttv3.MqttException;
import org.eclipse.paho.client.mqttv3.MqttMessage;
import org.eclipse.paho.client.mqttv3.persist.MemoryPersistence;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * The platform's own MQTT client. Subscribes to all device uplink topics and publishes downlink messages.
 * Connection is established in the background and retried, so the application also starts when the broker is
 * not up yet. Paho's automatic reconnect handles later drops; subscriptions are re-established on every connect.
 *
 * <p>One MQTT connection is one broker session with one delivery queue and one callback thread. At a few thousand
 * messages per second that session becomes the ceiling: the broker's queue for it fills up, EMQX drops messages
 * ({@code dropped_msg_due_to_mqueue_is_full}) and finally closes the connection ({@code mailbox_overflow}). The
 * gateway therefore opens {@code iot.mqtt.ingest-connections} connections that join one shared subscription
 * ({@code $share/<group>/<filter>}), so the broker distributes the messages over all of them. Each delivery still
 * reaches exactly one connection and is handled once.
 *
 * <p>Inbound messages are dispatched to a fixed number of single-threaded lanes keyed by device, which preserves
 * per-device ordering while keeping the Paho callback thread free.
 */
@Component
public class MqttGateway implements DownlinkPublisher {

    private static final Logger log = LoggerFactory.getLogger(MqttGateway.class);

    private final IotProperties props;
    private final UplinkProcessor processor;
    private final org.springframework.beans.factory.ObjectProvider<KafkaUplinkForwarder> forwarder;
    private final ThreadPoolExecutor[] lanes;
    private final ScheduledExecutorService connector = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "mqtt-connector");
        t.setDaemon(true);
        return t;
    });
    /** One slot per configured connection; null until that connection is up. */
    private final MqttClient[] clients;
    /** Cleared if the broker refuses {@code $share/...}: the gateway then falls back to a single plain connection. */
    private final AtomicBoolean shared = new AtomicBoolean(true);

    public MqttGateway(IotProperties props, UplinkProcessor processor,
            org.springframework.beans.factory.ObjectProvider<KafkaUplinkForwarder> forwarder,
            io.micrometer.core.instrument.MeterRegistry meters) {
        this.forwarder = forwarder;
        this.props = props;
        this.processor = processor;
        int laneCount = props.getIngest().getLanes() > 0 ? props.getIngest().getLanes()
                : Math.max(8, Runtime.getRuntime().availableProcessors() * 2);
        boolean dropWhenFull = "drop".equalsIgnoreCase(props.getIngest().getLaneOverflow());
        io.micrometer.core.instrument.Counter laneDropped = io.micrometer.core.instrument.Counter
                .builder("iot_ingest_lane_dropped_total")
                .description("Uplink messages dropped because every lane queue was full (lane-overflow=drop)")
                .register(meters);
        this.lanes = new ThreadPoolExecutor[laneCount];
        for (int i = 0; i < laneCount; i++) {
            int n = i;
            lanes[i] = new ThreadPoolExecutor(1, 1, 0, TimeUnit.SECONDS, new ArrayBlockingQueue<>(props.getIngest().getLaneQueue()), r -> {
                Thread t = new Thread(r, "mqtt-lane-" + n);
                t.setDaemon(true);
                return t;
            }, (r, executor) -> {
                if (dropWhenFull) {
                    // keep the broker connection and the Paho callback healthy; the loss is visible as a metric
                    laneDropped.increment();
                    return;
                }
                // default: back-pressure towards the broker (a message is never lost inside the JVM)
                r.run();
            });
        }
        int connections = Math.max(1, props.getMqtt().getIngestConnections());
        if (props.getMqtt().getSharedGroup() == null || props.getMqtt().getSharedGroup().isBlank()) {
            // without a shared group every connection would receive a copy of every message
            connections = 1;
        }
        this.clients = new MqttClient[connections];
        io.micrometer.core.instrument.Gauge.builder("iot_lane_queue_pending", lanes,
                l -> java.util.Arrays.stream(l).mapToInt(x -> x.getQueue().size()).sum())
                .description("MQTT messages waiting in the in-process lanes (direct mode backlog)").register(meters);
        io.micrometer.core.instrument.Gauge.builder("iot_lane_queue_max", lanes,
                l -> java.util.Arrays.stream(l).mapToInt(x -> x.getQueue().size()).max().orElse(0))
                .description("Backlog of the fullest lane").register(meters);
        io.micrometer.core.instrument.Gauge.builder("iot_mqtt_gateway_connected", this, g -> g.connectedCount() > 0 ? 1 : 0)
                .description("1 when at least one platform MQTT connection is up").register(meters);
        io.micrometer.core.instrument.Gauge.builder("iot_mqtt_gateway_connections", this, MqttGateway::connectedCount)
                .description("Platform MQTT connections currently connected").register(meters);
        io.micrometer.core.instrument.Gauge.builder("iot_mqtt_gateway_connections_expected", this,
                g -> g.clients.length)
                .description("Platform MQTT connections configured for ingestion").register(meters);
    }

    @PostConstruct
    void start() {
        connector.schedule(this::connectAll, 1, TimeUnit.SECONDS);
    }

    @PreDestroy
    void stop() {
        connector.shutdownNow();
        for (MqttClient c : clients) {
            close(c);
        }
        for (ThreadPoolExecutor lane : lanes) {
            lane.shutdown();
        }
    }

    private void connectAll() {
        for (int i = 0; i < clients.length; i++) {
            connectOne(i);
        }
    }

    private void connectOne(int i) {
        if (i > 0 && !shared.get()) {
            return; // the broker refused shared subscriptions; one connection is enough (and avoids duplicates)
        }
        IotProperties.Mqtt cfg = props.getMqtt();
        String clientId = clients.length > 1 ? cfg.getClientId() + "-" + i : cfg.getClientId();
        try {
            MqttClient c = new MqttClient(cfg.getBrokerUrl(), clientId, new MemoryPersistence());
            MqttConnectOptions opts = new MqttConnectOptions();
            opts.setUserName(cfg.getPlatformUsername());
            opts.setPassword(cfg.getPlatformPassword().toCharArray());
            opts.setAutomaticReconnect(true);
            opts.setCleanSession(cfg.isCleanSession());
            opts.setKeepAliveInterval(30);
            opts.setConnectionTimeout(10);
            // Paho's outbound in-flight window: it limits how many QoS1 messages this client may publish without an
            // acknowledgement (downlink commands). The uplink receive window is the broker's mqtt.max_inflight:
            // throughput = sessions x broker max_inflight / acknowledgement round trip. At 1024 per session and 8
            // sessions the pipeline stalled at about 4.6k msg/s with the broker window completely full.
            opts.setMaxInflight(Math.max(1, cfg.getMaxInflight()));
            c.setCallback(new Callback(i));
            clients[i] = c;
            c.connect(opts);
            log.info("MQTT gateway connection {}/{} connected to {} as {}", i + 1, clients.length, cfg.getBrokerUrl(),
                    clientId);
            // first connect: Paho also calls connectComplete(false), so do not subscribe twice
            subscribeAll(i);
        } catch (MqttException e) {
            clients[i] = null;
            log.warn("MQTT gateway connection {}/{} cannot connect to {} ({}); retrying in 5s", i + 1, clients.length,
                    cfg.getBrokerUrl(), e.getMessage());
            connector.schedule(() -> connectOne(i), 5, TimeUnit.SECONDS);
        }
    }

    private void subscribeAll(int index) {
        MqttClient c = clients[index];
        if (c == null) {
            return;
        }
        IotProperties.Mqtt cfg = props.getMqtt();
        String[] filters = Topics.PLATFORM_SUBSCRIPTIONS;
        String group = cfg.getSharedGroup();
        if (shared.get() && clients.length > 1 && group != null && !group.isBlank()) {
            String[] sharedFilters = new String[filters.length];
            for (int i = 0; i < filters.length; i++) {
                sharedFilters[i] = Topics.shared(group, filters[i]);
            }
            try {
                IMqttToken token = c.subscribeWithResponse(sharedFilters, qosArray(filters.length));
                if (granted(token)) {
                    log.info("MQTT gateway connection {}/{} subscribed to {} shared filters (group '{}')", index + 1,
                            clients.length, filters.length, group);
                    return;
                }
                log.error("Broker refused the shared subscription ($share/{}); falling back to one plain MQTT "
                        + "connection. Check that shared subscriptions are enabled on the broker.", group);
            } catch (MqttException e) {
                log.error("Shared subscription failed ({}); falling back to one plain MQTT connection", e.getMessage());
            }
            degradedToSingleConnection();
        }
        try {
            c.subscribe(filters, qosArray(filters.length));
            log.info("MQTT gateway connection {}/{} subscribed to {} topic filters", index + 1, clients.length,
                    filters.length);
        } catch (MqttException e) {
            log.error("MQTT subscribe failed: {}", e.getMessage());
        }
    }

    /**
     * The broker does not support shared subscriptions. Extra connections would each get a copy of every message,
     * so keep exactly one.
     */
    private void degradedToSingleConnection() {
        shared.set(false);
        for (int i = 1; i < clients.length; i++) {
            close(clients[i]);
            clients[i] = null;
        }
    }

    private static boolean granted(IMqttToken token) {
        int[] granted = token.getGrantedQos();
        if (granted == null || granted.length == 0) {
            return false;
        }
        for (int q : granted) {
            if (q == 0x80) { // SUBACK failure
                return false;
            }
        }
        return true;
    }

    private int[] qosArray(int length) {
        int[] qos = new int[length];
        java.util.Arrays.fill(qos, props.getMqtt().getSubscribeQos() == 0 ? 0 : 1);
        return qos;
    }

    private static void close(MqttClient c) {
        if (c == null) {
            return;
        }
        try {
            if (c.isConnected()) {
                c.disconnect(2000);
            }
            c.close();
        } catch (MqttException e) {
            log.debug("Error closing MQTT client: {}", e.getMessage());
        }
    }

    @Override
    public void publish(String topic, byte[] payload, int qos) throws Exception {
        MqttClient target = null;
        for (MqttClient c : clients) {
            if (c != null && c.isConnected()) {
                target = c;
                break;
            }
        }
        if (target == null) {
            throw new IllegalStateException("MQTT gateway not connected");
        }
        MqttMessage m = new MqttMessage(payload);
        m.setQos(qos);
        m.setRetained(false);
        target.publish(topic, m);
    }

    @Override
    public boolean isConnected() {
        return connectedCount() > 0;
    }

    private int connectedCount() {
        int n = 0;
        for (MqttClient c : clients) {
            if (c != null && c.isConnected()) {
                n++;
            }
        }
        return n;
    }

    private final class Callback implements MqttCallbackExtended {

        private final int index;

        private Callback(int index) {
            this.index = index;
        }

        @Override
        public void connectComplete(boolean reconnect, String serverURI) {
            // Not on the Paho callback thread: a blocking subscribe from inside the callback can stall it.
            // reconnect=false is the first connect, where connectOne() subscribes right after connect().
            if (reconnect) {
                connector.execute(() -> subscribeAll(index));
            }
        }

        @Override
        public void connectionLost(Throwable cause) {
            log.warn("MQTT gateway connection {}/{} lost: {}", index + 1, clients.length,
                    cause == null ? "?" : cause.getMessage());
        }

        @Override
        public void messageArrived(String topic, MqttMessage message) {
            int lane = Math.floorMod(laneKey(topic), lanes.length);
            long arrived = System.currentTimeMillis();
            KafkaUplinkForwarder f = forwarder.getIfAvailable();
            if (f != null) {
                // kafka mode: the platform's consumer will process it; this thread only hands it to Kafka
                f.forward(topic, message.getPayload(), arrived);
                return;
            }
            lanes[lane].execute(() -> processor.handle(topic, message.getPayload(), arrived));
        }

        @Override
        public void deliveryComplete(IMqttDeliveryToken token) {
        }
    }

    private static int laneKey(String topic) {
        String[] p = topic.split("/", 4);
        return p.length >= 3 ? (p[1] + "." + p[2]).hashCode() : topic.hashCode();
    }
}
