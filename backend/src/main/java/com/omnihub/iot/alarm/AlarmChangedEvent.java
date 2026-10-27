package com.omnihub.iot.alarm;

import com.omnihub.iot.alarm.AlarmDtos.AlarmVO;

/** Published when an alarm was created, recovered or acknowledged. */
public record AlarmChangedEvent(Long tenantId, AlarmVO alarm) {
}
