package com.omnihub.iot.system.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.Size;
import java.util.List;

public record UserUpdateRequest(@Size(max = 64) String nickname,
        @Email(message = "邮箱格式不正确") @Size(max = 128) String email, Boolean enabled, List<Long> roleIds) {
}
