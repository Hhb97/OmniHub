package com.omnihub.iot.device;

import java.time.Instant;

/** Published whenever a device's business online status changes (ONLINE / OFFLINE). */
public record DeviceStatusEvent(Long deviceId, Long tenantId, String onlineStatus, Instant at) {
}
