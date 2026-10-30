package com.omnihub.iot.identity.web;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

@Data
@ConfigurationProperties(prefix = "iot.metrics")
public class MetricsProperties {
    /** bearer token Prometheus uses for /actuator/prometheus; empty = endpoint closed */
    private String token = "";
}
