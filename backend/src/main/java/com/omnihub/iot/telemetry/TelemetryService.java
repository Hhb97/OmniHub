package com.omnihub.iot.telemetry;

import com.fasterxml.jackson.databind.JsonNode;
import com.omnihub.iot.common.BatchQueue;
import com.omnihub.iot.common.BizException;
import com.omnihub.iot.common.Jsons;
import com.omnihub.iot.common.PageResult;
import com.omnihub.iot.config.IotProperties;
import com.omnihub.iot.device.Device;
import com.omnihub.iot.device.DeviceRuntime.DeviceRef;
import com.omnihub.iot.device.DeviceService;
import com.omnihub.iot.system.service.ConfigService;
import com.omnihub.iot.thingmodel.ThingModelDefs;
import com.omnihub.iot.thingmodel.ThingModelDefs.Content;
import com.omnihub.iot.thingmodel.ThingModelService;
import com.omnihub.iot.thingmodel.ValueValidator;
import jakarta.annotation.PreDestroy;
import java.sql.PreparedStatement;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

/**
 * Property / event ingestion (write side) and telemetry queries (read side).
 * History lives in iot_device_property_history, separated from business tables; swapping it for a time-series
 * database only affects this class.
 */
@Service
public class TelemetryService {

    private static final Logger log = LoggerFactory.getLogger(TelemetryService.class);
    private static final Pattern IDENT = Pattern.compile("^[a-zA-Z][a-zA-Z0-9_]{0,63}$");
    private static final int MAX_POINTS = 2000;
    private static final int MAX_IDENTIFIERS = 10;
    private static final Duration MAX_HISTORY_SPAN = Duration.ofDays(7);
    private static final Duration MAX_TREND_SPAN = Duration.ofDays(31);

    public record IngestResult(Map<String, JsonNode> accepted, Map<String, String> rejected) {
    }

    public record LatestVO(String identifier, String name, String unit, String dataType, JsonNode value,
            Instant reportedAt) {
    }

    public record HistoryVO(String identifier, JsonNode value, Instant ts) {
    }

    public record SeriesVO(String identifier, String name, String unit, List<List<Object>> points) {
    }

    public record TrendVO(String interval, List<SeriesVO> series) {
    }

    public record EventVO(Long id, String identifier, String name, String level, JsonNode params, Instant ts) {
    }

    public record MessageVO(Long id, String direction, String type, String topic, String payload, String result,
            String message, Instant createdAt) {
    }

    private final JdbcTemplate jdbc;
    private final ThingModelService thingModels;
    private final DeviceService deviceService;
    private final ConfigService config;
    private final ApplicationEventPublisher events;
    private final HistoryStore history;
    /** latest values (PostgreSQL) and history (PostgreSQL or InfluxDB) have their own queue and thread, so a slow
     *  history store never delays the latest values and vice versa */
    private final BatchQueue<HistoryStore.Sample> latestWriter;
    private final BatchQueue<HistoryStore.Sample> historyWriter;

    public TelemetryService(JdbcTemplate jdbc, ThingModelService thingModels, DeviceService deviceService,
            ConfigService config, ApplicationEventPublisher events, HistoryStore history, IotProperties props,
            io.micrometer.core.instrument.MeterRegistry meters) {
        this.history = history;
        IotProperties.Batch b = props.getIngest().getBatch();
        this.latestWriter = new BatchQueue<>("latest", b.getQueueCapacity(), b.getMaxBatch(), 100, this::flushLatest).bind(meters);
        this.historyWriter = new BatchQueue<>("history", b.getQueueCapacity(), b.getHistoryMaxBatch(), 100,
                this::flushHistory).bind(meters);
        this.jdbc = jdbc;
        this.thingModels = thingModels;
        this.deviceService = deviceService;
        this.config = config;
        this.events = events;
    }

    // ------------------------------------------------------------------ ingestion

    public IngestResult ingestProperties(DeviceRef ref, JsonNode params, Instant ts) {
        Content model = thingModels.current(ref.productId()).content();
        Map<String, JsonNode> accepted = new LinkedHashMap<>();
        Map<String, String> rejected = new LinkedHashMap<>();
        params.fields().forEachRemaining(e -> {
            ThingModelDefs.Property def = model.property(e.getKey());
            if (def == null) {
                rejected.put(e.getKey(), "物模型中未定义该属性");
                return;
            }
            String err = ValueValidator.validate(def.dataType(), def.spec(), e.getValue());
            if (err != null) {
                rejected.put(e.getKey(), err);
            } else {
                accepted.put(e.getKey(), e.getValue());
            }
        });
        if (!accepted.isEmpty()) {
            persist(ref.id(), accepted, ts);
            events.publishEvent(new PropertyReportedEvent(ref.id(), ref.tenantId(), ref.productId(), ts, accepted));
        }
        return new IngestResult(accepted, rejected);
    }

    /** Queues the values; the background writer stores them in batches (latest values + history). */
    private void persist(Long deviceId, Map<String, JsonNode> values, Instant ts) {
        for (Map.Entry<String, JsonNode> e : values.entrySet()) {
            HistoryStore.Sample sample = new HistoryStore.Sample(deviceId, e.getKey(), e.getValue(), ts);
            latestWriter.offer(sample);
            historyWriter.offer(sample);
        }
    }

    public long droppedWrites() {
        return latestWriter.dropped() + historyWriter.dropped();
    }

    @PreDestroy
    void close() {
        latestWriter.close();
        historyWriter.close();
    }

    private static final int CHUNK = 5000;

    private void flushLatest(List<HistoryStore.Sample> batch) {
        upsertLatest(batch); // a failure is logged by the queue; the batch is lost, history is unaffected
    }

    private void flushHistory(List<HistoryStore.Sample> batch) {
        try {
            history.writeBatch(batch);
        } catch (RuntimeException first) {
            // one retry for transient errors (connection reset, brief timeout); then the batch is given up
            try {
                Thread.sleep(300);
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                throw first;
            }
            history.writeBatch(batch);
        }
    }

    /** Keeps only the newest sample per (device, property), then one INSERT ... ON CONFLICT per chunk. */
    private void upsertLatest(List<HistoryStore.Sample> batch) {
        Map<String, HistoryStore.Sample> newest = new LinkedHashMap<>();
        for (HistoryStore.Sample s : batch) {
            newest.merge(s.deviceId() + ":" + s.identifier(), s, (a, b) -> b.ts().isBefore(a.ts()) ? a : b);
        }
        List<HistoryStore.Sample> list = new ArrayList<>(newest.values());
        for (int from = 0; from < list.size(); from += CHUNK) {
            List<HistoryStore.Sample> part = list.subList(from, Math.min(list.size(), from + CHUNK));
            int n = part.size();
            Long[] dev = new Long[n];
            String[] ident = new String[n];
            String[] json = new String[n];
            Timestamp[] ts = new Timestamp[n];
            for (int i = 0; i < n; i++) {
                HistoryStore.Sample x = part.get(i);
                dev[i] = x.deviceId();
                ident[i] = x.identifier();
                json[i] = Jsons.toJson(x.value());
                ts[i] = Timestamp.from(x.ts());
            }
            // a late (out-of-order) report never overwrites a newer latest value
            jdbc.execute((ConnectionCallback<Void>) con -> {
                try (PreparedStatement ps = con.prepareStatement("""
                        INSERT INTO iot_device_latest (device_id, identifier, value, reported_at)
                        SELECT d, i, v::jsonb, t FROM unnest(?::bigint[], ?::varchar[], ?::text[], ?::timestamptz[]) AS x(d, i, v, t)
                        ON CONFLICT (device_id, identifier) DO UPDATE SET value = EXCLUDED.value, reported_at = EXCLUDED.reported_at
                        WHERE iot_device_latest.reported_at <= EXCLUDED.reported_at""")) {
                    ps.setArray(1, con.createArrayOf("bigint", dev));
                    ps.setArray(2, con.createArrayOf("varchar", ident));
                    ps.setArray(3, con.createArrayOf("text", json));
                    ps.setArray(4, con.createArrayOf("timestamptz", ts));
                    ps.executeUpdate();
                }
                return null;
            });
        }
    }

    /** @return error message, or null when the event was accepted and stored. */
    public String ingestEvent(DeviceRef ref, String identifier, JsonNode params, Instant ts) {
        ThingModelDefs.Event def = thingModels.current(ref.productId()).content().event(identifier);
        if (def == null) {
            return "物模型中未定义该事件";
        }
        jdbc.update("""
                INSERT INTO iot_device_event (device_id, identifier, name, level, params, ts)
                VALUES (?, ?, ?, ?, ?::jsonb, ?)""", ref.id(), identifier, def.name(), def.level(),
                params == null ? null : Jsons.toJson(params), Timestamp.from(ts));
        events.publishEvent(new DeviceEventReported(ref.id(), ref.tenantId(), ref.productId(), identifier,
                def.name(), def.level(), params, ts));
        return null;
    }

    // ------------------------------------------------------------------ queries

    public List<LatestVO> latest(Long deviceId) {
        Device d = deviceService.require(deviceId);
        Content model = thingModels.current(d.getProductId()).content();
        return jdbc.query("SELECT identifier, value::text, reported_at FROM iot_device_latest WHERE device_id = ? ORDER BY identifier",
                (rs, i) -> {
                    String id = rs.getString(1);
                    ThingModelDefs.Property p = model.property(id);
                    return new LatestVO(id, p == null ? id : p.name(), p == null ? null : p.unit(),
                            p == null ? null : p.dataType(), Jsons.parse(rs.getString(2)),
                            rs.getTimestamp(3).toInstant());
                }, deviceId);
    }

    public PageResult<HistoryVO> history(Long deviceId, List<String> identifiers, String start, String end, Integer page,
            Integer size) {
        deviceService.require(deviceId);
        Instant[] range = range(start, end, MAX_HISTORY_SPAN);
        List<String> ids = identifiers(identifiers);
        int sz = com.omnihub.iot.common.PageQuery.size(size);
        long pg = com.omnihub.iot.common.PageQuery.page(page);
        HistoryStore.Page r = history.page(deviceId, ids, range[0], range[1], sz, (int) ((pg - 1) * sz));
        return PageResult.of(r.rows().stream().map(x -> new HistoryVO(x.identifier(), x.value(), x.ts())).toList(),
                r.total(), pg, sz);
    }

    public TrendVO trend(Long deviceId, List<String> identifiers, String start, String end, String interval) {
        Device d = deviceService.require(deviceId);
        Instant[] range = range(start, end, MAX_TREND_SPAN);
        List<String> ids = identifiers(identifiers);
        Content model = thingModels.current(d.getProductId()).content();
        long spanSec = Math.max(1, Duration.between(range[0], range[1]).toSeconds());
        String iv = interval == null ? "RAW" : interval.toUpperCase();
        long bucket = switch (iv) {
            case "1M" -> 60;
            case "5M" -> 300;
            case "1H" -> 3600;
            case "1D" -> 86400;
            case "RAW" -> 0;
            default -> throw BizException.badRequest("interval 只能为 RAW/1m/5m/1h/1d");
        };
        if (bucket > 0 && spanSec / bucket > MAX_POINTS) {
            bucket = (long) Math.ceil((double) spanSec / MAX_POINTS);
        }
        List<SeriesVO> series = new ArrayList<>();
        for (String id : ids) {
            ThingModelDefs.Property p = model.property(id);
            List<List<Object>> points = history.series(deviceId, id, range[0], range[1], bucket, MAX_POINTS);
            series.add(new SeriesVO(id, p == null ? id : p.name(), p == null ? null : p.unit(), points));
        }
        return new TrendVO(bucket == 0 ? "RAW" : bucket + "s", series);
    }

    public PageResult<EventVO> events(Long deviceId, Integer page, Integer size, String level) {
        deviceService.require(deviceId);
        int sz = com.omnihub.iot.common.PageQuery.size(size);
        long pg = com.omnihub.iot.common.PageQuery.page(page);
        boolean byLevel = level != null && !level.isBlank();
        String where = " WHERE device_id = ?" + (byLevel ? " AND level = ?" : "");
        Object[] args = byLevel ? new Object[] { deviceId, level } : new Object[] { deviceId };
        Long total = jdbc.queryForObject("SELECT count(*) FROM iot_device_event" + where, Long.class, args);
        List<Object> pa = new ArrayList<>(List.of(args));
        pa.add(sz);
        pa.add((pg - 1) * sz);
        List<EventVO> rows = jdbc.query("SELECT id, identifier, name, level, params::text, ts FROM iot_device_event" + where
                + " ORDER BY ts DESC, id DESC LIMIT ? OFFSET ?",
                (rs, i) -> new EventVO(rs.getLong(1), rs.getString(2), rs.getString(3), rs.getString(4),
                        Jsons.parse(rs.getString(5)), rs.getTimestamp(6).toInstant()),
                pa.toArray());
        return PageResult.of(rows, total == null ? 0 : total, pg, sz);
    }

    public PageResult<MessageVO> messages(Long deviceId, Integer page, Integer size, String direction) {
        deviceService.require(deviceId);
        int sz = com.omnihub.iot.common.PageQuery.size(size);
        long pg = com.omnihub.iot.common.PageQuery.page(page);
        boolean byDir = direction != null && !direction.isBlank();
        String where = " WHERE device_id = ?" + (byDir ? " AND direction = ?" : "");
        Object[] args = byDir ? new Object[] { deviceId, direction } : new Object[] { deviceId };
        Long total = jdbc.queryForObject("SELECT count(*) FROM iot_device_message_log" + where, Long.class, args);
        List<Object> pa = new ArrayList<>(List.of(args));
        pa.add(sz);
        pa.add((pg - 1) * sz);
        List<MessageVO> rows = jdbc.query(
                "SELECT id, direction, type, topic, payload, result, message, created_at FROM iot_device_message_log"
                        + where + " ORDER BY id DESC LIMIT ? OFFSET ?",
                (rs, i) -> new MessageVO(rs.getLong(1), rs.getString(2), rs.getString(3), rs.getString(4),
                        rs.getString(5), rs.getString(6), rs.getString(7), rs.getTimestamp(8).toInstant()),
                pa.toArray());
        return PageResult.of(rows, total == null ? 0 : total, pg, sz);
    }

    // ------------------------------------------------------------------ retention

    /** Retention policy: history and message log older than the configured number of days are deleted. */
    @Scheduled(cron = "0 17 3 * * *")
    public void purgeExpired() {
        try {
            int histDays = config.getInt("retention.history.days", 90);
            int msgDays = config.getInt("retention.message-log.days", 7);
            history.purgeOlderThan(histDays);
            int m = jdbc.update("DELETE FROM iot_device_message_log WHERE created_at < now() - make_interval(days => ?)",
                    msgDays);
            int e = jdbc.update("DELETE FROM iot_device_event WHERE ts < now() - make_interval(days => ?)", histDays);
            log.info("Retention purge removed {} message logs and {} events (history store: {})", m, e,
                    history.getClass().getSimpleName());
        } catch (Exception ex) {
            log.warn("Retention purge failed: {}", ex.getMessage());
        }
    }

    // ------------------------------------------------------------------ helpers

    private static Instant[] range(String start, String end, Duration maxSpan) {
        Instant e = end == null || end.isBlank() ? Instant.now().plusSeconds(1) : parse(end);
        Instant s = start == null || start.isBlank() ? e.minus(Duration.ofHours(1)) : parse(start);
        if (!s.isBefore(e)) {
            throw BizException.badRequest("开始时间必须早于结束时间");
        }
        if (Duration.between(s, e).compareTo(maxSpan) > 0) {
            throw BizException.badRequest("查询时间跨度不能超过 " + maxSpan.toDays() + " 天");
        }
        return new Instant[] { s, e };
    }

    private static Instant parse(String s) {
        try {
            return OffsetDateTime.parse(s).toInstant();
        } catch (DateTimeParseException ex) {
            throw BizException.badRequest("时间格式错误，需为 ISO-8601，例如 2026-01-01T08:00:00Z");
        }
    }

    private static List<String> identifiers(List<String> ids) {
        if (ids == null || ids.isEmpty()) {
            throw BizException.badRequest("identifiers 不能为空");
        }
        if (ids.size() > MAX_IDENTIFIERS) {
            throw BizException.badRequest("一次最多查询 " + MAX_IDENTIFIERS + " 个属性");
        }
        for (String id : ids) {
            if (!IDENT.matcher(id).matches()) {
                throw BizException.badRequest("属性标识不合法: " + id);
            }
        }
        return ids;
    }

    private static String placeholders(int n) {
        return String.join(",", java.util.Collections.nCopies(n, "?"));
    }
}
