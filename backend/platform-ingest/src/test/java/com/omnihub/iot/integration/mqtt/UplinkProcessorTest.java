package com.omnihub.iot.integration.mqtt;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.IntNode;
import com.omnihub.iot.command.CommandService;
import com.omnihub.iot.device.DeviceProperties;
import com.omnihub.iot.device.DeviceRuntime;
import com.omnihub.iot.device.DeviceRuntime.DeviceRef;
import com.omnihub.iot.telemetry.MessageLogger;
import com.omnihub.iot.telemetry.TelemetryService;
import com.omnihub.iot.telemetry.TelemetryService.IngestResult;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/** Device -> platform message handling in isolation: routing, size / format checks, dedup, timestamps. */
class UplinkProcessorTest {

    private static final String TOPIC = "iot/pk/dk/";
    private static final IngestResult ACCEPTED = new IngestResult(Map.of("t", IntNode.valueOf(1)), Map.of());

    private final DeviceRuntime runtime = mock(DeviceRuntime.class);
    private final TelemetryService telemetry = mock(TelemetryService.class);
    private final CommandService commands = mock(CommandService.class);
    private final MessageLogger logger = mock(MessageLogger.class);
    private final DeviceProperties props = new DeviceProperties();
    private final DeviceRef ref = new DeviceRef(1L, 1L, 10L, "pk", "dk", "Dev", true, true, "h", "s");
    private UplinkProcessor processor;

    @BeforeEach
    void setUp() {
        when(runtime.resolve("pk", "dk")).thenReturn(Optional.of(ref));
        when(telemetry.ingestProperties(any(), any(), any())).thenReturn(ACCEPTED);
        processor = new UplinkProcessor(runtime, telemetry, commands, logger, props, new IngestStats(new IngestProperties(), new io.micrometer.core.instrument.simple.SimpleMeterRegistry()));
    }

    private void send(String kind, String json) {
        processor.handle(TOPIC + kind, json.getBytes(StandardCharsets.UTF_8));
    }

    private void verifyLogged(String type, String result, String message) {
        verify(logger).log(eq(1L), eq("UP"), eq(type), anyString(), anyString(), eq(result),
                message == null ? isNull() : eq(message));
    }

    // ------------------------------------------------------------------ routing

    @Test
    void validPropertyReportIsIngestedAndLoggedAsOk() {
        send("property/post", "{\"id\":\"m1\",\"params\":{\"t\":1}}");

        verify(runtime).touch(ref);
        verify(telemetry).ingestProperties(eq(ref), any(JsonNode.class), any(Instant.class));
        verifyLogged("PROPERTY", "OK", null);
    }

    @Test
    void messagesFromUnknownDevicesAreDroppedWithoutTouchingAnything() {
        when(runtime.resolve("pk", "ghost")).thenReturn(Optional.empty());

        processor.handle("iot/pk/ghost/property/post", "{\"params\":{\"t\":1}}".getBytes(StandardCharsets.UTF_8));

        verifyNoInteractions(telemetry, logger, commands);
        verify(runtime, never()).touch(any());
    }

    @Test
    void messagesFromDisabledDevicesOrProductsAreDropped() {
        for (DeviceRef disabled : new DeviceRef[] {
                new DeviceRef(1L, 1L, 10L, "pk", "dk", "Dev", false, true, "h", "s"),
                new DeviceRef(1L, 1L, 10L, "pk", "dk", "Dev", true, false, "h", "s") }) {
            when(runtime.resolve("pk", "dk")).thenReturn(Optional.of(disabled));
            send("property/post", "{\"params\":{\"t\":1}}");
            send("status", "{\"state\":\"online\"}");
        }
        verifyNoInteractions(telemetry, commands);
        verify(runtime, never()).touch(any());
        verify(runtime, never()).markConnected(any());
    }

    @Test
    void onlyDeviceToPlatformTopicsAreProcessed() {
        for (String topic : new String[] { "iot/pk/dk/command/down", "other/pk/dk/status", "iot/pk/status",
                "iot/pk/dk/property/post/extra", "iot/pk/dk/unknown", "iot", "" }) {
            processor.handle(topic, "{\"params\":{\"t\":1}}".getBytes(StandardCharsets.UTF_8));
        }
        verifyNoInteractions(telemetry, logger, commands);
        verify(runtime, never()).touch(any());
    }

    // ------------------------------------------------------------------ size / format

    @Test
    void payloadOverSixteenKilobytesIsRejectedAndLoggedWithoutTheBody() {
        byte[] big = new byte[16 * 1024 + 1];
        java.util.Arrays.fill(big, (byte) 'a');

        processor.handle(TOPIC + "property/post", big);

        verifyNoInteractions(telemetry);
        verify(logger).log(1L, "UP", "PROPERTY", TOPIC + "property/post", "[16385 bytes]", "INVALID",
                "消息超过 16KB 限制");
    }

    @Test
    void payloadOfExactlySixteenKilobytesIsAccepted() {
        String prefix = "{\"params\":{\"t\":\"";
        String suffix = "\"}}";
        String json = prefix + "a".repeat(16 * 1024 - prefix.length() - suffix.length()) + suffix;
        assertThat(json.getBytes(StandardCharsets.UTF_8)).hasSize(16 * 1024);

        send("property/post", json);

        verify(telemetry).ingestProperties(eq(ref), any(), any());
    }

    @Test
    void bodiesThatAreNotJsonObjectsAreRejected() {
        for (String body : new String[] { "not json", "[1,2]", "42", "\"text\"", "null", "", "{\"broken\":" }) {
            send("property/post", body);
        }
        verifyNoInteractions(telemetry);
        verify(logger, times(7)).log(eq(1L), eq("UP"), eq("PROPERTY"), anyString(), anyString(), eq("INVALID"),
                anyString());
    }

    @Test
    void propertyReportWithoutAParamsObjectIsRejected() {
        for (String body : new String[] { "{}", "{\"params\":[]}", "{\"params\":{}}", "{\"params\":\"x\"}",
                "{\"params\":null}" }) {
            send("property/post", body);
        }
        verify(telemetry, never()).ingestProperties(any(), any(), any());
        verify(logger, times(5)).log(eq(1L), eq("UP"), eq("PROPERTY"), anyString(), anyString(), eq("INVALID"),
                eq("缺少 params 对象"));
    }

    // ------------------------------------------------------------------ rejected values

    @Test
    void partiallyRejectedReportIsStillOkButExplainsWhy() {
        when(telemetry.ingestProperties(any(), any(), any()))
                .thenReturn(new IngestResult(Map.of("t", IntNode.valueOf(1)), Map.of("h", "超出范围")));

        send("property/post", "{\"params\":{\"t\":1,\"h\":999}}");

        verify(logger).log(eq(1L), eq("UP"), eq("PROPERTY"), anyString(), anyString(), eq("OK"),
                argThat((String m) -> m.startsWith("部分属性被拒绝") && m.contains("超出范围")));
    }

    @Test
    void fullyRejectedReportIsLoggedAsInvalid() {
        when(telemetry.ingestProperties(any(), any(), any()))
                .thenReturn(new IngestResult(Map.of(), Map.of("x", "物模型中未定义该属性")));

        send("property/post", "{\"params\":{\"x\":1}}");

        verify(logger).log(eq(1L), eq("UP"), eq("PROPERTY"), anyString(), anyString(), eq("INVALID"),
                argThat((String m) -> m.startsWith("全部属性被拒绝")));
    }

    // ------------------------------------------------------------------ duplicates

    @Test
    void repeatedMessageIdIsIgnoredButStillProvesTheDeviceIsAlive() {
        String msg = "{\"id\":\"m1\",\"params\":{\"t\":1}}";

        send("property/post", msg);
        send("property/post", msg);

        verify(telemetry, times(1)).ingestProperties(any(), any(), any());
        verify(runtime, times(2)).touch(ref);
    }

    @Test
    void messagesWithoutAnIdAreNeverTreatedAsDuplicates() {
        send("property/post", "{\"params\":{\"t\":1}}");
        send("property/post", "{\"params\":{\"t\":1}}");
        send("property/post", "{\"id\":null,\"params\":{\"t\":1}}");

        verify(telemetry, times(3)).ingestProperties(any(), any(), any());
    }

    @Test
    void dedupIsScopedPerDeviceAndPerMessageKind() {
        DeviceRef other = new DeviceRef(2L, 1L, 10L, "pk", "dk2", "Other", true, true, "h", "s");
        when(runtime.resolve("pk", "dk2")).thenReturn(Optional.of(other));
        when(telemetry.ingestEvent(any(), anyString(), any(), any())).thenReturn(null);

        send("property/post", "{\"id\":\"m1\",\"params\":{\"t\":1}}");
        processor.handle("iot/pk/dk2/property/post", "{\"id\":\"m1\",\"params\":{\"t\":1}}".getBytes(StandardCharsets.UTF_8));
        send("event/post", "{\"id\":\"m1\",\"event\":\"overheat\"}");

        verify(telemetry, times(2)).ingestProperties(any(), any(), any());
        verify(telemetry, times(1)).ingestEvent(any(), eq("overheat"), any(), any());
    }

    @Test
    void dedupCacheEvictsTheLeastRecentlySeenKey() {
        UplinkProcessor.DedupCache cache = new UplinkProcessor.DedupCache(3);
        assertThat(cache.seen("a")).isFalse();
        assertThat(cache.seen("b")).isFalse();
        assertThat(cache.seen("c")).isFalse();
        assertThat(cache.seen("a")).as("a was just seen again: now the most recent").isTrue();

        assertThat(cache.seen("d")).isFalse(); // evicts b, the least recently used
        assertThat(cache.seen("a")).isTrue();
        assertThat(cache.seen("b")).as("b was evicted").isFalse();
    }

    @Test
    void configuredCacheSizeBoundsMemory() {
        props.setDedupCacheSize(1);
        processor = new UplinkProcessor(runtime, telemetry, commands, logger, props, new IngestStats(new IngestProperties(), new io.micrometer.core.instrument.simple.SimpleMeterRegistry()));

        send("property/post", "{\"id\":\"a\",\"params\":{\"t\":1}}");
        send("property/post", "{\"id\":\"b\",\"params\":{\"t\":1}}"); // evicts a
        send("property/post", "{\"id\":\"a\",\"params\":{\"t\":1}}"); // no longer remembered

        verify(telemetry, times(3)).ingestProperties(any(), any(), any());
    }

    // ------------------------------------------------------------------ timestamps

    private Instant timestampUsedFor(String tsJson) {
        ArgumentCaptor<Instant> captor = ArgumentCaptor.forClass(Instant.class);
        org.mockito.Mockito.clearInvocations(telemetry);
        send("property/post", "{" + tsJson + "\"params\":{\"t\":1}}");
        verify(telemetry).ingestProperties(eq(ref), any(), captor.capture());
        return captor.getValue();
    }

    @Test
    void deviceClockIsTrustedOnlyWhenPlausible() {
        Instant now = Instant.now();

        Instant oneHourAgo = now.minus(Duration.ofHours(1));
        assertThat(timestampUsedFor("\"ts\":" + oneHourAgo.toEpochMilli() + ",")).isEqualTo(oneHourAgo.truncatedTo(java.time.temporal.ChronoUnit.MILLIS));

        Instant soon = now.plus(Duration.ofMinutes(1));
        assertThat(timestampUsedFor("\"ts\":" + soon.toEpochMilli() + ",")).isEqualTo(soon.truncatedTo(java.time.temporal.ChronoUnit.MILLIS));

        // too old (> 7 days), too far in the future (> 5 minutes), seconds instead of millis, wrong type, missing
        String[] untrusted = { "\"ts\":" + now.minus(Duration.ofDays(8)).toEpochMilli() + ",",
                "\"ts\":" + now.plus(Duration.ofMinutes(10)).toEpochMilli() + ",", "\"ts\":1700000000,",
                "\"ts\":\"2026-01-01T00:00:00Z\",", "\"ts\":null,", "" };
        for (String ts : untrusted) {
            assertThat(timestampUsedFor(ts)).as(ts).isBetween(now.minusSeconds(2), Instant.now().plusSeconds(2));
        }
    }

    // ------------------------------------------------------------------ events

    @Test
    void eventsNeedAStringIdentifier() {
        for (String body : new String[] { "{}", "{\"event\":5}", "{\"event\":null}" }) {
            send("event/post", body);
        }
        verify(telemetry, never()).ingestEvent(any(), anyString(), any(), any());
        verify(logger, times(3)).log(eq(1L), eq("UP"), eq("EVENT"), anyString(), anyString(), eq("INVALID"),
                eq("缺少 event 标识"));
    }

    @Test
    void acceptedAndRejectedEventsAreLoggedAccordingly() {
        when(telemetry.ingestEvent(eq(ref), eq("overheat"), any(), any())).thenReturn(null);
        when(telemetry.ingestEvent(eq(ref), eq("ghost"), any(), any())).thenReturn("物模型中未定义该事件");

        send("event/post", "{\"event\":\"overheat\",\"params\":{\"t\":99}}");
        send("event/post", "{\"event\":\"ghost\"}");

        verify(logger).log(eq(1L), eq("UP"), eq("EVENT"), anyString(), anyString(), eq("OK"), isNull());
        verify(logger).log(eq(1L), eq("UP"), eq("EVENT"), anyString(), anyString(), eq("INVALID"),
                eq("物模型中未定义该事件"));
    }

    // ------------------------------------------------------------------ status

    @Test
    void onlineAndOfflineStatusUpdateTheConnectionState() {
        send("status", "{\"state\":\"online\"}");
        verify(runtime).markConnected(1L);

        send("status", "{\"state\":\"offline\"}");
        verify(runtime).markDisconnected(1L);
        verify(logger, times(2)).log(eq(1L), eq("UP"), eq("STATUS"), anyString(), anyString(), eq("OK"), isNull());
    }

    @Test
    void anyOtherStatusValueIsRejectedAndChangesNothing() {
        for (String body : new String[] { "{}", "{\"state\":\"ONLINE\"}", "{\"state\":\"sleeping\"}", "{\"state\":1}",
                "{\"status\":\"online\"}" }) {
            send("status", body);
        }
        verify(runtime, never()).markConnected(any());
        verify(runtime, never()).markDisconnected(any());
        verify(logger, times(5)).log(eq(1L), eq("UP"), eq("STATUS"), anyString(), anyString(), eq("INVALID"),
                eq("state 只能为 online/offline"));
    }

    // ------------------------------------------------------------------ command replies

    @Test
    void commandReplyIsRoutedToTheCommandService() {
        when(commands.handleReply(any(), eq("c1"), eq("SUCCESS"), eq("done"), any())).thenReturn(true);

        send("command/reply", "{\"cmdId\":\"c1\",\"status\":\"SUCCESS\",\"message\":\"done\",\"data\":{\"ok\":true}}");

        verify(commands).handleReply(eq(ref), eq("c1"), eq("SUCCESS"), eq("done"), argThat(d -> d.get("ok").asBoolean()));
        verify(runtime).touch(ref);
        verifyLogged("REPLY", "OK", null);
    }

    @Test
    void ignoredRepliesAreLoggedAsRejected() {
        when(commands.handleReply(any(), anyString(), anyString(), any(), any())).thenReturn(false);

        send("command/reply", "{\"cmdId\":\"late\",\"status\":\"SUCCESS\"}");

        verifyLogged("REPLY", "REJECTED", "未知指令、重复回复或指令已结束");
    }

    @Test
    void repliesMissingTheCommandIdOrStatusNeverReachTheService() {
        send("command/reply", "{\"status\":\"SUCCESS\"}");
        send("command/reply", "{\"cmdId\":\"c1\"}");
        send("command/reply", "{\"cmdId\":\"\",\"status\":\"SUCCESS\"}");

        verifyNoInteractions(commands);
        verify(logger, times(3)).log(eq(1L), eq("UP"), eq("REPLY"), anyString(), anyString(), eq("INVALID"),
                eq("缺少 cmdId 或 status"));
    }

    // ------------------------------------------------------------------ resilience

    @Test
    void aFailureInsideAServiceNeverEscapesIntoTheMqttThread() {
        when(telemetry.ingestProperties(any(), any(), any())).thenThrow(new IllegalStateException("db down"));
        when(runtime.resolve("pk", "boom")).thenThrow(new IllegalStateException("db down"));

        assertThatCode(() -> send("property/post", "{\"params\":{\"t\":1}}")).doesNotThrowAnyException();
        assertThatCode(() -> processor.handle("iot/pk/boom/status", "{}".getBytes(StandardCharsets.UTF_8)))
                .doesNotThrowAnyException();
    }
}
