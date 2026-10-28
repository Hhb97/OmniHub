package com.omnihub.iot.dashboard;

import com.omnihub.iot.common.BizException;
import com.omnihub.iot.config.IotProperties;
import com.omnihub.iot.device.DeviceService;
import com.omnihub.iot.device.DeviceVO;
import com.omnihub.iot.security.CurrentUser;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/** Dashboard numbers are read from the database; expensive aggregates are cached for a few seconds (see below). */
@Service
public class DashboardService {

    public record Overview(long productCount, long deviceCount, long onlineCount, long offlineCount, long inactiveCount,
            double onlineRate, long todayAlarmCount, long activeAlarmCount, long todayMessageCount) {
    }

    public record TrendPoint(Instant time, long count) {
    }

    public record LevelCount(String level, long count) {
    }

    public record DayCount(String date, long count) {
    }

    public record AlarmStats(List<LevelCount> byLevel, List<DayCount> byDay) {
    }

    public record TopDevice(Long deviceId, String name, long messageCount) {
    }

    private static final List<String> LEVELS = List.of("CRITICAL", "MAJOR", "WARNING", "INFO");
    /** Upper bound of cached answers; keys are tenant + time bucket + parameters, so this is only a safety net. */
    private static final int CACHE_MAX = 512;

    private record Cached(Object value, long expiresAt) {
    }

    private final JdbcTemplate jdbc;
    private final DeviceService deviceService;
    private final ZoneId zone;
    private final long cacheMillis;
    private final Map<String, Cached> cache = new ConcurrentHashMap<>();

    public DashboardService(JdbcTemplate jdbc, DeviceService deviceService, IotProperties props) {
        this.jdbc = jdbc;
        this.deviceService = deviceService;
        this.zone = ZoneId.of(props.getDashboardZone());
        this.cacheMillis = Math.max(0, props.getDashboardCacheSeconds()) * 1000L;
    }

    /**
     * Runs {@code loader} at most once per key and window. The dashboard is polled by every open browser tab and its
     * counts scan iot_device_message_log (millions of rows per day); without a cache a handful of open dashboards kept
     * the database busy for exactly the rows ingestion was trying to write. After the window the value is recomputed -
     * a slightly stale number is fine here, the timestamp of the data does not change.
     */
    @SuppressWarnings("unchecked")
    private <T> T cached(String key, Supplier<T> loader) {
        if (cacheMillis <= 0) {
            return loader.get();
        }
        long now = System.currentTimeMillis();
        Cached hit = cache.get(key);
        if (hit != null && hit.expiresAt() > now) {
            return (T) hit.value();
        }
        T value = loader.get();
        if (cache.size() >= CACHE_MAX) {
            cache.clear();
        }
        cache.put(key, new Cached(value, System.currentTimeMillis() + cacheMillis));
        return value;
    }

    /** tenant + day/hour: a cached answer must never leak into the next day's numbers. */
    private String key(Object... parts) {
        StringBuilder sb = new StringBuilder().append(CurrentUser.tenantId());
        for (Object p : parts) {
            sb.append(':').append(p);
        }
        return sb.toString();
    }

    public Overview overview() {
        String day = LocalDate.now(zone).toString();
        return cached(key("overview", day), () -> loadOverview());
    }

    private Overview loadOverview() {
        Long tenant = CurrentUser.tenantId();
        Timestamp today = Timestamp.from(ZonedDateTime.now(zone).truncatedTo(ChronoUnit.DAYS).toInstant());
        long products = count("SELECT count(*) FROM iot_product WHERE tenant_id = ? AND deleted = 0", tenant);
        Map<String, Long> byStatus = new HashMap<>();
        jdbc.query("SELECT online_status, count(*) FROM iot_device WHERE tenant_id = ? AND deleted = 0 GROUP BY 1",
                rs -> {
                    byStatus.put(rs.getString(1), rs.getLong(2));
                }, tenant);
        long online = byStatus.getOrDefault("ONLINE", 0L);
        long offline = byStatus.getOrDefault("OFFLINE", 0L);
        long inactive = byStatus.getOrDefault("INACTIVE", 0L);
        long total = online + offline + inactive;
        // online rate = ONLINE devices / all devices (devices that never connected count as not online)
        double rate = total == 0 ? 0 : BigDecimal.valueOf(online * 100.0 / total).setScale(2, RoundingMode.HALF_UP).doubleValue();
        long todayAlarms = count("SELECT count(*) FROM alarm_record WHERE tenant_id = ? AND first_triggered_at >= ?", tenant,
                today);
        long activeAlarms = count("SELECT count(*) FROM alarm_record WHERE tenant_id = ? AND status = 'ACTIVE'", tenant);
        long messages = count("""
                SELECT count(*) FROM iot_device_message_log m JOIN iot_device d ON d.id = m.device_id
                WHERE d.tenant_id = ? AND m.created_at >= ? AND m.direction = 'UP' AND m.type = 'PROPERTY'""", tenant,
                today);
        return new Overview(products, total, online, offline, inactive, rate, todayAlarms, activeAlarms, messages);
    }

    public List<TrendPoint> reportTrend(int hours) {
        if (hours < 1 || hours > 168) {
            throw BizException.badRequest("hours 范围为 1-168");
        }
        Instant end = Instant.now().truncatedTo(ChronoUnit.HOURS);
        return cached(key("trend", hours, end), () -> loadTrend(hours));
    }

    private List<TrendPoint> loadTrend(int hours) {
        Instant end = Instant.now().truncatedTo(ChronoUnit.HOURS);
        Instant start = end.minus(hours - 1L, ChronoUnit.HOURS);
        Map<Instant, Long> found = new HashMap<>();
        jdbc.query("""
                SELECT date_trunc('hour', m.created_at AT TIME ZONE 'UTC') AS h, count(*)
                FROM iot_device_message_log m JOIN iot_device d ON d.id = m.device_id
                WHERE d.tenant_id = ? AND m.created_at >= ? AND m.direction = 'UP' AND m.type = 'PROPERTY'
                GROUP BY 1""", rs -> {
                    found.put(rs.getTimestamp(1).toLocalDateTime().toInstant(java.time.ZoneOffset.UTC), rs.getLong(2));
                }, CurrentUser.tenantId(), Timestamp.from(start));
        List<TrendPoint> out = new ArrayList<>();
        for (int i = 0; i < hours; i++) {
            Instant t = start.plus(i, ChronoUnit.HOURS);
            out.add(new TrendPoint(t, found.getOrDefault(t, 0L)));
        }
        return out;
    }

    public AlarmStats alarmStats(int days) {
        if (days < 1 || days > 90) {
            throw BizException.badRequest("days 范围为 1-90");
        }
        return cached(key("alarms", days, LocalDate.now(zone)), () -> loadAlarmStats(days));
    }

    private AlarmStats loadAlarmStats(int days) {
        Long tenant = CurrentUser.tenantId();
        LocalDate firstDay = LocalDate.now(zone).minusDays(days - 1L);
        Timestamp since = Timestamp.from(firstDay.atStartOfDay(zone).toInstant());
        Map<String, Long> levels = new HashMap<>();
        jdbc.query("SELECT level, count(*) FROM alarm_record WHERE tenant_id = ? AND first_triggered_at >= ? GROUP BY 1",
                rs -> {
                    levels.put(rs.getString(1), rs.getLong(2));
                }, tenant, since);
        Map<String, Long> perDay = new HashMap<>();
        jdbc.query("""
                SELECT to_char(first_triggered_at AT TIME ZONE ?, 'YYYY-MM-DD'), count(*) FROM alarm_record
                WHERE tenant_id = ? AND first_triggered_at >= ? GROUP BY 1""", rs -> {
                    perDay.put(rs.getString(1), rs.getLong(2));
                }, zone.getId(), tenant, since);
        List<LevelCount> byLevel = LEVELS.stream().map(l -> new LevelCount(l, levels.getOrDefault(l, 0L))).toList();
        List<DayCount> byDay = new ArrayList<>();
        for (int i = 0; i < days; i++) {
            String d = firstDay.plusDays(i).toString();
            byDay.add(new DayCount(d, perDay.getOrDefault(d, 0L)));
        }
        return new AlarmStats(byLevel, byDay);
    }

    public List<DeviceVO> recentDevices(int limit) {
        return deviceService.recentlyOnline(limit);
    }

    public List<TopDevice> topDevices(int limit) {
        int lim = Math.max(1, Math.min(limit, 20));
        return cached(key("top", lim), () -> loadTopDevices(lim));
    }

    private List<TopDevice> loadTopDevices(int lim) {
        List<Object[]> rows = new ArrayList<>();
        jdbc.query("""
                SELECT m.device_id, count(*) AS c FROM iot_device_message_log m JOIN iot_device d ON d.id = m.device_id
                WHERE d.tenant_id = ? AND m.created_at >= now() - interval '24 hours' AND m.direction = 'UP'
                  AND m.type = 'PROPERTY' GROUP BY m.device_id ORDER BY c DESC LIMIT ?""", rs -> {
                    rows.add(new Object[] { rs.getLong(1), rs.getLong(2) });
                }, CurrentUser.tenantId(), lim);
        if (rows.isEmpty()) {
            return List.of();
        }
        Map<Long, String> names = deviceService.names(Set.copyOf(rows.stream().map(r -> (Long) r[0]).toList()));
        return rows.stream().map(r -> new TopDevice((Long) r[0], names.get((Long) r[0]), (Long) r[1])).toList();
    }

    private long count(String sql, Object... args) {
        Long n = jdbc.queryForObject(sql, Long.class, args);
        return n == null ? 0 : n;
    }
}
