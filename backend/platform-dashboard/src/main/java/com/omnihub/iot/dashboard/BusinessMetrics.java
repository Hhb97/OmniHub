package com.omnihub.iot.dashboard;

import com.omnihub.iot.device.DeviceRuntime;
import com.omnihub.iot.device.DeviceStatusEvent;
import com.omnihub.iot.integration.ws.WsHub;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.Meter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tags;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Business figures for Prometheus: devices by status (overall and per product), products, active alarms, commands in
 * flight, and a few consistency numbers. The values are read from the database by a background task every few
 * seconds and published through gauges that only read memory, so a scrape never queries the database.
 * Figures cover all tenants of this installation.
 */
@Component
public class BusinessMetrics {

    private static final Logger log = LoggerFactory.getLogger(BusinessMetrics.class);
    private static final List<String> DEVICE_STATUS = List.of("ONLINE", "OFFLINE", "INACTIVE");
    private static final List<String> ALARM_LEVELS = List.of("CRITICAL", "MAJOR", "WARNING", "INFO");
    private static final List<String> COMMAND_STATUS = List.of("PENDING", "SENT", "RECEIVED");
    /** Upper bound for the per-product series, so a huge number of products cannot blow up the metric cardinality. */
    private static final int MAX_PRODUCT_SERIES = 600;

    private final JdbcTemplate jdbc;
    private final MeterRegistry registry;
    private final DeviceRuntime runtime;
    private final Map<String, AtomicLong> values = new ConcurrentHashMap<>();
    private final Map<String, Meter.Id> ids = new ConcurrentHashMap<>();
    private final Map<String, Counter> statusChanges = new ConcurrentHashMap<>();
    private final Counter refreshFailures;
    private final AtomicLong lastRefreshSeconds = new AtomicLong();

    public BusinessMetrics(JdbcTemplate jdbc, MeterRegistry registry, DeviceRuntime runtime, WsHub ws) {
        this.jdbc = jdbc;
        this.registry = registry;
        this.runtime = runtime;
        Gauge.builder("iot_devices_online_in_memory", runtime, DeviceRuntime::onlineKnownCount)
                .description("Devices this instance believes are online (compare with iot_devices{status=ONLINE})")
                .register(registry);
        Gauge.builder("iot_device_auth_cache_entries", runtime, DeviceRuntime::authCacheSize)
                .description("Devices in the connect authentication cache").register(registry);
        Gauge.builder("iot_ws_sessions", ws, WsHub::sessionCount)
                .description("Open page (WebSocket) subscriptions").register(registry);
        Gauge.builder("iot_business_metrics_last_refresh_timestamp_seconds", lastRefreshSeconds, AtomicLong::get)
                .description("When the business figures were last read from the database").register(registry);
        this.refreshFailures = Counter.builder("iot_business_metrics_refresh_failures_total")
                .description("Failed attempts to read the business figures").register(registry);
        // the status series exist from the start (0), so dashboards show 0 instead of "no data"
        DEVICE_STATUS.forEach(s -> set("iot_devices", Tags.of("status", s), 0));
        ALARM_LEVELS.forEach(l -> set("iot_alarms_active", Tags.of("level", l), 0));
        COMMAND_STATUS.forEach(s -> set("iot_commands_in_flight", Tags.of("status", s), 0));
    }

    /** Counts business status changes (connect / disconnect / timeout) to show connection churn. */
    @EventListener
    public void onDeviceStatus(DeviceStatusEvent e) {
        statusChanges.computeIfAbsent(e.onlineStatus(), s -> Counter.builder("iot_device_status_changes_total")
                .tag("to", s).description("Device online status changes").register(registry)).increment();
    }

    @Scheduled(initialDelayString = "#{${iot.metrics.business-refresh-seconds:15} * 1000}",
            fixedDelayString = "#{${iot.metrics.business-refresh-seconds:15} * 1000}")
    public void refresh() {
        try {
            Map<String, Long> devices = new LinkedHashMap<>();
            jdbc.query("SELECT online_status, count(*) FROM iot_device WHERE deleted = 0 GROUP BY 1",
                    rs -> {
                        devices.put(rs.getString(1), rs.getLong(2));
                    });
            DEVICE_STATUS.forEach(s -> set("iot_devices", Tags.of("status", s), devices.getOrDefault(s, 0L)));
            set("iot_devices_disabled", Tags.empty(),
                    count("SELECT count(*) FROM iot_device WHERE deleted = 0 AND enabled = FALSE"));

            Set<String> seen = new HashSet<>();
            jdbc.query("""
                    SELECT status, count(*) FROM iot_product WHERE deleted = 0 GROUP BY 1""", rs -> {
                        seen.add(set("iot_products", Tags.of("status", rs.getString(1)), rs.getLong(2)));
                    });
            retain("iot_products", seen);

            Set<String> perProduct = new HashSet<>();
            int[] n = { 0 };
            jdbc.query("""
                    SELECT p.product_key, p.name, d.online_status, count(*)
                    FROM iot_device d JOIN iot_product p ON p.id = d.product_id
                    WHERE d.deleted = 0 AND p.deleted = 0 GROUP BY 1, 2, 3 ORDER BY 4 DESC""", rs -> {
                        if (n[0]++ < MAX_PRODUCT_SERIES) {
                            perProduct.add(set("iot_product_devices", Tags.of("product_key", rs.getString(1),
                                    "product_name", rs.getString(2), "status", rs.getString(3)), rs.getLong(4)));
                        }
                    });
            retain("iot_product_devices", perProduct);

            Map<String, Long> alarms = new LinkedHashMap<>();
            jdbc.query("SELECT level, count(*) FROM alarm_record WHERE status = 'ACTIVE' GROUP BY 1", rs -> {
                alarms.put(rs.getString(1), rs.getLong(2));
            });
            ALARM_LEVELS.forEach(l -> set("iot_alarms_active", Tags.of("level", l), alarms.getOrDefault(l, 0L)));
            set("iot_alarms_unacknowledged", Tags.empty(), count(
                    "SELECT count(*) FROM alarm_record WHERE status = 'ACTIVE' AND acknowledged = FALSE"));
            set("iot_alarms_triggered_24h", Tags.empty(), count(
                    "SELECT count(*) FROM alarm_record WHERE first_triggered_at >= now() - interval '24 hours'"));
            set("iot_alarm_rules_enabled", Tags.empty(),
                    count("SELECT count(*) FROM alarm_rule WHERE deleted = 0 AND enabled = TRUE"));

            Map<String, Long> commands = new LinkedHashMap<>();
            jdbc.query("""
                    SELECT status, count(*) FROM iot_device_command
                    WHERE status IN ('PENDING', 'SENT', 'RECEIVED') GROUP BY 1""", rs -> {
                        commands.put(rs.getString(1), rs.getLong(2));
                    });
            COMMAND_STATUS.forEach(s -> set("iot_commands_in_flight", Tags.of("status", s), commands.getOrDefault(s, 0L)));

            lastRefreshSeconds.set(System.currentTimeMillis() / 1000);
        } catch (Exception e) {
            refreshFailures.increment();
            log.warn("Reading the business metrics failed: {}", e.getMessage());
        }
    }

    private long count(String sql) {
        Long v = jdbc.queryForObject(sql, Long.class);
        return v == null ? 0 : v;
    }

    /** Sets a gauge value, registering the gauge on first use. Returns the series key. */
    private String set(String name, Tags tags, long value) {
        String key = name + tags.stream().map(t -> t.getKey() + "=" + t.getValue()).sorted()
                .reduce("", (a, b) -> a + "," + b);
        values.computeIfAbsent(key, k -> {
            AtomicLong holder = new AtomicLong();
            Gauge g = Gauge.builder(name, holder, AtomicLong::get).tags(tags).register(registry);
            ids.put(k, g.getId());
            return holder;
        }).set(value);
        return key;
    }

    /** Drops the series of a dynamic metric that no longer exist (deleted product, status without devices). */
    private void retain(String name, Set<String> keep) {
        for (String key : List.copyOf(values.keySet())) {
            if (key.startsWith(name + ",") && !keep.contains(key)) {
                Meter.Id id = ids.remove(key);
                values.remove(key);
                if (id != null) {
                    registry.remove(id);
                }
            }
        }
    }
}
