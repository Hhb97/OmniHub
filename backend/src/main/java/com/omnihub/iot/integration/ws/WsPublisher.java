package com.omnihub.iot.integration.ws;

import com.omnihub.iot.alarm.AlarmChangedEvent;
import com.omnihub.iot.command.CommandChangedEvent;
import com.omnihub.iot.device.DeviceStatusEvent;
import com.omnihub.iot.telemetry.DeviceEventReported;
import com.omnihub.iot.telemetry.PropertyReportedEvent;
import java.util.LinkedHashMap;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/** Translates domain events into WebSocket pushes (see docs/api-contract.md for the message shapes). */
@Component
public class WsPublisher {

    private static final Logger log = LoggerFactory.getLogger(WsPublisher.class);

    private final WsHub hub;

    public WsPublisher(WsHub hub) {
        this.hub = hub;
    }

    @EventListener
    public void onProperty(PropertyReportedEvent e) {
        guard(() -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("type", "property");
            m.put("deviceId", e.deviceId());
            m.put("ts", e.ts().toEpochMilli());
            m.put("data", e.values());
            hub.pushToDevice(e.deviceId(), m);
            hub.signalDashboard(e.tenantId());
        });
    }

    @EventListener
    public void onStatus(DeviceStatusEvent e) {
        guard(() -> {
            hub.pushToDeviceNow(e.deviceId(), Map.of("type", "status", "deviceId", e.deviceId(), "ts",
                    e.at().toEpochMilli(), "onlineStatus", e.onlineStatus()));
            hub.signalDashboard(e.tenantId());
        });
    }

    @EventListener
    public void onDeviceEvent(DeviceEventReported e) {
        guard(() -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("type", "event");
            m.put("deviceId", e.deviceId());
            m.put("ts", e.ts().toEpochMilli());
            m.put("identifier", e.identifier());
            m.put("level", e.level());
            if (e.params() != null) {
                m.put("params", e.params());
            }
            hub.pushToDeviceNow(e.deviceId(), m);
        });
    }

    @EventListener
    public void onCommand(CommandChangedEvent e) {
        guard(() -> hub.pushToDeviceNow(e.command().deviceId(),
                Map.of("type", "command", "deviceId", e.command().deviceId(), "command", e.command())));
    }

    @EventListener
    public void onAlarm(AlarmChangedEvent e) {
        guard(() -> {
            hub.pushToDeviceNow(e.alarm().deviceId(),
                    Map.of("type", "alarm", "deviceId", e.alarm().deviceId(), "alarm", e.alarm()));
            hub.signalDashboard(e.tenantId());
        });
    }

    private void guard(Runnable r) {
        try {
            r.run();
        } catch (Exception ex) {
            log.warn("WebSocket push failed: {}", ex.toString());
        }
    }
}
