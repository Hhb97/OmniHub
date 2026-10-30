package com.omnihub.iot.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

@Data
@ConfigurationProperties(prefix = "iot.embedded-postgres")
public class EmbeddedPostgresProperties {
    private boolean enabled = false;
    private int port = 15432;
    private String dataDir = "./data/pgdata";
}
