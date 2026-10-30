package com.omnihub.iot.command;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.Instant;
import lombok.Data;

@Data
@TableName("iot_device_command")
public class DeviceCommand {
    @TableId(type = IdType.AUTO)
    private Long id;
    private String commandNo;
    private Long tenantId;
    private Long deviceId;
    private String type;
    private String identifier;
    /** JSONB, held as text */
    private String params;
    private String status;
    /** JSONB, held as text */
    private String result;
    private String message;
    private String createdBy;
    private Instant createdAt;
    private Instant sentAt;
    private Instant receivedAt;
    private Instant finishedAt;
    private Instant timeoutAt;
}
