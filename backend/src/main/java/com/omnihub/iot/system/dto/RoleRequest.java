package com.omnihub.iot.system.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.util.List;

public record RoleRequest(
        @NotBlank(message = "角色编码不能为空") @Pattern(regexp = "^[A-Z][A-Z0-9_]{1,63}$", message = "角色编码为大写字母/数字/下划线") String code,
        @NotBlank(message = "角色名称不能为空") @Size(max = 64) String name, @Size(max = 255) String description,
        List<String> permissions) {
}
