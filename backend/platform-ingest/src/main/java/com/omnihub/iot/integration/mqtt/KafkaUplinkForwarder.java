package com.omnihub.iot.integration.mqtt;

import com.omnihub.iot.common.Jsons;
import com.omnihub.iot.messaging.Topics;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

/**
 * kafka mode, producer side: the MQTT gateway hands every uplink message to Kafka instead of processing it.
 * Record key = clientId (productKey.deviceKey), so one device always lands in one partition and keeps its order.
 * Value = {"t": mqttTopic, "p": payloadText, "r": receivedAtMillis}.
 */
@Component
@ConditionalOnProperty(prefix = "iot.ingest", name = "mode", havingValue = "kafka")
public class KafkaUplinkForwarder {

    private static final Logger log = LoggerFactory.getLogger(KafkaUplinkForwarder.class);

    private final KafkaTemplate<String, String> kafka;
    private final String topic;
    private final IngestStats stats;

    public KafkaUplinkForwarder(KafkaTemplate<String, String> kafka, IngestProperties props, IngestStats stats) {
        this.kafka = kafka;
        this.topic = props.getKafka().getTopic();
        this.stats = stats;
    }

    public void forward(String mqttTopic, byte[] payload, long receivedAt) {
        String key = Topics.parse(mqttTopic).map(Topics.Parsed::clientId).orElse(mqttTopic);
        Map<String, Object> env = new LinkedHashMap<>();
        env.put("t", mqttTopic);
        env.put("p", new String(payload, StandardCharsets.UTF_8));
        env.put("r", receivedAt);
        try {
            kafka.send(topic, key, Jsons.toJson(env)).whenComplete((r, ex) -> {
                if (ex != null) {
                    stats.dropped();
                    log.warn("Forwarding to Kafka failed, message dropped: {}", ex.toString());
                }
            });
        } catch (Exception e) {
            // e.g. producer buffer full / metadata timeout: never block or break the MQTT callback thread
            stats.dropped();
            log.warn("Forwarding to Kafka failed, message dropped: {}", e.toString());
        }
    }
}
