package com.omnihub.iot.config;

import com.zaxxer.hikari.HikariDataSource;
import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import java.io.IOException;
import java.nio.file.Path;
import javax.sql.DataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

/**
 * Local development convenience: starts a real PostgreSQL process (zonky embedded binaries) and exposes a
 * DataSource for it. Enabled only with iot.embedded-postgres.enabled=true (the `local` profile).
 */
@Configuration
@ConditionalOnClass(EmbeddedPostgres.class)
@ConditionalOnProperty(prefix = "iot.embedded-postgres", name = "enabled", havingValue = "true")
public class EmbeddedPostgresConfig {

    private static final Logger log = LoggerFactory.getLogger(EmbeddedPostgresConfig.class);

    @Bean(destroyMethod = "close")
    public EmbeddedPostgres embeddedPostgres(EmbeddedPostgresProperties cfg) throws IOException {
        log.info("Starting embedded PostgreSQL on port {} (data dir {})", cfg.getPort(), cfg.getDataDir());
        return EmbeddedPostgres.builder().setPort(cfg.getPort()).setDataDirectory(Path.of(cfg.getDataDir()))
                .setCleanDataDirectory(false).setServerConfig("timezone", "UTC").start();
    }

    @Bean
    @Primary
    public DataSource dataSource(EmbeddedPostgres pg) {
        HikariDataSource ds = new HikariDataSource();
        ds.setJdbcUrl(pg.getJdbcUrl("postgres", "postgres") + "&stringtype=unspecified");
        ds.setUsername("postgres");
        ds.setMaximumPoolSize(15);
        return ds;
    }
}
