package com.omnihub.iot.command;

import com.fasterxml.jackson.databind.JsonNode;
import com.omnihub.iot.common.Jsons;
import java.time.Instant;

public record CommandVO(Long id, String commandNo, Long deviceId, String type, String identifier, JsonNode params,
        String status, JsonNode result, String message, String createdBy, Instant createdAt, Instant sentAt,
        Instant receivedAt, Instant finishedAt) {

    public static CommandVO of(DeviceCommand c) {
        return new CommandVO(c.getId(), c.getCommandNo(), c.getDeviceId(), c.getType(), c.getIdentifier(),
                Jsons.parse(c.getParams()), c.getStatus(), Jsons.parse(c.getResult()), c.getMessage(),
                c.getCreatedBy(), c.getCreatedAt(), c.getSentAt(), c.getReceivedAt(), c.getFinishedAt());
    }
}
