package com.omnihub.iot.system.entity;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

@Data
@TableName("sys_permission")
public class SysPermission {
    @TableId
    private String code;
    private String name;
    private String type;
    private String parentCode;
    private Integer sort;
}
