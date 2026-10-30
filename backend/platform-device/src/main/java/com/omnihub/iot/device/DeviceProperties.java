package com.omnihub.iot.device;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

@Data
@ConfigurationProperties(prefix = "iot.device")
public class DeviceProperties {
    private int offlineSweepSeconds = 15;
    private int dedupCacheSize = 20000;
    /** keep all devices in memory for MQTT authentication (no DB access on connect) */
    private boolean authCacheWarmup = true;
    /** full reload interval of that cache; also how fast changes made by other instances are noticed */
    private int authCacheRefreshSeconds = 300;
    /** above this number of devices the warm-up is skipped and devices are cached lazily */
    private int authCacheMax = 200000;
}
