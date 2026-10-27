package com.omnihub.iot.telemetry;

import com.fasterxml.jackson.databind.JsonNode;
import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * Storage of property history (time series). The latest value per property always stays in PostgreSQL
 * (iot_device_latest); only history goes through this interface. Implementations: PostgreSQL (default) and
 * InfluxDB 1.x, selected with iot.history.store = postgres | influxdb.
 */
public interface HistoryStore {

    record Row(String identifier, JsonNode value, Instant ts) {
    }

    record Page(List<Row> rows, long total) {
    }

    void write(Long deviceId, Map<String, JsonNode> values, Instant ts);

    /** Newest first. Identifiers are already validated against ^[a-zA-Z][a-zA-Z0-9_]{0,63}$. */
    Page page(Long deviceId, List<String> identifiers, Instant from, Instant to, int size, int offset);

    /** Points are [epochMillis, value]. bucketSeconds == 0 means raw values (at most maxPoints, oldest first). */
    List<List<Object>> series(Long deviceId, String identifier, Instant from, Instant to, long bucketSeconds,
            int maxPoints);

    /** Retention hook. Stores that expire data themselves (InfluxDB retention policy) may do nothing. */
    void purgeOlderThan(int days);
}
