package com.omnihub.iot.integration.mqtt;

import com.fasterxml.jackson.databind.JsonNode;
import com.omnihub.iot.command.CommandService;
import com.omnihub.iot.common.Jsons;
import com.omnihub.iot.config.IotProperties;
import com.omnihub.iot.device.DeviceRuntime;
import com.omnihub.iot.device.DeviceRuntime.DeviceRef;
import com.omnihub.iot.telemetry.MessageLogger;
import com.omnihub.iot.telemetry.TelemetryService;
import com.omnihub.iot.telemetry.TelemetryService.IngestResult;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Component;

/**
 * Processes device -> platform messages: topic routing, size/format checks, de-duplication, thing-model
 * validation and dispatch to the right service. Every message leaves a row in the device communication log.
 * A failure while handling one message is contained here and never propagates into the MQTT client threads.
 */
@Component
public class UplinkProcessor {

    private static final Logger log = LoggerFactory.getLogger(UplinkProcessor.class);
    private static final int MAX_PAYLOAD_BYTES = 16 * 1024;
    private static final Duration MAX_PAST = Duration.ofDays(7);
    private static final Duration MAX_FUTURE = Duration.ofMinutes(5);

    private final DeviceRuntime runtime;
    private final TelemetryService telemetry;
    private final CommandService commands;
    private final MessageLogger messageLogger;
    private final DedupCache dedup;
    private final IngestStats stats;

    // @Lazy breaks the cycle CommandService -> DownlinkPublisher (gateway) -> UplinkProcessor -> CommandService
    public UplinkProcessor(DeviceRuntime runtime, TelemetryService telemetry, @Lazy CommandService commands,
            MessageLogger messageLogger, IotProperties props, IngestStats stats) {
        this.stats = stats;
        this.runtime = runtime;
        this.telemetry = telemetry;
        this.commands = commands;
        this.messageLogger = messageLogger;
        this.dedup = new DedupCache(props.getDevice().getDedupCacheSize());
    }

    public void handle(String topic, byte[] payload) {
        handle(topic, payload, System.currentTimeMillis());
    }

    /** @param receivedAt when the platform first saw the message (MQTT arrival); used for the queue-delay statistic */
    public void handle(String topic, byte[] payload, long receivedAt) {
        stats.record(System.currentTimeMillis() - receivedAt);
        try {
            doHandle(topic, payload);
        } catch (Exception e) {
            log.error("Failed to process message on topic {}: {}", topic, e.toString(), e);
        }
    }

    private void doHandle(String topic, byte[] payload) {
        Optional<Topics.Parsed> parsed = Topics.parse(topic);
        if (parsed.isEmpty() || !Topics.UPLINK_KINDS.contains(parsed.get().kind())) {
            log.debug("Ignoring message on unsupported topic {}", topic);
            return;
        }
        Topics.Parsed t = parsed.get();
        Optional<DeviceRef> found = runtime.resolve(t.productKey(), t.deviceKey());
        if (found.isEmpty()) {
            log.warn("Message for unknown device {} dropped (topic {})", t.clientId(), topic);
            return;
        }
        DeviceRef ref = found.get();
        if (!ref.enabled() || !ref.productEnabled()) {
            log.warn("Message from disabled device {} dropped", t.clientId());
            return;
        }
        String type = switch (t.kind()) {
            case "property/post" -> "PROPERTY";
            case "event/post" -> "EVENT";
            case "status" -> "STATUS";
            default -> "REPLY";
        };
        if (payload.length > MAX_PAYLOAD_BYTES) {
            messageLogger.log(ref.id(), "UP", type, topic, "[" + payload.length + " bytes]", "INVALID", "消息超过 16KB 限制");
            return;
        }
        String text = new String(payload, StandardCharsets.UTF_8);
        JsonNode body;
        try {
            body = Jsons.parse(text);
        } catch (IllegalArgumentException e) {
            messageLogger.log(ref.id(), "UP", type, topic, text, "INVALID", "不是合法的 JSON");
            return;
        }
        if (body == null || !body.isObject()) {
            messageLogger.log(ref.id(), "UP", type, topic, text, "INVALID", "消息体必须是 JSON 对象");
            return;
        }
        switch (t.kind()) {
            case "property/post" -> onProperty(ref, topic, text, body);
            case "event/post" -> onEvent(ref, topic, text, body);
            case "status" -> onStatus(ref, topic, text, body);
            default -> onReply(ref, topic, text, body);
        }
    }

    private void onProperty(DeviceRef ref, String topic, String text, JsonNode body) {
        JsonNode params = body.get("params");
        if (params == null || !params.isObject() || params.isEmpty()) {
            messageLogger.log(ref.id(), "UP", "PROPERTY", topic, text, "INVALID", "缺少 params 对象");
            return;
        }
        if (isDuplicate(ref, "p", body)) {
            runtime.touch(ref);
            return;
        }
        runtime.touch(ref);
        IngestResult r = telemetry.ingestProperties(ref, params, timestamp(body));
        if (r.rejected().isEmpty()) {
            messageLogger.log(ref.id(), "UP", "PROPERTY", topic, text, "OK", null);
        } else {
            Map<String, String> rej = new LinkedHashMap<>(r.rejected());
            messageLogger.log(ref.id(), "UP", "PROPERTY", topic, text, r.accepted().isEmpty() ? "INVALID" : "OK",
                    (r.accepted().isEmpty() ? "全部属性被拒绝: " : "部分属性被拒绝: ") + Jsons.toJson(rej));
        }
    }

    private void onEvent(DeviceRef ref, String topic, String text, JsonNode body) {
        JsonNode id = body.get("event");
        if (id == null || !id.isTextual()) {
            messageLogger.log(ref.id(), "UP", "EVENT", topic, text, "INVALID", "缺少 event 标识");
            return;
        }
        if (isDuplicate(ref, "e", body)) {
            runtime.touch(ref);
            return;
        }
        runtime.touch(ref);
        String err = telemetry.ingestEvent(ref, id.asText(), body.get("params"), timestamp(body));
        messageLogger.log(ref.id(), "UP", "EVENT", topic, text, err == null ? "OK" : "INVALID", err);
    }

    private void onStatus(DeviceRef ref, String topic, String text, JsonNode body) {
        String state = body.path("state").asText("");
        switch (state) {
            case "online" -> {
                runtime.markConnected(ref.id());
                messageLogger.log(ref.id(), "UP", "STATUS", topic, text, "OK", null);
            }
            case "offline" -> {
                runtime.markDisconnected(ref.id());
                messageLogger.log(ref.id(), "UP", "STATUS", topic, text, "OK", null);
            }
            default -> messageLogger.log(ref.id(), "UP", "STATUS", topic, text, "INVALID", "state 只能为 online/offline");
        }
    }

    private void onReply(DeviceRef ref, String topic, String text, JsonNode body) {
        String cmdId = body.path("cmdId").asText("");
        String status = body.path("status").asText("");
        if (cmdId.isEmpty() || status.isEmpty()) {
            messageLogger.log(ref.id(), "UP", "REPLY", topic, text, "INVALID", "缺少 cmdId 或 status");
            return;
        }
        runtime.touch(ref);
        boolean applied = commands.handleReply(ref, cmdId, status, body.path("message").asText(null),
                body.get("data"));
        messageLogger.log(ref.id(), "UP", "REPLY", topic, text, applied ? "OK" : "REJECTED",
                applied ? null : "未知指令、重复回复或指令已结束");
    }

    private boolean isDuplicate(DeviceRef ref, String kind, JsonNode body) {
        JsonNode id = body.get("id");
        if (id == null || id.isNull()) {
            return false;
        }
        boolean dup = dedup.seen(ref.id() + ":" + kind + ":" + id.asText());
        if (dup) {
            log.debug("Duplicate message {} from device {} ignored", id.asText(), ref.id());
        }
        return dup;
    }

    /** Device clock is only trusted when plausible; otherwise server time is used. */
    private static Instant timestamp(JsonNode body) {
        Instant now = Instant.now();
        JsonNode ts = body.get("ts");
        if (ts != null && ts.isNumber()) {
            Instant t = Instant.ofEpochMilli(ts.asLong());
            if (t.isAfter(now.minus(MAX_PAST)) && t.isBefore(now.plus(MAX_FUTURE))) {
                return t;
            }
        }
        return now;
    }

    /** Bounded LRU set of recently seen message keys. */
    static final class DedupCache {
        private final Map<String, Boolean> map;

        DedupCache(int max) {
            this.map = new java.util.LinkedHashMap<>(16, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<String, Boolean> eldest) {
                    return size() > max;
                }
            };
        }

        synchronized boolean seen(String key) {
            return map.put(key, Boolean.TRUE) != null;
        }
    }
}
