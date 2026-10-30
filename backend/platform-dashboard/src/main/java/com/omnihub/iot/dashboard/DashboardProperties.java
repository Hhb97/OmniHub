package com.omnihub.iot.dashboard;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/** The keys sit directly under "iot" (iot.dashboard-zone, iot.dashboard-cache-seconds) and stay there. */
@Data
@ConfigurationProperties(prefix = "iot")
public class DashboardProperties {
    /** Time zone that defines "today" / day buckets on the dashboard. */
    private String dashboardZone = "UTC";
    /** How long the dashboard aggregates (overview / trend / top devices) are cached, in seconds. 0 = always read. */
    private int dashboardCacheSeconds = 10;
}
