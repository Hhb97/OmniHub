package com.omnihub.iot.integration.mqtt;

import com.omnihub.iot.config.IotProperties;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import org.eclipse.paho.client.mqttv3.IMqttDeliveryToken;
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
 * Inbound messages are dispatched to a fixed number of single-threaded lanes keyed by device, which preserves
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
    private volatile MqttClient client;

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
        io.micrometer.core.instrument.Gauge.builder("iot_lane_queue_pending", lanes,
                l -> java.util.Arrays.stream(l).mapToInt(x -> x.getQueue().size()).sum())
                .description("MQTT messages waiting in the in-process lanes (direct mode backlog)").register(meters);
        io.micrometer.core.instrument.Gauge.builder("iot_lane_queue_max", lanes,
                l -> java.util.Arrays.stream(l).mapToInt(x -> x.getQueue().size()).max().orElse(0))
                .description("Backlog of the fullest lane").register(meters);
        io.micrometer.core.instrument.Gauge.builder("iot_mqtt_gateway_connected", this, g -> g.isConnected() ? 1 : 0)
                .description("1 when the platform's MQTT client is connected to the broker").register(meters);
    }

    @PostConstruct
    void start() {
        connector.schedule(this::connect, 1, TimeUnit.SECONDS);
    }

    @PreDestroy
    void stop() {
        connector.shutdownNow();
        try {
            if (client != null && client.isConnected()) {
                client.disconnect(2000);
            }
            if (client != null) {
                client.close();
            }
        } catch (MqttException e) {
            log.debug("Error closing MQTT client: {}", e.getMessage());
        }
        for (ThreadPoolExecutor lane : lanes) {
            lane.shutdown();
        }
    }

    private void connect() {
        IotProperties.Mqtt cfg = props.getMqtt();
        try {
            MqttClient c = new MqttClient(cfg.getBrokerUrl(), cfg.getClientId(), new MemoryPersistence());
            MqttConnectOptions opts = new MqttConnectOptions();
            opts.setUserName(cfg.getPlatformUsername());
            opts.setPassword(cfg.getPlatformPassword().toCharArray());
            opts.setAutomaticReconnect(true);
            opts.setCleanSession(cfg.isCleanSession());
            opts.setKeepAliveInterval(30);
            opts.setConnectionTimeout(10);
            opts.setMaxInflight(1000);
            c.setCallback(new Callback());
            c.connect(opts);
            client = c;
            log.info("MQTT gateway connected to {}", cfg.getBrokerUrl());
        } catch (MqttException e) {
            log.warn("MQTT gateway cannot connect to {} ({}); retrying in 5s", cfg.getBrokerUrl(), e.getMessage());
            connector.schedule(this::connect, 5, TimeUnit.SECONDS);
        }
    }

    private void subscribeAll(MqttClient c) {
        try {
            String[] filters = Topics.PLATFORM_SUBSCRIPTIONS;
            int[] qos = new int[filters.length];
            java.util.Arrays.fill(qos, props.getMqtt().getSubscribeQos() == 0 ? 0 : 1);
            c.subscribe(filters, qos);
            log.info("MQTT gateway subscribed to {} topic filters", filters.length);
        } catch (MqttException e) {
            log.error("MQTT subscribe failed: {}", e.getMessage());
        }
    }

    @Override
    public void publish(String topic, byte[] payload, int qos) throws Exception {
        MqttClient c = client;
        if (c == null || !c.isConnected()) {
            throw new IllegalStateException("MQTT gateway not connected");
        }
        MqttMessage m = new MqttMessage(payload);
        m.setQos(qos);
        m.setRetained(false);
        c.publish(topic, m);
    }

    @Override
    public boolean isConnected() {
        MqttClient c = client;
        return c != null && c.isConnected();
    }

    private final class Callback implements MqttCallbackExtended {

        @Override
        public void connectComplete(boolean reconnect, String serverURI) {
            // Not on the Paho callback thread: a blocking subscribe from inside the callback can stall it.
            // On the first connect this task is queued behind connect() on the single connector thread, so
            // `client` is already assigned when it runs.
            connector.execute(() -> {
                MqttClient c = client;
                if (c != null) {
                    subscribeAll(c);
                }
            });
        }

        @Override
        public void connectionLost(Throwable cause) {
            log.warn("MQTT gateway connection lost: {}", cause == null ? "?" : cause.getMessage());
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
