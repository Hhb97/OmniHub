package com.omnihub.iot.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

@Data
@ConfigurationProperties(prefix = "iot")
public class IotProperties {

    private Security security = new Security();
    private Admin admin = new Admin();
    private String corsAllowedOrigins = "";
    /** Time zone that defines "today" / day buckets on the dashboard. */
    private String dashboardZone = "UTC";
    private Mqtt mqtt = new Mqtt();
    private Device device = new Device();
    private History history = new History();
    private Ingest ingest = new Ingest();
    private Influxdb influxdb = new Influxdb();
    private EmbeddedPostgres embeddedPostgres = new EmbeddedPostgres();

    @Data
    public static class Security {
        private String jwtSecret = "";
        private int jwtTtlMinutes = 120;
    }

    @Data
    public static class Admin {
        private String username = "admin";
        private String initialPassword = "";
    }

    @Data
    public static class Mqtt {
        private String brokerUrl = "tcp://localhost:1883";
        private String platformUsername = "platform";
        private String platformPassword = "";
        private String clientId = "iot-platform";
        private String httpAuthToken = "";
        private EmbeddedBroker embeddedBroker = new EmbeddedBroker();
    }

    @Data
    public static class EmbeddedBroker {
        private boolean enabled = false;
        private String host = "0.0.0.0";
        private int port = 1883;
    }

    @Data
    public static class Device {
        private int offlineSweepSeconds = 15;
        private int dedupCacheSize = 20000;
    }

    @Data
    public static class Ingest {
        /** direct: MQTT message is processed in-process. kafka: gateway forwards it to Kafka, a consumer processes it. */
        private String mode = "direct";
        private Kafka kafka = new Kafka();
    }

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

    @Data
    public static class History {
        /** postgres | influxdb */
        private String store = "postgres";
    }

    @Data
    public static class Influxdb {
        private String url = "http://localhost:8086";
        private String database = "iot";
        private String username = "";
        private String password = "";
        /** empty = the database's default retention policy */
        private String retentionPolicy = "";
        /** run CREATE DATABASE on startup (needs a user allowed to do so) */
        private boolean createDatabase = false;
        /** when createDatabase is true and > 0, also create a default retention policy of this many days */
        private int retentionDays = 0;
    }

    @Data
    public static class EmbeddedPostgres {
        private boolean enabled = false;
        private int port = 15432;
        private String dataDir = "./data/pgdata";
    }
}
