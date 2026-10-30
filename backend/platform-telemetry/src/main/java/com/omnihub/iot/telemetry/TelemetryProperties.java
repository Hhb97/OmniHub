package com.omnihub.iot.telemetry;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/** Write path of the telemetry module: the "latest value" sampling and the background batch writers. */
@Data
@ConfigurationProperties(prefix = "iot.ingest")
public class TelemetryProperties {

    /**
     * Maximum rate at which the "latest value" of one device property is written to PostgreSQL, in milliseconds.
     * The newest sample is always written eventually (trailing flush); 0 writes every sample.
     */
    private int latestMinIntervalMs = 1000;
    private Batch batch = new Batch();

    /** Background batch writer settings (message log, latest values, history). */
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
        private int historyWorkers = 4;
        private int logWorkers = 2;
        /** false = do not log successful property reports in the communication log (the dashboard trend needs them) */
        private boolean logOkProperty = false;
    }
}
