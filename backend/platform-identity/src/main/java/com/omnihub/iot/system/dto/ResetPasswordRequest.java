package com.omnihub.iot.system.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record ResetPasswordRequest(
        @NotBlank(message = "新密码不能为空") @Size(min = 8, max = 64, message = "密码长度需为8-64位") String newPassword) {
}
