package com.omnihub.iot.integration.mqtt;

import com.fasterxml.jackson.databind.JsonNode;
import com.omnihub.iot.common.Jsons;
import java.nio.charset.StandardCharsets;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

/** kafka mode, consumer side: feeds records back into the same {@link UplinkProcessor} used by direct mode. */
@Component
@ConditionalOnProperty(prefix = "iot.ingest", name = "mode", havingValue = "kafka")
public class KafkaUplinkConsumer {

    private static final Logger log = LoggerFactory.getLogger(KafkaUplinkConsumer.class);

    private final UplinkProcessor processor;

    public KafkaUplinkConsumer(UplinkProcessor processor) {
        this.processor = processor;
    }

    @KafkaListener(topics = "${iot.ingest.kafka.topic}", groupId = "${iot.ingest.kafka.group-id}",
            concurrency = "${iot.ingest.kafka.concurrency}")
    public void onRecord(String value) {
        JsonNode env;
        try {
            env = Jsons.parse(value);
        } catch (IllegalArgumentException e) {
            log.warn("Skipping malformed Kafka record");
            return;
        }
        if (env == null || !env.hasNonNull("t") || !env.has("p")) {
            log.warn("Skipping Kafka record without topic/payload");
            return;
        }
        long receivedAt = env.path("r").asLong(System.currentTimeMillis());
        processor.handle(env.get("t").asText(), env.get("p").asText().getBytes(StandardCharsets.UTF_8), receivedAt);
    }
}
