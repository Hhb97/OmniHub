package com.omnihub.iot.integration.mqtt;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/** How uplink messages enter the platform: in-process lanes (direct) or through Kafka. */
@Data
@ConfigurationProperties(prefix = "iot.ingest")
public class IngestProperties {
    /** direct: MQTT message is processed in-process. kafka: gateway forwards it to Kafka, a consumer processes it. */
    private String mode = "direct";
    private Kafka kafka = new Kafka();
    /** single-threaded MQTT processing lanes (per-device ordering); 0 = max(8, 2 x CPU cores) */
    private int lanes = 0;
    private int laneQueue = 20000;
    /**
     * What to do when a lane queue is full: block = process on the Paho callback thread (back-pressure towards the
     * broker, no message lost); drop = drop the message and count it (the broker connection stays healthy).
     */
    private String laneOverflow = "block";

    @Data
    public static class Kafka {
        private String topic = "iot-uplink";
        private String groupId = "iot-platform";
        /** consumer threads; useful up to the partition count */
        private int concurrency = 8;
        /** create the topic on startup if it does not exist */
        private boolean createTopic = true;
        private int partitions = 8;
        private short replicas = 1;
    }
}
