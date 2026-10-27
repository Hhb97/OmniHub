package com.omnihub.iot.alarm;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.omnihub.iot.alarm.AlarmDtos.AlarmVO;
import com.omnihub.iot.device.Device;
import com.omnihub.iot.device.DeviceMapper;
import com.omnihub.iot.device.DeviceStatusEvent;
import com.omnihub.iot.telemetry.DeviceEventReported;
import com.omnihub.iot.telemetry.PropertyReportedEvent;
import com.omnihub.iot.thingmodel.ValueValidator;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.event.EventListener;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Evaluates alarm rules against what the platform really received: property reports, device events and online /
 * offline transitions.
 *
 * Duplicate suppression: a unique partial index allows only one ACTIVE alarm per (rule, device). While it is
 * ACTIVE, further violations only bump last_triggered_at / trigger_count. A new alarm is created only after the
 * previous one RECOVERED (and, if the rule has a silence period, after that period elapsed).
 * Threshold rules need `triggerCount` consecutive violations (counter is in memory: it restarts after a restart).
 */
@Component
public class AlarmEngine {

    private static final Logger log = LoggerFactory.getLogger(AlarmEngine.class);
    private static final long RULE_CACHE_MS = 10_000;

    private record CachedRules(List<AlarmRule> rules, long at) {
    }

    private final JdbcTemplate jdbc;
    private final AlarmRuleMapper ruleMapper;
    private final AlarmRecordMapper recordMapper;
    private final DeviceMapper deviceMapper;
    private final ApplicationEventPublisher events;
    private final Map<Long, CachedRules> ruleCache = new ConcurrentHashMap<>();
    private final Map<String, Integer> consecutive = new ConcurrentHashMap<>();

    public AlarmEngine(JdbcTemplate jdbc, AlarmRuleMapper ruleMapper, AlarmRecordMapper recordMapper,
            DeviceMapper deviceMapper, ApplicationEventPublisher events) {
        this.jdbc = jdbc;
        this.ruleMapper = ruleMapper;
        this.recordMapper = recordMapper;
        this.deviceMapper = deviceMapper;
        this.events = events;
    }

    public void invalidateRules() {
        ruleCache.clear();
        consecutive.clear();
    }

    // ------------------------------------------------------------------ inputs

    @EventListener
    public void onProperty(PropertyReportedEvent e) {
        safely("property", () -> {
            for (AlarmRule r : rulesFor(e.deviceId(), e.productId())) {
                if (!"THRESHOLD".equals(r.getRuleType())) {
                    continue;
                }
                JsonNode v = e.values().get(r.getIdentifier());
                Double num = v == null ? null : ValueValidator.numeric(v);
                if (num == null) {
                    continue;
                }
                String key = r.getId() + ":" + e.deviceId();
                if (compare(num, r.getOperator(), r.getThreshold())) {
                    int n = consecutive.merge(key, 1, Integer::sum);
                    if (n >= r.getTriggerCount()) {
                        trigger(r, e.deviceId(), e.tenantId(), r.getIdentifier() + " = " + fmt(num) + "，"
                                + opText(r.getOperator()) + " 阈值 " + fmt(r.getThreshold()), fmt(num));
                    }
                } else {
                    consecutive.remove(key);
                    recover(r.getId(), e.deviceId(), r.getIdentifier() + " = " + fmt(num) + "，已恢复正常");
                }
            }
        });
    }

    @EventListener
    public void onStatus(DeviceStatusEvent e) {
        safely("status", () -> {
            Device d = deviceMapper.selectById(e.deviceId());
            if (d == null) {
                return;
            }
            for (AlarmRule r : rulesFor(d.getId(), d.getProductId())) {
                if (!"OFFLINE".equals(r.getRuleType())) {
                    continue;
                }
                if ("OFFLINE".equals(e.onlineStatus())) {
                    trigger(r, d.getId(), d.getTenantId(), "设备离线", "OFFLINE");
                } else if ("ONLINE".equals(e.onlineStatus())) {
                    recover(r.getId(), d.getId(), "设备已恢复在线");
                }
            }
        });
    }

    @EventListener
    public void onEvent(DeviceEventReported e) {
        safely("event", () -> {
            for (AlarmRule r : rulesFor(e.deviceId(), e.productId())) {
                if ("EVENT".equals(r.getRuleType()) && e.identifier().equals(r.getIdentifier())) {
                    trigger(r, e.deviceId(), e.tenantId(), "设备上报事件 " + e.identifier()
                            + (e.name() == null ? "" : "（" + e.name() + "）"), e.identifier());
                }
            }
        });
    }

    // ------------------------------------------------------------------ state changes

    private void trigger(AlarmRule r, Long deviceId, Long tenantId, String message, String value) {
        Timestamp now = Timestamp.from(Instant.now());
        int bumped = jdbc.update("""
                UPDATE alarm_record SET last_triggered_at = ?, trigger_count = trigger_count + 1, trigger_value = ?
                WHERE rule_id = ? AND device_id = ? AND status = 'ACTIVE'""", now, value, r.getId(), deviceId);
        if (bumped > 0) {
            return;
        }
        if (r.getSilenceSeconds() != null && r.getSilenceSeconds() > 0) {
            Integer recent = jdbc.queryForObject("""
                    SELECT count(*) FROM alarm_record WHERE rule_id = ? AND device_id = ? AND status = 'RECOVERED'
                    AND recovered_at > ?""", Integer.class, r.getId(), deviceId,
                    Timestamp.from(Instant.now().minusSeconds(r.getSilenceSeconds())));
            if (recent != null && recent > 0) {
                return;
            }
        }
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
        rec.setFirstTriggeredAt(now.toInstant());
        rec.setLastTriggeredAt(now.toInstant());
        try {
            recordMapper.insert(rec);
        } catch (DataIntegrityViolationException race) {
            // another thread created the ACTIVE alarm first: just count this occurrence
            jdbc.update("UPDATE alarm_record SET trigger_count = trigger_count + 1, last_triggered_at = ? "
                    + "WHERE rule_id = ? AND device_id = ? AND status = 'ACTIVE'", now, r.getId(), deviceId);
            return;
        }
        action(rec.getId(), "TRIGGER", "system", message);
        publish(rec);
        log.info("Alarm {} raised: rule '{}' device {} ({})", rec.getId(), r.getName(), deviceId, message);
    }

    public void recover(Long ruleId, Long deviceId, String remark) {
        List<Long> ids = jdbc.query("""
                UPDATE alarm_record SET status = 'RECOVERED', recovered_at = now()
                WHERE rule_id = ? AND device_id = ? AND status = 'ACTIVE' RETURNING id""",
                (rs, i) -> rs.getLong(1), ruleId, deviceId);
        finishRecovery(ids, remark);
    }

    /** Closes every ACTIVE alarm of a rule (rule disabled / deleted). */
    public void recoverRule(Long ruleId, String remark) {
        List<Long> ids = jdbc.query("""
                UPDATE alarm_record SET status = 'RECOVERED', recovered_at = now()
                WHERE rule_id = ? AND status = 'ACTIVE' RETURNING id""", (rs, i) -> rs.getLong(1), ruleId);
        finishRecovery(ids, remark);
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

    void action(Long alarmId, String action, String operator, String remark) {
        jdbc.update("INSERT INTO alarm_action (alarm_id, action, operator, remark) VALUES (?, ?, ?, ?)", alarmId,
                action, operator, remark);
    }

    void publish(AlarmRecord rec) {
        Device d = deviceMapper.selectById(rec.getDeviceId());
        events.publishEvent(new AlarmChangedEvent(rec.getTenantId(), toVO(rec, d == null ? null : d.getName())));
    }

    static AlarmVO toVO(AlarmRecord r, String deviceName) {
        return new AlarmVO(r.getId(), r.getRuleId(), r.getRuleName(), r.getDeviceId(), deviceName, r.getRuleType(),
                r.getLevel(), r.getStatus(), r.getAcknowledged(), r.getMessage(), r.getTriggerValue(),
                r.getTriggerCount(), r.getFirstTriggeredAt(), r.getLastTriggeredAt(), r.getRecoveredAt(),
                r.getAcknowledgedBy(), r.getAcknowledgedAt());
    }

    // ------------------------------------------------------------------ helpers

    private List<AlarmRule> rulesFor(Long deviceId, Long productId) {
        CachedRules c = ruleCache.get(deviceId);
        long now = System.currentTimeMillis();
        if (c != null && now - c.at() < RULE_CACHE_MS) {
            return c.rules();
        }
        List<AlarmRule> rules = ruleMapper.selectList(new LambdaQueryWrapper<AlarmRule>()
                .eq(AlarmRule::getEnabled, true).and(w -> w.eq(AlarmRule::getScope, "DEVICE")
                        .eq(AlarmRule::getDeviceId, deviceId).or(o -> o.eq(AlarmRule::getScope, "PRODUCT")
                                .eq(AlarmRule::getProductId, productId))));
        ruleCache.put(deviceId, new CachedRules(rules, now));
        return rules;
    }

    private static boolean compare(double v, String op, double t) {
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

    /** Alarm evaluation must never break ingestion. */
    private void safely(String what, Runnable r) {
        try {
            r.run();
        } catch (Exception e) {
            log.error("Alarm evaluation ({}) failed: {}", what, e.toString(), e);
        }
    }
}
