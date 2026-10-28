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
    private Metrics metrics = new Metrics();
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
        /** QoS of the platform's uplink subscription: 1 = at least once, 0 = fastest (messages may be lost) */
        private int subscribeQos = 1;
        /** see application.yml: a random client id per start makes a persistent session useless */
        private boolean cleanSession = true;
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
        /** keep all devices in memory for MQTT authentication (no DB access on connect) */
        private boolean authCacheWarmup = true;
        /** full reload interval of that cache; also how fast changes made by other instances are noticed */
        private int authCacheRefreshSeconds = 300;
        /** above this number of devices the warm-up is skipped and devices are cached lazily */
        private int authCacheMax = 200000;
    }

    @Data
    public static class Metrics {
        /** bearer token Prometheus uses for /actuator/prometheus; empty = endpoint closed */
        private String token = "";
    }

    @Data
    public static class Ingest {
        /** direct: MQTT message is processed in-process. kafka: gateway forwards it to Kafka, a consumer processes it. */
        private String mode = "direct";
        private Kafka kafka = new Kafka();
        /** single-threaded MQTT processing lanes (per-device ordering); 0 = max(8, 2 x CPU cores) */
        private int lanes = 0;
        private int laneQueue = 20000;
        private Batch batch = new Batch();
    }

    /** Background batch writer settings (message log, latest values, history, device last-communication time). */
    @Data
    public static class Batch {
        /** items buffered per writer; when full new items are dropped and counted */
        private int queueCapacity = 200000;
        /** maximum rows written per statement round trip */
        private int maxBatch = 2000;
        /** maximum samples per history write (InfluxDB likes 5000-10000 points per request) */
        private int historyMaxBatch = 5000;
        /**
         * parallel writer threads per queue. Items are spread by device id, so one device always goes to the same
         * writer (its order is kept) and the writers update different rows. 1 = a single writer, as before.
         */
        private int workers = 4;
        private int historyWorkers = 2;
        private int logWorkers = 2;
        /** false = do not log successful property reports in the communication log (the dashboard trend needs them) */
        private boolean logOkProperty = true;
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
