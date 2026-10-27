package com.omnihub.iot.telemetry;

import com.fasterxml.jackson.databind.JsonNode;
import java.time.Instant;
import java.util.Map;

/** Published after valid property values of one report were persisted. */
public record PropertyReportedEvent(Long deviceId, Long tenantId, Long productId, Instant ts,
        Map<String, JsonNode> values) {
}
