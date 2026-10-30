package com.omnihub.iot.identity.core;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

@Data
@ConfigurationProperties(prefix = "iot.security")
public class SecurityProperties {
    private String jwtSecret = "";
    private int jwtTtlMinutes = 120;
}
