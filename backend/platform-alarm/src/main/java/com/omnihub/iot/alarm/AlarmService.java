package com.omnihub.iot.alarm;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.omnihub.iot.alarm.AlarmDtos.ActionVO;
import com.omnihub.iot.alarm.AlarmDtos.AlarmVO;
import com.omnihub.iot.common.BizException;
import com.omnihub.iot.common.PageQuery;
import com.omnihub.iot.common.PageResult;
import com.omnihub.iot.device.DeviceService;
import com.omnihub.iot.identity.core.CurrentUser;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AlarmService {

    private final AlarmRecordMapper mapper;
    private final DeviceService deviceService;
    private final AlarmEngine engine;
    private final JdbcTemplate jdbc;

    public AlarmService(AlarmRecordMapper mapper, DeviceService deviceService, AlarmEngine engine, JdbcTemplate jdbc) {
        this.mapper = mapper;
        this.deviceService = deviceService;
        this.engine = engine;
        this.jdbc = jdbc;
    }

    public PageResult<AlarmVO> page(Integer page, Integer size, String status, Boolean acknowledged, String level,
            Long deviceId, String start, String end) {
        LambdaQueryWrapper<AlarmRecord> q = new LambdaQueryWrapper<AlarmRecord>()
                .eq(AlarmRecord::getTenantId, CurrentUser.tenantId()).orderByDesc(AlarmRecord::getId);
        if (status != null && !status.isBlank()) {
            q.eq(AlarmRecord::getStatus, status);
        }
        if (acknowledged != null) {
            q.eq(AlarmRecord::getAcknowledged, acknowledged);
        }
        if (level != null && !level.isBlank()) {
            q.eq(AlarmRecord::getLevel, level);
        }
        if (deviceId != null) {
            q.eq(AlarmRecord::getDeviceId, deviceId);
        }
        if (start != null && !start.isBlank()) {
            q.ge(AlarmRecord::getFirstTriggeredAt, parse(start));
        }
        if (end != null && !end.isBlank()) {
            q.lt(AlarmRecord::getFirstTriggeredAt, parse(end));
        }
        Page<AlarmRecord> p = mapper.selectPage(PageQuery.of(page, size), q);
        Set<Long> dids = p.getRecords().stream().map(AlarmRecord::getDeviceId).collect(Collectors.toSet());
        Map<Long, String> names = dids.isEmpty() ? Map.of() : deviceService.names(dids);
        return PageResult.of(p, r -> AlarmEngine.toVO(r, names.get(r.getDeviceId())));
    }

    @Transactional
    public AlarmVO ack(Long id, String remark) {
        AlarmRecord r = require(id);
        if (!Boolean.TRUE.equals(r.getAcknowledged())) {
            r.setAcknowledged(true);
            r.setAcknowledgedBy(CurrentUser.username());
            r.setAcknowledgedAt(Instant.now());
            // event alarms have no automatic recovery condition: handling them by hand closes them
            if ("EVENT".equals(r.getRuleType()) && "ACTIVE".equals(r.getStatus())) {
                r.setStatus("RECOVERED");
                r.setRecoveredAt(Instant.now());
            }
            mapper.updateById(r);
            engine.action(id, "ACK", CurrentUser.username(), remark);
            engine.publish(r);
        }
        return view(r);
    }

    public AlarmVO comment(Long id, String remark) {
        AlarmRecord r = require(id);
        engine.action(id, "COMMENT", CurrentUser.username(), remark);
        return view(r);
    }

    public List<ActionVO> actions(Long id) {
        require(id);
        return jdbc.query("SELECT id, action, operator, remark, created_at FROM alarm_action WHERE alarm_id = ? ORDER BY id",
                (rs, i) -> new ActionVO(rs.getLong(1), rs.getString(2), rs.getString(3), rs.getString(4),
                        rs.getTimestamp(5).toInstant()),
                id);
    }

    private AlarmRecord require(Long id) {
        AlarmRecord r = mapper.selectById(id);
        if (r == null || !r.getTenantId().equals(CurrentUser.tenantId())) {
            throw BizException.notFound("告警不存在");
        }
        return r;
    }

    private AlarmVO view(AlarmRecord r) {
        return AlarmEngine.toVO(r, deviceService.names(Set.of(r.getDeviceId())).get(r.getDeviceId()));
    }

    private static Instant parse(String s) {
        try {
            return OffsetDateTime.parse(s).toInstant();
        } catch (DateTimeParseException e) {
            throw BizException.badRequest("时间格式错误，需为 ISO-8601");
        }
    }
}
