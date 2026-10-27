package com.omnihub.iot.integration.ws;

import com.fasterxml.jackson.databind.JsonNode;
import com.omnihub.iot.common.Jsons;
import com.omnihub.iot.device.Device;
import com.omnihub.iot.device.DeviceMapper;
import com.omnihub.iot.security.LoginUser;
import com.omnihub.iot.system.service.AccountService;
import java.util.Map;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;

/**
 * Client protocol: {"action":"subscribe|unsubscribe","deviceId":N} or {"action":...,"topic":"dashboard"}.
 * A device subscription is only accepted when the device belongs to the user's tenant and the user (with the
 * permissions as they are right now) holds telemetry:view.
 */
@Component
public class WsHandler extends TextWebSocketHandler {

    private final WsHub hub;
    private final DeviceMapper deviceMapper;
    private final AccountService accountService;

    public WsHandler(WsHub hub, DeviceMapper deviceMapper, AccountService accountService) {
        this.hub = hub;
        this.deviceMapper = deviceMapper;
        this.accountService = accountService;
    }

    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage message) {
        if (message.getPayloadLength() > 1024) {
            WsHub.sendTo(session, Map.of("type", "error", "message", "消息过长"));
            return;
        }
        JsonNode m;
        try {
            m = Jsons.parse(message.getPayload());
        } catch (IllegalArgumentException e) {
            WsHub.sendTo(session, Map.of("type", "error", "message", "消息格式错误"));
            return;
        }
        String action = m.path("action").asText("");
        if ("ping".equals(action)) {
            WsHub.sendTo(session, Map.of("type", "pong"));
            return;
        }
        // re-read the user so permission changes apply to already-open sockets
        LoginUser user = accountService.loadLoginUser(((LoginUser) session.getAttributes().get(WsAuthInterceptor.ATTR_USER)).userId())
                .orElse(null);
        if (user == null) {
            closeQuietly(session);
            return;
        }
        if ("dashboard".equals(m.path("topic").asText(""))) {
            if (!user.has("dashboard:view")) {
                WsHub.sendTo(session, Map.of("type", "error", "message", "无权限订阅总览"));
            } else if ("subscribe".equals(action)) {
                hub.subscribeDashboard(session);
                WsHub.sendTo(session, Map.of("type", "subscribed", "topic", "dashboard"));
            } else {
                hub.unsubscribeDashboard(session);
            }
            return;
        }
        if (!m.hasNonNull("deviceId") || !m.get("deviceId").canConvertToLong()) {
            WsHub.sendTo(session, Map.of("type", "error", "message", "缺少 deviceId"));
            return;
        }
        long deviceId = m.get("deviceId").asLong();
        if ("unsubscribe".equals(action)) {
            hub.unsubscribeDevice(deviceId, session);
            return;
        }
        if (!"subscribe".equals(action)) {
            WsHub.sendTo(session, Map.of("type", "error", "message", "未知 action"));
            return;
        }
        Device d = deviceMapper.selectById(deviceId);
        if (!user.has("telemetry:view") || d == null || !d.getTenantId().equals(user.tenantId())) {
            WsHub.sendTo(session, Map.of("type", "error", "message", "无权订阅该设备或设备不存在"));
            return;
        }
        hub.subscribeDevice(deviceId, session);
        WsHub.sendTo(session, Map.of("type", "subscribed", "deviceId", deviceId));
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        hub.remove(session);
    }

    @Override
    public void handleTransportError(WebSocketSession session, Throwable exception) {
        hub.remove(session);
    }

    private static void closeQuietly(WebSocketSession s) {
        try {
            s.close(CloseStatus.POLICY_VIOLATION);
        } catch (Exception ignored) {
            // already closed
        }
    }
}
