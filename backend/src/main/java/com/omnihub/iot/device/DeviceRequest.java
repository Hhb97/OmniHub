package com.omnihub.iot.device;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record DeviceRequest(@NotBlank(message = "设备名称不能为空") @Size(max = 128) String name, Long productId,
        @Pattern(regexp = "^[a-zA-Z0-9_-]{3,64}$", message = "设备标识为3-64位字母、数字、_或-") String deviceKey,
        @Size(max = 512) String description) {

    public record Enabled(@NotNull(message = "enabled 不能为空") Boolean enabled) {
    }
}
