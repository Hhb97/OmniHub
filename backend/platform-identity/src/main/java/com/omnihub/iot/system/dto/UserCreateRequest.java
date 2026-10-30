package com.omnihub.iot.system.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.util.List;

public record UserCreateRequest(
        @NotBlank(message = "用户名不能为空") @Pattern(regexp = "^[a-zA-Z0-9_.-]{3,64}$", message = "用户名为3-64位字母、数字或 _.-") String username,
        @NotBlank(message = "密码不能为空") @Size(min = 8, max = 64, message = "密码长度需为8-64位") String password,
        @Size(max = 64) String nickname, @Email(message = "邮箱格式不正确") @Size(max = 128) String email,
        List<Long> roleIds) {
}
