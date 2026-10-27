package com.omnihub.iot.system.entity;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

@Data
@TableName("sys_config")
public class SysConfig {
    @TableId
    private String configKey;
    private String configValue;
    private String description;
}
