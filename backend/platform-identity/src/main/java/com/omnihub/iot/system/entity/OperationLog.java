package com.omnihub.iot.system.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.Instant;
import lombok.Data;

@Data
@TableName("sys_operation_log")
public class OperationLog {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long tenantId;
    private String username;
    private String module;
    private String action;
    private String method;
    private String path;
    private String ip;
    private Boolean success;
    private Long costMs;
    private String detail;
    private Instant createdAt;
}
