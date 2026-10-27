package com.omnihub.iot.device;

import java.time.Instant;

/** Device view. Never carries credentials, except `secret` which is set exactly once on create / rotate. */
public record DeviceVO(Long id, String name, String deviceKey, Long productId, String productName, String productKey,
        String description, Boolean enabled, String onlineStatus, Boolean mqttConnected, String clientId,
        Instant lastOnlineAt, Instant lastOfflineAt, Instant lastCommunicationAt, Instant createdAt, String secret) {

    public DeviceVO withSecret(String s) {
        return new DeviceVO(id, name, deviceKey, productId, productName, productKey, description, enabled,
                onlineStatus, mqttConnected, clientId, lastOnlineAt, lastOfflineAt, lastCommunicationAt, createdAt, s);
    }
}
