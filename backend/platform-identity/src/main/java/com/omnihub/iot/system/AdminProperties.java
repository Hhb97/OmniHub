package com.omnihub.iot.system;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

@Data
@ConfigurationProperties(prefix = "iot.admin")
public class AdminProperties {
    private String username = "admin";
    private String initialPassword = "";
}
