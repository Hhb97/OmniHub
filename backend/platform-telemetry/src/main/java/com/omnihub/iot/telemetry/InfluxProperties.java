package com.omnihub.iot.telemetry;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

@Data
@ConfigurationProperties(prefix = "iot.influxdb")
public class InfluxProperties {
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
