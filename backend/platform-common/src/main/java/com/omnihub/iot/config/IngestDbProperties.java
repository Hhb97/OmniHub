package com.omnihub.iot.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/** The dedicated connection pool of the ingestion writers (see {@link IngestDb}). */
@Data
@ConfigurationProperties(prefix = "iot.ingest")
public class IngestDbProperties {

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
}
