package com.omnihub.iot.integration.mqtt;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.omnihub.iot.config.IotProperties;
import com.omnihub.iot.device.DeviceRuntime;
import com.omnihub.iot.integration.mqtt.MqttAuthController.AclRequest;
import com.omnihub.iot.integration.mqtt.MqttAuthController.AuthRequest;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

/**
 * The HTTP authn / authz hooks used by an external broker (EMQX). ACL rules are security critical: a device may
 * only publish its own uplink topics and subscribe to its own command topic.
 */
class MqttAuthControllerTest {

    private static final String TOKEN = "broker-shared-token";
    private static final String DEVICE = "pk1.dev1";

    private final DeviceRuntime runtime = mock(DeviceRuntime.class);
    private IotProperties props;
    private MqttAuthController controller;
    private final SimpleMeterRegistry meters = new SimpleMeterRegistry();

    @BeforeEach
    void setUp() {
        props = new IotProperties();
        props.getMqtt().setHttpAuthToken(TOKEN);
        props.getMqtt().setPlatformUsername("platform");
        props.getMqtt().setPlatformPassword("platform-pass");
        controller = new MqttAuthController(runtime, props, meters);
    }

    private String auth(String clientId, String username, String password) {
        return controller.auth(TOKEN, new AuthRequest(clientId, username, password)).get("result");
    }

    private String acl(String username, String action, String topic) {
        return controller.acl(TOKEN, new AclRequest(DEVICE, username, topic, action)).get("result");
    }

    // ------------------------------------------------------------------ shared token

    @Test
    void callsWithoutTheSharedTokenAreRejected() {
        for (String token : new String[] { null, "", "wrong", TOKEN + "x", TOKEN.substring(1) }) {
            assertThatThrownBy(() -> controller.auth(token, new AuthRequest(DEVICE, DEVICE, "s")))
                    .isInstanceOfSatisfying(ResponseStatusException.class,
                            e -> assertThat(e.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED));
            assertThatThrownBy(() -> controller.acl(token, new AclRequest(DEVICE, DEVICE, "t", "publish")))
                    .isInstanceOf(ResponseStatusException.class);
        }
        verifyNoInteractions(runtime);
    }

    @Test
    void anUnconfiguredTokenNeverMatchesAnEmptyHeader() {
        props.getMqtt().setHttpAuthToken("");
        assertThatThrownBy(() -> controller.auth("", new AuthRequest(DEVICE, DEVICE, "s")))
                .isInstanceOf(ResponseStatusException.class);
        assertThatThrownBy(() -> controller.auth(null, new AuthRequest(DEVICE, DEVICE, "s")))
                .isInstanceOf(ResponseStatusException.class);
        assertThatThrownBy(() -> controller.acl("", new AclRequest(DEVICE, DEVICE, "t", "publish")))
                .isInstanceOf(ResponseStatusException.class);
    }

    // ------------------------------------------------------------------ authn

    @Test
    void platformAccountNeedsTheConfiguredPassword() {
        assertThat(auth("iot-platform", "platform", "platform-pass")).isEqualTo("allow");
        assertThat(auth("iot-platform", "platform", "nope")).isEqualTo("deny");
        assertThat(auth("iot-platform", "platform", null)).isEqualTo("deny");
        verifyNoInteractions(runtime); // never looked up as a device
    }

    @Test
    void platformAccountWithNoConfiguredPasswordIsAlwaysDenied() {
        props.getMqtt().setPlatformPassword("");
        assertThat(auth("iot-platform", "platform", "")).isEqualTo("deny");
        assertThat(auth("iot-platform", "platform", "anything")).isEqualTo("deny");
    }

    @Test
    void deviceAuthenticationIsDelegatedToTheRuntime() {
        when(runtime.check(DEVICE, DEVICE, "good")).thenReturn(DeviceRuntime.AuthResult.ALLOW);
        when(runtime.check(DEVICE, DEVICE, "bad")).thenReturn(DeviceRuntime.AuthResult.BAD_SECRET);
        when(runtime.check(DEVICE, null, "good")).thenReturn(DeviceRuntime.AuthResult.MALFORMED);

        assertThat(auth(DEVICE, DEVICE, "good")).isEqualTo("allow");
        assertThat(auth(DEVICE, DEVICE, "bad")).isEqualTo("deny");
        assertThat(auth(DEVICE, null, "good")).isEqualTo("deny");
    }

    private double counter(String kind, String result, String reason) {
        io.micrometer.core.instrument.Counter c = meters.find("iot_mqtt_" + kind + "_total").tag("result", result)
                .tag("reason", reason).counter();
        return c == null ? 0 : c.count();
    }

    @Test
    void everyDecisionIsCountedByResultAndReason() {
        when(runtime.check(DEVICE, DEVICE, "good")).thenReturn(DeviceRuntime.AuthResult.ALLOW);
        when(runtime.check(DEVICE, DEVICE, "bad")).thenReturn(DeviceRuntime.AuthResult.BAD_SECRET);
        when(runtime.check("ghost.d", "ghost.d", "x")).thenReturn(DeviceRuntime.AuthResult.UNKNOWN_DEVICE);

        auth(DEVICE, DEVICE, "good");
        auth(DEVICE, DEVICE, "good");
        for (int i = 0; i < 5; i++) {
            auth(DEVICE, DEVICE, "bad");
        }
        auth("ghost.d", "ghost.d", "x");
        auth("iot-platform", "platform", "platform-pass");
        auth("iot-platform", "platform", "wrong");
        acl(DEVICE, "publish", "iot/pk1/dev1/status");
        acl(DEVICE, "publish", "iot/pk1/dev2/status");
        acl(null, "publish", "iot/pk1/dev1/status");

        assertThat(counter("auth", "allow", "device")).isEqualTo(2);
        assertThat(counter("auth", "deny", "bad_secret")).isEqualTo(5);
        assertThat(counter("auth", "deny", "unknown_device")).isEqualTo(1);
        assertThat(counter("auth", "allow", "platform")).isEqualTo(1);
        assertThat(counter("auth", "deny", "platform_bad_password")).isEqualTo(1);
        assertThat(counter("acl", "allow", "device")).isEqualTo(1);
        assertThat(counter("acl", "deny", "topic_not_allowed")).isEqualTo(1);
        assertThat(counter("acl", "deny", "no_username")).isEqualTo(1);
    }

    // ------------------------------------------------------------------ ACL

    @Test
    void deviceMayPublishOnlyItsOwnUplinkTopics() {
        for (String kind : new String[] { "property/post", "event/post", "status", "command/reply" }) {
            assertThat(acl(DEVICE, "publish", "iot/pk1/dev1/" + kind)).as(kind).isEqualTo("allow");
        }
        // the downlink topic is platform -> device only
        assertThat(acl(DEVICE, "publish", "iot/pk1/dev1/command/down")).isEqualTo("deny");
        // another device's topics
        assertThat(acl(DEVICE, "publish", "iot/pk1/dev2/property/post")).isEqualTo("deny");
        assertThat(acl(DEVICE, "publish", "iot/pk2/dev1/property/post")).isEqualTo("deny");
        // unknown suffixes, extra levels, prefix tricks, wildcards, other roots
        assertThat(acl(DEVICE, "publish", "iot/pk1/dev1/other")).isEqualTo("deny");
        assertThat(acl(DEVICE, "publish", "iot/pk1/dev1/status/extra")).isEqualTo("deny");
        assertThat(acl(DEVICE, "publish", "iot/pk1/dev1")).isEqualTo("deny");
        assertThat(acl(DEVICE, "publish", "iot/pk1/dev10/status")).isEqualTo("deny");
        assertThat(acl(DEVICE, "publish", "iot/pk1/dev1/#")).isEqualTo("deny");
        assertThat(acl(DEVICE, "publish", "iot/+/+/property/post")).isEqualTo("deny");
        assertThat(acl(DEVICE, "publish", "$SYS/broker/clients")).isEqualTo("deny");
        assertThat(acl(DEVICE, "publish", "other/pk1/dev1/status")).isEqualTo("deny");
    }

    @Test
    void deviceMaySubscribeOnlyToItsOwnCommandTopic() {
        assertThat(acl(DEVICE, "subscribe", "iot/pk1/dev1/command/down")).isEqualTo("allow");
        assertThat(acl(DEVICE, "subscribe", "iot/pk1/dev2/command/down")).isEqualTo("deny");
        assertThat(acl(DEVICE, "subscribe", "iot/pk1/dev1/property/post")).isEqualTo("deny");
        assertThat(acl(DEVICE, "subscribe", "iot/pk1/dev1/#")).isEqualTo("deny");
        assertThat(acl(DEVICE, "subscribe", "iot/+/+/command/down")).isEqualTo("deny");
        assertThat(acl(DEVICE, "subscribe", "#")).isEqualTo("deny");
        assertThat(acl(DEVICE, "subscribe", "$SYS/#")).isEqualTo("deny");
        // an unknown or missing action is treated like a subscription, never as a wider permission
        assertThat(acl(DEVICE, null, "iot/pk1/dev1/command/down")).isEqualTo("allow");
        assertThat(acl(DEVICE, null, "iot/pk1/dev1/status")).isEqualTo("deny");
    }

    @Test
    void usernamesThatAreNotDeviceClientIdsAreDenied() {
        assertThat(acl(null, "publish", "iot/pk1/dev1/status")).isEqualTo("deny");
        assertThat(acl("nodot", "publish", "iot/pk1/dev1/status")).isEqualTo("deny");
        assertThat(acl(".leading", "publish", "iot/x/y/status")).isEqualTo("deny");
        assertThat(acl(DEVICE, "publish", null)).isEqualTo("deny");
    }

    @Test
    void platformAccountMayUseAnyTopic() {
        assertThat(acl("platform", "publish", "iot/pk1/dev1/command/down")).isEqualTo("allow");
        assertThat(acl("platform", "subscribe", "iot/+/+/property/post")).isEqualTo("allow");
    }

    @Test
    void aDeviceKeyContainingDotsStillMapsToItsOwnPrefix() {
        // clientId splits on the FIRST dot, exactly like DeviceRuntime.resolveClientId
        assertThat(acl("pk1.dev.with.dots", "publish", "iot/pk1/dev.with.dots/status")).isEqualTo("allow");
        assertThat(acl("pk1.dev.with.dots", "publish", "iot/pk1.dev/with.dots/status")).isEqualTo("deny");
    }
}
