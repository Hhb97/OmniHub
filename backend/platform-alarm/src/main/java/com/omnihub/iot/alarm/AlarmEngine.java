package com.omnihub.iot.alarm;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.omnihub.iot.alarm.AlarmDtos.AlarmVO;
import com.omnihub.iot.config.IngestDb;
import com.omnihub.iot.device.Device;
import com.omnihub.iot.device.DeviceMapper;
import com.omnihub.iot.device.DeviceStatusEvent;
import com.omnihub.iot.telemetry.DeviceEventReported;
import com.omnihub.iot.telemetry.PropertyReportedEvent;
import com.omnihub.iot.thingmodel.ValueValidator;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import java.sql.PreparedStatement;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.event.EventListener;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Evaluates alarm rules against what the platform really received: property reports, device events and online /
 * offline transitions.
 *
 * Everything runs on one dedicated worker thread, never on the MQTT lanes: alarm evaluation used to issue an
 * UPDATE per incoming message (and a rule query per device every 10 s), so a device that stayed above a threshold
 * made the ingestion path wait for the database on every report. Ingest now only hands over an event when the
 * device actually has rules.
 *
 * - rules are kept in memory (indexed by device and product) and reloaded every
 *   {@code iot.alarm.rule-refresh-seconds} or right after a rule change
 * - "consecutive violations" counters are in memory (they restart with the process)
 * - trigger_count / last_triggered_at of an ACTIVE alarm are accumulated in memory and written in one statement per
 *   second ({@code iot.alarm.flush-ms}), and recovery issues no SQL at all when this instance knows of no ACTIVE alarm
 * - duplicate suppression: a unique partial index allows only one ACTIVE alarm per (rule, device). A new alarm is
 *   created only after the previous one RECOVERED (and, if the rule has a silence period, after it elapsed)
 *
 * When the queue is full (evaluation slower than ingest) evaluations are dropped and counted instead of blocking
 * the MQTT threads; the metric {@code iot_alarm_eval_dropped_total} shows it.
 */
@Component
public class AlarmEngine {

    private static final Logger log = LoggerFactory.getLogger(AlarmEngine.class);
    private static final int CHUNK = 1000;

    /** In-memory rule lookup; replaced as a whole on reload. */
    private record RuleIndex(Map<Long, List<AlarmRule>> byDevice, Map<Long, List<AlarmRule>> byProduct) {

        static RuleIndex of(List<AlarmRule> all) {
            Map<Long, List<AlarmRule>> byDevice = new HashMap<>();
            Map<Long, List<AlarmRule>> byProduct = new HashMap<>();
            for (AlarmRule r : all) {
                if (!Boolean.TRUE.equals(r.getEnabled())) {
                    continue;
                }
                if ("DEVICE".equals(r.getScope()) && r.getDeviceId() != null) {
                    byDevice.computeIfAbsent(r.getDeviceId(), k -> new ArrayList<>(2)).add(r);
                } else if ("PRODUCT".equals(r.getScope()) && r.getProductId() != null) {
                    byProduct.computeIfAbsent(r.getProductId(), k -> new ArrayList<>(2)).add(r);
                }
            }
            return new RuleIndex(byDevice, byProduct);
        }

        /** Empty for the vast majority of deployments: no rules means no work and no allocation on the ingest path. */
        List<AlarmRule> forDevice(Long deviceId, Long productId) {
            List<AlarmRule> d = deviceId == null ? null : byDevice.get(deviceId);
            List<AlarmRule> p = productId == null ? null : byProduct.get(productId);
            if (d == null) {
                return p == null ? List.of() : p;
            }
            if (p == null) {
                return d;
            }
            List<AlarmRule> both = new ArrayList<>(d.size() + p.size());
            both.addAll(d);
            both.addAll(p);
            return both;
        }
    }

    /** An ACTIVE alarm this instance knows about; fields are only touched by the worker thread. */
    private static final class AlarmState {
        final String key;
        final Long alarmId;
        long pending;
        String value;
        Instant lastTriggeredAt;

        AlarmState(String key, Long alarmId) {
            this.key = key;
            this.alarmId = alarmId;
            this.lastTriggeredAt = Instant.now();
        }
    }

    private record Recovered(Long id, Long deviceId, Long ruleId) {
    }

    /** Primary pool: rows written from the API (alarm actions, rule-wide recovery) stay in the caller's transaction. */
    private final JdbcTemplate jdbc;
    /** Ingestion pool: the statements of the evaluation worker never compete with HTTP requests for a connection. */
    private final JdbcTemplate writeJdbc;
    private final AlarmRuleMapper ruleMapper;
    private final AlarmRecordMapper recordMapper;
    private final DeviceMapper deviceMapper;
    private final ApplicationEventPublisher events;

    private final BlockingQueue<Runnable> tasks;
    private final Thread worker;
    private final long flushMs;
    private final AtomicLong dropped = new AtomicLong();
    private volatile boolean running = true;

    private volatile RuleIndex rules = new RuleIndex(Map.of(), Map.of());
    private final Map<String, AlarmState> active = new ConcurrentHashMap<>();
    private final Map<String, Integer> consecutive = new ConcurrentHashMap<>();

    public AlarmEngine(JdbcTemplate jdbc, IngestDb ingestDb, AlarmRuleMapper ruleMapper, AlarmRecordMapper recordMapper,
            DeviceMapper deviceMapper, ApplicationEventPublisher events, AlarmProperties props, MeterRegistry meters) {
        this.jdbc = jdbc;
        this.writeJdbc = ingestDb.jdbc();
        this.ruleMapper = ruleMapper;
        this.recordMapper = recordMapper;
        this.deviceMapper = deviceMapper;
        this.events = events;
        this.flushMs = Math.max(1, props.getFlushMs());
        this.tasks = new ArrayBlockingQueue<>(Math.max(1, props.getQueueCapacity()));
        this.worker = new Thread(this::loop, "alarm-eval");
        this.worker.setDaemon(true);
        Gauge.builder("iot_alarm_eval_queue_pending", tasks, BlockingQueue::size)
                .description("Alarm evaluations waiting for the worker").register(meters);
        this.droppedCounter = Counter.builder("iot_alarm_eval_dropped_total")
                .description("Alarm evaluations dropped because the worker could not keep up")
                .register(meters);
    }

    private final Counter droppedCounter;

    @PostConstruct
    void start() {
        reloadRules();
        loadActiveAlarms();
        worker.start();
    }

    @PreDestroy
    void stop() {
        running = false;
        worker.interrupt();
        try {
            worker.join(2000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        flushTriggerCounts();
    }

    // ------------------------------------------------------------------ inputs

    @EventListener
    public void onProperty(PropertyReportedEvent e) {
        // hot path: one or two map lookups, no allocation and no database when the device has no rules
        if (rules.forDevice(e.deviceId(), e.productId()).isEmpty()) {
            return;
        }
        offer(() -> evaluateProperty(e));
    }

    @EventListener
    public void onStatus(DeviceStatusEvent e) {
        // status messages are rare (connect / disconnect); the product id is only needed for product rules
        offer(() -> evaluateStatus(e));
    }

    @EventListener
    public void onEvent(DeviceEventReported e) {
        if (rules.forDevice(e.deviceId(), e.productId()).isEmpty()) {
            return;
        }
        offer(() -> evaluateEvent(e));
    }

    // ------------------------------------------------------------------ worker

    private void offer(Runnable task) {
        if (!tasks.offer(task)) {
            long n = dropped.incrementAndGet();
            droppedCounter.increment();
            if (n % 1000 == 1) {
                log.warn("Alarm evaluation queue is full: {} evaluations dropped so far", n);
            }
        }
    }

    private void loop() {
        long nextFlush = System.currentTimeMillis() + flushMs;
        while (running) {
            try {
                long wait = Math.max(1, nextFlush - System.currentTimeMillis());
                Runnable task = tasks.poll(wait, TimeUnit.MILLISECONDS);
                if (task != null) {
                    try {
                        task.run();
                    } catch (Exception e) {
                        // one bad rule/report must never kill the worker
                        log.error("Alarm evaluation failed: {}", e.toString(), e);
                    }
                }
                if (System.currentTimeMillis() >= nextFlush) {
                    flushTriggerCounts();
                    nextFlush = System.currentTimeMillis() + flushMs;
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
    }

    private void evaluateProperty(PropertyReportedEvent e) {
        for (AlarmRule r : rules.forDevice(e.deviceId(), e.productId())) {
            if (!"THRESHOLD".equals(r.getRuleType())) {
                continue;
            }
            JsonNode v = e.values().get(r.getIdentifier());
            Double num = v == null ? null : ValueValidator.numeric(v);
            if (num == null) {
                continue;
            }
            String key = key(r.getId(), e.deviceId());
            if (compare(num, r.getOperator(), r.getThreshold())) {
                int n = consecutive.merge(key, 1, Integer::sum);
                if (n >= triggerCount(r)) {
                    raise(r, e.deviceId(), e.tenantId(), r.getIdentifier() + " = " + fmt(num) + "，"
                            + opText(r.getOperator()) + " 阈值 " + fmt(r.getThreshold()), fmt(num));
                }
            } else {
                consecutive.remove(key);
                recoverActive(r.getId(), e.deviceId(), r.getIdentifier() + " = " + fmt(num) + "，已恢复正常");
            }
        }
    }

    private void evaluateStatus(DeviceStatusEvent e) {
        Device d = deviceMapper.selectById(e.deviceId());
        if (d == null) {
            return;
        }
        for (AlarmRule r : rules.forDevice(d.getId(), d.getProductId())) {
            if (!"OFFLINE".equals(r.getRuleType())) {
                continue;
            }
            if ("OFFLINE".equals(e.onlineStatus())) {
                raise(r, d.getId(), d.getTenantId(), "设备离线", "OFFLINE");
            } else if ("ONLINE".equals(e.onlineStatus())) {
                recoverActive(r.getId(), d.getId(), "设备已恢复在线");
            }
        }
    }

    private void evaluateEvent(DeviceEventReported e) {
        for (AlarmRule r : rules.forDevice(e.deviceId(), e.productId())) {
            if ("EVENT".equals(r.getRuleType()) && e.identifier().equals(r.getIdentifier())) {
                raise(r, e.deviceId(), e.tenantId(), "设备上报事件 " + e.identifier()
                        + (e.name() == null ? "" : "（" + e.name() + "）"), e.identifier());
            }
        }
    }

    // ------------------------------------------------------------------ state changes

    private void raise(AlarmRule r, Long deviceId, Long tenantId, String message, String value) {
        String key = key(r.getId(), deviceId);
        AlarmState state = active.get(key);
        if (state != null) {
            // already ACTIVE: accumulate, the counter is written once per flush instead of once per message
            state.pending++;
            state.value = value;
            state.lastTriggeredAt = Instant.now();
            return;
        }
        if (r.getSilenceSeconds() != null && r.getSilenceSeconds() > 0) {
            Integer recent = writeJdbc.queryForObject("""
                    SELECT count(*) FROM alarm_record WHERE rule_id = ? AND device_id = ? AND status = 'RECOVERED'
                    AND recovered_at > ?""", Integer.class, r.getId(), deviceId,
                    Timestamp.from(Instant.now().minusSeconds(r.getSilenceSeconds())));
            if (recent != null && recent > 0) {
                return;
            }
        }
        Instant now = Instant.now();
        AlarmRecord rec = new AlarmRecord();
        rec.setTenantId(tenantId);
        rec.setRuleId(r.getId());
        rec.setDeviceId(deviceId);
        rec.setRuleName(r.getName());
        rec.setRuleType(r.getRuleType());
        rec.setLevel(r.getLevel());
        rec.setStatus("ACTIVE");
        rec.setAcknowledged(false);
        rec.setMessage(message);
        rec.setTriggerValue(value);
        rec.setTriggerCount(1);
        rec.setFirstTriggeredAt(now);
        rec.setLastTriggeredAt(now);
        try {
            recordMapper.insert(rec);
        } catch (DataIntegrityViolationException race) {
            // another instance (or a stale in-memory view) already created the ACTIVE alarm: count this occurrence
            List<Long> ids = writeJdbc.queryForList(
                    "SELECT id FROM alarm_record WHERE rule_id = ? AND device_id = ? AND status = 'ACTIVE'",
                    Long.class, r.getId(), deviceId);
            if (!ids.isEmpty()) {
                AlarmState attached = new AlarmState(key, ids.get(0));
                attached.pending = 1;
                attached.value = value;
                active.put(key, attached);
            }
            return;
        }
        action(rec.getId(), "TRIGGER", "system", message);
        publish(rec);
        active.put(key, new AlarmState(key, rec.getId()));
        log.info("Alarm {} raised: rule '{}' device {} ({})", rec.getId(), r.getName(), deviceId, message);
    }

    /** Closes the ACTIVE alarm of this rule+device; no database access when this instance knows of none. */
    private void recoverActive(Long ruleId, Long deviceId, String remark) {
        AlarmState state = active.remove(key(ruleId, deviceId));
        if (state == null) {
            return;
        }
        List<Long> ids = writeJdbc.query("""
                UPDATE alarm_record SET status = 'RECOVERED', recovered_at = now()
                WHERE id = ? AND status = 'ACTIVE' RETURNING id""", (rs, i) -> rs.getLong(1), state.alarmId);
        finishRecovery(ids, remark);
    }

    /** Closes every ACTIVE alarm of a rule (rule disabled / deleted). Called from the rule management API. */
    public void recoverRule(Long ruleId, String remark) {
        List<Recovered> rows = jdbc.query("""
                UPDATE alarm_record SET status = 'RECOVERED', recovered_at = now()
                WHERE rule_id = ? AND status = 'ACTIVE' RETURNING id, device_id, rule_id""",
                (rs, i) -> new Recovered(rs.getLong(1), rs.getLong(2), rs.getLong(3)), ruleId);
        for (Recovered row : rows) {
            active.remove(key(row.ruleId(), row.deviceId()));
            action(row.id(), "RECOVER", "system", remark);
            AlarmRecord rec = recordMapper.selectById(row.id());
            if (rec != null) {
                publish(rec);
                log.info("Alarm {} recovered: {}", row.id(), remark);
            }
        }
    }

    private void finishRecovery(List<Long> ids, String remark) {
        for (Long id : ids) {
            action(id, "RECOVER", "system", remark);
            AlarmRecord rec = recordMapper.selectById(id);
            if (rec != null) {
                publish(rec);
                log.info("Alarm {} recovered: {}", id, remark);
            }
        }
    }

    /** Writes the accumulated trigger counters (one statement per chunk instead of one per message). */
    private void flushTriggerCounts() {
        List<AlarmState> states = new ArrayList<>();
        for (AlarmState s : active.values()) {
            if (s.pending > 0) {
                states.add(s);
            }
        }
        if (states.isEmpty()) {
            return;
        }
        for (int from = 0; from < states.size(); from += CHUNK) {
            List<AlarmState> part = states.subList(from, Math.min(states.size(), from + CHUNK));
            int n = part.size();
            Long[] id = new Long[n];
            Long[] count = new Long[n];
            Timestamp[] ts = new Timestamp[n];
            String[] value = new String[n];
            long[] snapshot = new long[n];
            for (int i = 0; i < n; i++) {
                AlarmState s = part.get(i);
                snapshot[i] = s.pending;
                id[i] = s.alarmId;
                count[i] = s.pending;
                ts[i] = Timestamp.from(s.lastTriggeredAt == null ? Instant.now() : s.lastTriggeredAt);
                value[i] = s.value;
            }
            try {
                writeJdbc.execute((ConnectionCallback<Void>) con -> {
                    try (PreparedStatement ps = con.prepareStatement("""
                            UPDATE alarm_record a
                            SET trigger_count = a.trigger_count + x.c, last_triggered_at = x.t, trigger_value = x.v
                            FROM unnest(?::bigint[], ?::bigint[], ?::timestamptz[], ?::varchar[]) AS x(id, c, t, v)
                            WHERE a.id = x.id AND a.status = 'ACTIVE'""")) {
                        ps.setArray(1, con.createArrayOf("bigint", id));
                        ps.setArray(2, con.createArrayOf("bigint", count));
                        ps.setArray(3, con.createArrayOf("timestamptz", ts));
                        ps.setArray(4, con.createArrayOf("varchar", value));
                        ps.executeUpdate();
                    }
                    return null;
                });
            } catch (Exception e) {
                // keep the counters for the next flush; an alarm counter is not worth retrying in a loop
                log.warn("Writing alarm trigger counters failed (will retry): {}", e.getMessage());
                continue;
            }
            for (int i = 0; i < n; i++) {
                AlarmState s = part.get(i);
                if (active.get(s.key) == s) {
                    s.pending -= snapshot[i];
                }
            }
        }
    }

    void action(Long alarmId, String action, String operator, String remark) {
        jdbc.update("INSERT INTO alarm_action (alarm_id, action, operator, remark) VALUES (?, ?, ?, ?)", alarmId,
                action, operator, remark);
    }

    void publish(AlarmRecord rec) {
        if ("RECOVERED".equals(rec.getStatus())) {
            active.remove(key(rec.getRuleId(), rec.getDeviceId()));
        }
        Device d = deviceMapper.selectById(rec.getDeviceId());
        events.publishEvent(new AlarmChangedEvent(rec.getTenantId(), toVO(rec, d == null ? null : d.getName())));
    }

    static AlarmVO toVO(AlarmRecord r, String deviceName) {
        return new AlarmVO(r.getId(), r.getRuleId(), r.getRuleName(), r.getDeviceId(), deviceName, r.getRuleType(),
                r.getLevel(), r.getStatus(), r.getAcknowledged(), r.getMessage(), r.getTriggerValue(),
                r.getTriggerCount(), r.getFirstTriggeredAt(), r.getLastTriggeredAt(), r.getRecoveredAt(),
                r.getAcknowledgedBy(), r.getAcknowledgedAt());
    }

    // ------------------------------------------------------------------ rule cache

    /** Called after a rule was created, changed or deleted; reloads after the surrounding transaction commits. */
    public void invalidateRules() {
        consecutive.clear();
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    reloadRules();
                }
            });
        } else {
            reloadRules();
        }
    }

    @Scheduled(fixedDelayString = "#{${iot.alarm.rule-refresh-seconds:30} * 1000}")
    public synchronized void reloadRules() {
        try {
            List<AlarmRule> all = ruleMapper.selectList(
                    new LambdaQueryWrapper<AlarmRule>().eq(AlarmRule::getEnabled, true));
            rules = RuleIndex.of(all);
        } catch (Exception e) {
            log.warn("Reloading alarm rules failed, keeping the previous set: {}", e.getMessage());
        }
    }

    /** Loads the ACTIVE alarms at startup so that recovery needs no SQL for the common "nothing active" case. */
    private void loadActiveAlarms() {
        try {
            List<AlarmState> rows = writeJdbc.query(
                    "SELECT id, rule_id, device_id FROM alarm_record WHERE status = 'ACTIVE'",
                    (rs, i) -> new AlarmState(key(rs.getLong(2), rs.getLong(3)), rs.getLong(1)));
            active.clear();
            for (AlarmState s : rows) {
                active.put(s.key, s);
            }
            if (!rows.isEmpty()) {
                log.info("Loaded {} ACTIVE alarms into the evaluation state", rows.size());
            }
        } catch (Exception e) {
            log.warn("Loading ACTIVE alarms failed: {}", e.getMessage());
        }
    }

    // ------------------------------------------------------------------ helpers

    private static String key(Long ruleId, Long deviceId) {
        return ruleId + ":" + deviceId;
    }

    private static int triggerCount(AlarmRule r) {
        return r.getTriggerCount() == null || r.getTriggerCount() < 1 ? 1 : r.getTriggerCount();
    }

    private static boolean compare(double v, String op, Double threshold) {
        if (threshold == null || op == null) {
            return false;
        }
        double t = threshold;
        return switch (op) {
            case "GT" -> v > t;
            case "GTE" -> v >= t;
            case "LT" -> v < t;
            case "LTE" -> v <= t;
            case "EQ" -> v == t;
            case "NE" -> v != t;
            default -> false;
        };
    }

    private static String opText(String op) {
        return switch (op) {
            case "GT" -> "大于";
            case "GTE" -> "大于等于";
            case "LT" -> "小于";
            case "LTE" -> "小于等于";
            case "EQ" -> "等于";
            default -> "不等于";
        };
    }

    private static String fmt(double d) {
        return d == Math.rint(d) ? String.valueOf((long) d) : String.valueOf(d);
    }
}
