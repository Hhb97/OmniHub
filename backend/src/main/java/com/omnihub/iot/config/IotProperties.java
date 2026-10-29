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
    private Alarm alarm = new Alarm();
    private Ws ws = new Ws();
    private Metrics metrics = new Metrics();
    private Influxdb influxdb = new Influxdb();
    private EmbeddedPostgres embeddedPostgres = new EmbeddedPostgres();
    /** How long the dashboard aggregates (overview / trend / top devices) are cached, in seconds. 0 = always read. */
    private int dashboardCacheSeconds = 10;

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
        /**
         * Number of MQTT connections that share the uplink subscriptions. One connection is one broker session with
         * one delivery queue: at a few thousand messages per second that queue fills up and EMQX starts dropping
         * (then closes the connection with mailbox_overflow). Several connections joining a shared subscription make
         * the broker spread the messages, so neither the session queue nor the single Paho callback thread is a
         * bottleneck. 1 = single connection (a broker without shared subscriptions), 0 = same as 1.
         */
        private int ingestConnections = 4;
        /**
         * Shared-subscription group ({@code $share/<group>/<filter>}). All ingestion connections join it, so every
         * message is delivered to exactly one of them. Empty = plain subscriptions (only useful with 1 connection,
         * otherwise every connection would receive a copy of every message).
         */
        private String sharedGroup = "ingest";
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
        /**
         * What to do when a lane queue is full: block = process on the Paho callback thread (back-pressure towards the
         * broker, no message lost); drop = drop the message and count it (the broker connection stays healthy).
         */
        private String laneOverflow = "block";
        /**
         * Maximum rate at which the "latest value" of one device property is written to PostgreSQL, in milliseconds.
         * The newest sample is always written eventually (trailing flush); 0 writes every sample.
         */
        private int latestMinIntervalMs = 1000;
        /** size of the dedicated ingestion connection pool; 0 = share the main pool */
        private int dbPool = 12;
        /** wait for a connection at most this long before a batch is given up (ms) */
        private int dbTimeoutMs = 2000;
        /**
         * PostgreSQL {@code synchronous_commit} for the ingestion pool only (on/off/local/remote_write/remote_apply).
         *
         * <p>{@code off} means a batch commit returns before its WAL is flushed to disk. At a few thousand messages per
         * second the ingest writers spend most of their time waiting for the WAL group commit (pg_stat_activity
         * wait_event = WALWrite), and that wait caps the whole ingestion pipeline - the queue fills up, EMQX's session
         * queue fills up behind it and the broker starts dropping. Telemetry is not audit data: on an operating system
         * crash the last few hundred milliseconds of latest values, history and message log can be lost, while the
         * database itself stays consistent (no torn writes). History goes to InfluxDB and is unaffected.
         * Set to {@code on} to get the previous fully synchronous behaviour back.
         */
        private String synchronousCommit = "off";
        private Batch batch = new Batch();
    }

    /** Alarm evaluation runs off the MQTT threads (see AlarmEngine). */
    @Data
    public static class Alarm {
        /** how often the enabled rules are reloaded into memory */
        private int ruleRefreshSeconds = 30;
        /** max queued evaluations; when full, evaluations are dropped and counted (ingest never waits) */
        private int queueCapacity = 50000;
        /** trigger_count / last_triggered_at updates are coalesced and written at most this often (ms) */
        private int flushMs = 1000;
    }

    /** WebSocket push: serialisation and socket writes never happen on the ingestion threads. */
    @Data
    public static class Ws {
        private int senderThreads = 2;
        private int queueCapacity = 20000;
        /** property pushes of one device are merged within this window (ms); 0 pushes every message */
        private int coalesceMillis = 200;
    }

    /** Background batch writer settings (message log, latest values, history, device last-communication time). */
    @Data
    public static class Batch {
        /** items buffered per writer; when full new items are dropped and counted */
        private int queueCapacity = 200000;
        /** maximum rows written per statement round trip */
        private int maxBatch = 2000;
        /**
         * After the first item of a batch is queued, keep collecting for at most this many milliseconds before the
         * write. One INSERT of 1000 rows costs only slightly more than one of 100, and one HTTP request with 5000
         * InfluxDB points is far cheaper than 20 requests with 250 - without the linger the batch size settles at
         * "arrival rate x write duration", which shrinks exactly when the sink gets slower. 0 = write immediately.
         */
        private int lingerMs = 50;
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
