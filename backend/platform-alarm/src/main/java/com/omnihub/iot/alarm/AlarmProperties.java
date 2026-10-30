package com.omnihub.iot.alarm;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/** Alarm evaluation runs off the MQTT threads (see AlarmEngine). */
@Data
@ConfigurationProperties(prefix = "iot.alarm")
public class AlarmProperties {
    /** how often the enabled rules are reloaded into memory */
    private int ruleRefreshSeconds = 30;
    /** max queued evaluations; when full, evaluations are dropped and counted (ingest never waits) */
    private int queueCapacity = 50000;
    /** trigger_count / last_triggered_at updates are coalesced and written at most this often (ms) */
    private int flushMs = 1000;
}
