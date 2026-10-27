package com.omnihub.iot.alarm;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.Instant;
import lombok.Data;

@Data
@TableName("alarm_record")
public class AlarmRecord {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long tenantId;
    private Long ruleId;
    private Long deviceId;
    private String ruleName;
    private String ruleType;
    private String level;
    private String status;
    private Boolean acknowledged;
    private String message;
    private String triggerValue;
    private Integer triggerCount;
    private Instant firstTriggeredAt;
    private Instant lastTriggeredAt;
    private Instant recoveredAt;
    private String acknowledgedBy;
    private Instant acknowledgedAt;
}
