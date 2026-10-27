package com.omnihub.iot.alarm;

import com.omnihub.iot.alarm.AlarmDtos.ActionVO;
import com.omnihub.iot.alarm.AlarmDtos.AlarmVO;
import com.omnihub.iot.alarm.AlarmDtos.EnabledRequest;
import com.omnihub.iot.alarm.AlarmDtos.RemarkRequest;
import com.omnihub.iot.alarm.AlarmDtos.RuleRequest;
import com.omnihub.iot.alarm.AlarmDtos.RuleVO;
import com.omnihub.iot.common.ApiResponse;
import com.omnihub.iot.common.PageResult;
import com.omnihub.iot.system.OpLog;
import jakarta.validation.Valid;
import java.util.List;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api")
public class AlarmController {

    private final AlarmRuleService ruleService;
    private final AlarmService alarmService;

    public AlarmController(AlarmRuleService ruleService, AlarmService alarmService) {
        this.ruleService = ruleService;
        this.alarmService = alarmService;
    }

    @GetMapping("/alarm-rules")
    @PreAuthorize("hasAuthority('alarm:view')")
    public ApiResponse<PageResult<RuleVO>> rules(@RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer size, @RequestParam(required = false) String keyword,
            @RequestParam(required = false) String ruleType, @RequestParam(required = false) Boolean enabled) {
        return ApiResponse.ok(ruleService.page(page, size, keyword, ruleType, enabled));
    }

    @PostMapping("/alarm-rules")
    @PreAuthorize("hasAuthority('alarm:rule')")
    @OpLog(module = "告警", action = "新增告警规则")
    public ApiResponse<RuleVO> createRule(@Valid @RequestBody RuleRequest req) {
        return ApiResponse.ok(ruleService.create(req));
    }

    @PutMapping("/alarm-rules/{id}")
    @PreAuthorize("hasAuthority('alarm:rule')")
    @OpLog(module = "告警", action = "编辑告警规则")
    public ApiResponse<RuleVO> updateRule(@PathVariable Long id, @Valid @RequestBody RuleRequest req) {
        return ApiResponse.ok(ruleService.update(id, req));
    }

    @DeleteMapping("/alarm-rules/{id}")
    @PreAuthorize("hasAuthority('alarm:rule')")
    @OpLog(module = "告警", action = "删除告警规则")
    public ApiResponse<Void> deleteRule(@PathVariable Long id) {
        ruleService.delete(id);
        return ApiResponse.ok();
    }

    @PutMapping("/alarm-rules/{id}/enabled")
    @PreAuthorize("hasAuthority('alarm:rule')")
    @OpLog(module = "告警", action = "启用/停用告警规则")
    public ApiResponse<RuleVO> enableRule(@PathVariable Long id, @Valid @RequestBody EnabledRequest req) {
        return ApiResponse.ok(ruleService.setEnabled(id, req.enabled()));
    }

    @GetMapping("/alarms")
    @PreAuthorize("hasAuthority('alarm:view')")
    public ApiResponse<PageResult<AlarmVO>> alarms(@RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer size, @RequestParam(required = false) String status,
            @RequestParam(required = false) Boolean acknowledged, @RequestParam(required = false) String level,
            @RequestParam(required = false) Long deviceId, @RequestParam(required = false) String start,
            @RequestParam(required = false) String end) {
        return ApiResponse.ok(alarmService.page(page, size, status, acknowledged, level, deviceId, start, end));
    }

    @PostMapping("/alarms/{id}/ack")
    @PreAuthorize("hasAuthority('alarm:ack')")
    @OpLog(module = "告警", action = "确认告警")
    public ApiResponse<AlarmVO> ack(@PathVariable Long id, @Valid @RequestBody RemarkRequest req) {
        return ApiResponse.ok(alarmService.ack(id, req.remark()));
    }

    @PostMapping("/alarms/{id}/comments")
    @PreAuthorize("hasAuthority('alarm:ack')")
    @OpLog(module = "告警", action = "告警处理备注")
    public ApiResponse<AlarmVO> comment(@PathVariable Long id, @Valid @RequestBody RemarkRequest req) {
        return ApiResponse.ok(alarmService.comment(id, req.remark()));
    }

    @GetMapping("/alarms/{id}/actions")
    @PreAuthorize("hasAuthority('alarm:view')")
    public ApiResponse<List<ActionVO>> actions(@PathVariable Long id) {
        return ApiResponse.ok(alarmService.actions(id));
    }
}
