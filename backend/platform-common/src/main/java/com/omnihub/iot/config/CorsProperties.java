package com.omnihub.iot.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/** Browser origins allowed to call the REST API and open WebSockets; shared by the security and realtime modules. */
@Data
@ConfigurationProperties(prefix = "iot")
public class CorsProperties {

    /** comma separated; empty = same-origin only */
    private String corsAllowedOrigins = "";
}
