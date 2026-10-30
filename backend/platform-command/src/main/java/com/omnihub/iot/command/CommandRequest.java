package com.omnihub.iot.command;

import com.fasterxml.jackson.databind.JsonNode;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record CommandRequest(
        @NotBlank(message = "指令类型不能为空") @Pattern(regexp = "^(SERVICE|PROPERTY_SET|PROPERTY_GET)$", message = "type 只能为 SERVICE/PROPERTY_SET/PROPERTY_GET") String type,
        @Size(max = 64) String identifier, JsonNode params) {
}
