package com.omnihub.iot.config;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.annotation.PreDestroy;
import javax.sql.DataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * A second, small connection pool used only by the ingestion write path (batch writers, device state, alarm
 * evaluation).
 *
 * Why: with one pool the HTTP requests (dashboards, pages) and the batch writers compete for the same connections.
 * A burst of slow read queries can take every connection, the writers wait, their queues grow and finally drop data -
 * that is exactly the stall observed in the load tests (see docs/tuning.md). The pool is created from the primary
 * DataSource's settings, so it works with both the normal profiles and the embedded PostgreSQL of `local`/`test`.
 *
 * Set iot.ingest.db-pool=0 to disable it and share the main pool (previous behaviour).
 */
@Component
public class IngestDb {

    private static final Logger log = LoggerFactory.getLogger(IngestDb.class);

    private final JdbcTemplate jdbc;
    private final HikariDataSource pool;

    public IngestDb(DataSource primary, IotProperties props, MeterRegistry meters) {
        int size = props.getIngest().getDbPool();
        if (size > 0 && primary instanceof HikariDataSource hikari && hikari.getJdbcUrl() != null) {
            HikariConfig cfg = new HikariConfig();
            cfg.setPoolName("ingest");
            cfg.setJdbcUrl(hikari.getJdbcUrl());
            cfg.setUsername(hikari.getUsername());
            cfg.setPassword(hikari.getPassword());
            cfg.setMaximumPoolSize(size);
            cfg.setMinimumIdle(Math.min(4, size));
            // fail fast: a batch writer that cannot get a connection gives up and counts the batch as dropped,
            // instead of blocking a lane for Hikari's default 30 seconds
            cfg.setConnectionTimeout(props.getIngest().getDbTimeoutMs());
            cfg.setInitializationFailTimeout(-1); // the first connection is taken on demand
            // without this the second pool would be invisible: no pending/timeout metrics during a load test
            cfg.setMetricRegistry(meters);
            this.pool = new HikariDataSource(cfg);
            this.jdbc = new JdbcTemplate(pool);
            log.info("Ingestion writes use their own pool '{}' with {} connections (main pool is left to HTTP)",
                    "ingest", size);
        } else {
            this.pool = null;
            this.jdbc = new JdbcTemplate(primary);
            log.info("Ingestion writes share the main connection pool (iot.ingest.db-pool={})", size);
        }
    }

    /** JdbcTemplate bound to the ingestion pool (or the primary one when the separate pool is disabled). */
    public JdbcTemplate jdbc() {
        return jdbc;
    }

    @PreDestroy
    void close() {
        if (pool != null) {
            pool.close();
        }
    }
}
