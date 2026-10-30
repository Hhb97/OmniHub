package com.omnihub.iot.integration.mqtt;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/** The platform's own MQTT client (uplink subscription, downlink publish) and the optional embedded broker. */
@Data
@ConfigurationProperties(prefix = "iot.mqtt")
public class MqttProperties {
        private String brokerUrl = "tcp://localhost:1883";
        private String platformUsername = "platform";
        private String platformPassword = "";
        private String clientId = "iot-platform";
        private String httpAuthToken = "";
        /** QoS of the platform's uplink subscription: 1 = at least once, 0 = fastest (messages may be lost) */
        private int subscribeQos = 1;
        /**
         * Number of MQTT connections that share the uplink subscriptions. One connection is one broker session with
         * one delivery queue: at a few thousand messages per second that queue fills up and EMQX starts dropping
         * (then closes the connection with mailbox_overflow). Several connections joining a shared subscription make
         * the broker spread the messages, so neither the session queue nor the single Paho callback thread is a
         * bottleneck. 1 = single connection (a broker without shared subscriptions), 0 = same as 1.
         * <p>16 is the measured profile: 16 x 4096 in flight sustained a full 15k msg/s with the broker queue empty.
         */
        private int ingestConnections = 16;
        /**
         * Paho's outbound in-flight window: how many QoS1 messages this client may publish before they are
         * acknowledged. It applies to downlink commands, not to the uplink: the broker decides how many messages it
         * hands to a subscriber, through its own {@code mqtt.max_inflight} (EMQX_MAX_INFLIGHT, 4096 in
         * docker-compose.yml). Measured uplink ceiling is therefore
         * {@code sessions x broker max_inflight / acknowledgement round trip}: 8 x 1024 stalled at 4.6k msg/s with
         * every session full, 16 x 4096 sustained 15k msg/s with the broker queue empty.
         */
        private int maxInflight = 4096;
        /**
         * Shared-subscription group ({@code $share/<group>/<filter>}). All ingestion connections join it, so every
         * message is delivered to exactly one of them. Empty = plain subscriptions (only useful with 1 connection,
         * otherwise every connection would receive a copy of every message).
         */
        private String sharedGroup = "ingest";
        /** see application.yml: a random client id per start makes a persistent session useless */
        private boolean cleanSession = true;
        private EmbeddedBroker embeddedBroker = new EmbeddedBroker();

    @Data
    public static class EmbeddedBroker {
        private boolean enabled = false;
        private String host = "0.0.0.0";
        private int port = 1883;
    }
}
