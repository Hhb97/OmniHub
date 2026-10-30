package com.omnihub.iot.product;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record ProductRequest(@NotBlank(message = "产品名称不能为空") @Size(max = 128) String name,
        @Pattern(regexp = "^[a-zA-Z0-9_-]{3,64}$", message = "产品标识为3-64位字母、数字、_或-") String productKey,
        @Size(max = 64) String category,
        @Pattern(regexp = "^(MQTT)$", message = "目前仅支持 MQTT 协议") String protocol,
        @Pattern(regexp = "^(ENABLED|DISABLED)$", message = "状态只能为 ENABLED 或 DISABLED") String status,
        @Size(max = 512) String description) {
}
