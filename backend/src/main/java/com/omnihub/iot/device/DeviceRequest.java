package com.omnihub.iot.device;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record DeviceRequest(@NotBlank(message = "设备名称不能为空") @Size(max = 128) String name, Long productId,
        @Pattern(regexp = "^[a-zA-Z0-9_-]{3,64}$", message = "设备标识为3-64位字母、数字、_或-") String deviceKey,
        @Size(max = 512) String description) {

    /** Creates `count` devices (no upper bound) named {namePrefix}-001, {namePrefix}-002 ... with generated keys and secrets. */
    public record Batch(@NotNull(message = "所属产品不能为空") Long productId,
            @NotBlank(message = "名称前缀不能为空") @Size(max = 100, message = "名称前缀最长100字符") String namePrefix,
            @NotNull(message = "数量不能为空") @jakarta.validation.constraints.Min(value = 1, message = "数量至少为1") Integer count,
            @Size(max = 512) String description) {
    }

    public record Enabled(@NotNull(message = "enabled 不能为空") Boolean enabled) {
    }
}
