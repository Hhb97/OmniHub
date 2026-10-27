package com.omnihub.iot.telemetry;

import com.fasterxml.jackson.databind.JsonNode;
import java.time.Instant;

/** Published after a device event (defined in the thing model) was persisted. */
public record DeviceEventReported(Long deviceId, Long tenantId, Long productId, String identifier, String name,
        String level, JsonNode params, Instant ts) {
}
