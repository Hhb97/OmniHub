package com.omnihub.iot.alarm;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.time.Instant;

public final class AlarmDtos {

    private AlarmDtos() {
    }

    public record RuleRequest(@NotBlank(message = "规则名称不能为空") @Size(max = 128) String name,
            @NotBlank(message = "范围不能为空") @Pattern(regexp = "^(PRODUCT|DEVICE)$", message = "scope 只能为 PRODUCT/DEVICE") String scope,
            Long productId, Long deviceId,
            @NotBlank(message = "规则类型不能为空") @Pattern(regexp = "^(THRESHOLD|OFFLINE|EVENT)$", message = "ruleType 只能为 THRESHOLD/OFFLINE/EVENT") String ruleType,
            @Size(max = 64) String identifier,
            @Pattern(regexp = "^(GT|GTE|LT|LTE|EQ|NE)$", message = "operator 不合法") String operator, Double threshold,
            @Min(value = 1, message = "连续触发次数至少为1") @Max(value = 1000, message = "连续触发次数过大") Integer triggerCount,
            @Min(0) @Max(86400) Integer silenceSeconds,
            @NotBlank(message = "级别不能为空") @Pattern(regexp = "^(INFO|WARNING|MAJOR|CRITICAL)$", message = "level 不合法") String level,
            Boolean enabled, @Size(max = 512) String description) {
    }

    public record EnabledRequest(@NotNull(message = "enabled 不能为空") Boolean enabled) {
    }

    public record RemarkRequest(@NotBlank(message = "备注不能为空") @Size(max = 512) String remark) {
    }

    public record RuleVO(Long id, String name, String scope, Long productId, Long deviceId, String productName,
            String deviceName, String ruleType, String identifier, String operator, Double threshold,
            Integer triggerCount, Integer silenceSeconds, String level, Boolean enabled, String description,
            Instant createdAt) {
    }

    public record AlarmVO(Long id, Long ruleId, String ruleName, Long deviceId, String deviceName, String ruleType,
            String level, String status, Boolean acknowledged, String message, String triggerValue,
            Integer triggerCount, Instant firstTriggeredAt, Instant lastTriggeredAt, Instant recoveredAt,
            String acknowledgedBy, Instant acknowledgedAt) {
    }

    public record ActionVO(Long id, String action, String operator, String remark, Instant createdAt) {
    }
}
