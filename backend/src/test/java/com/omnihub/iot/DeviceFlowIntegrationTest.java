package com.omnihub.iot;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.UUID;
import org.eclipse.paho.client.mqttv3.MqttClient;
import org.eclipse.paho.client.mqttv3.MqttConnectOptions;
import org.eclipse.paho.client.mqttv3.MqttException;
import org.eclipse.paho.client.mqttv3.MqttMessage;
import org.eclipse.paho.client.mqttv3.persist.MemoryPersistence;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;

/**
 * Core business flow against real components: embedded PostgreSQL (Flyway migrations), embedded MQTT broker,
 * the platform's MQTT gateway, REST API with JWT and a Paho client acting as the device.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class DeviceFlowIntegrationTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String ADMIN_PASSWORD = "Test-Admin-Pass-1";
    private static final String SUFFIX = UUID.randomUUID().toString().substring(0, 6);
    private static final String PRODUCT_KEY = "it-prod-" + SUFFIX;
    private static final String DEVICE_KEY = "it-dev-" + SUFFIX;

    @LocalServerPort
    int port;
    @Autowired
    TestRestTemplate rest;

    static String adminToken;
    static long productId;
    static long deviceId;
    static String deviceSecret;

    // ------------------------------------------------------------------ helpers

    JsonNode call(HttpMethod method, String path, Object body, String token, int expectedStatus) {
        HttpHeaders h = new HttpHeaders();
        h.setContentType(MediaType.APPLICATION_JSON);
        if (token != null) {
            h.setBearerAuth(token);
        }
        var res = rest.exchange("http://localhost:" + port + "/api" + path, method,
                new HttpEntity<>(body == null ? null : toJson(body), h), String.class);
        assertThat(res.getStatusCode().value()).as("%s %s -> %s", method, path, res.getBody())
                .isEqualTo(expectedStatus);
        try {
            return res.getBody() == null ? null : JSON.readTree(res.getBody());
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    static String toJson(Object o) {
        try {
            return JSON.writeValueAsString(o);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    static MqttClient connectDevice(String secret) throws MqttException {
        String clientId = PRODUCT_KEY + "." + DEVICE_KEY;
        MqttClient c = new MqttClient("tcp://localhost:18830", clientId, new MemoryPersistence());
        MqttConnectOptions o = new MqttConnectOptions();
        o.setUserName(clientId);
        o.setPassword(secret.toCharArray());
        o.setCleanSession(true);
        o.setAutomaticReconnect(false);
        o.setConnectionTimeout(5);
        c.connect(o);
        return c;
    }

    static void publish(MqttClient c, String kind, Map<String, Object> body) throws MqttException {
        MqttMessage m = new MqttMessage(toJson(body).getBytes(StandardCharsets.UTF_8));
        m.setQos(1);
        c.publish("iot/" + PRODUCT_KEY + "/" + DEVICE_KEY + "/" + kind, m);
    }

    // ------------------------------------------------------------------ tests

    @Test
    @Order(1)
    void authenticationAndAuthorizationBasics() {
        call(HttpMethod.GET, "/devices", null, null, 401);
        call(HttpMethod.POST, "/auth/login", Map.of("username", "admin", "password", "wrong"), null, 401);
        JsonNode login = call(HttpMethod.POST, "/auth/login",
                Map.of("username", "admin", "password", ADMIN_PASSWORD), null, 200);
        adminToken = login.at("/data/token").asText();
        assertThat(adminToken).isNotBlank();
        assertThat(login.at("/data/user/permissions").toString()).contains("device:control");
        // /health is public and exposes no details
        var health = rest.getForEntity("http://localhost:" + port + "/actuator/health", String.class);
        assertThat(health.getStatusCode().is2xxSuccessful()).isTrue();
    }

    @Test
    @Order(2)
    void createProductThingModelAndDevice() {
        JsonNode p = call(HttpMethod.POST, "/products", Map.of("name", "IT product", "productKey", PRODUCT_KEY,
                "category", "test", "protocol", "MQTT", "status", "ENABLED"), adminToken, 200);
        productId = p.at("/data/id").asLong();
        call(HttpMethod.POST, "/products", Map.of("name", "dup", "productKey", PRODUCT_KEY), adminToken, 409);

        // invalid model (duplicate identifier) is rejected
        Map<String, Object> temp = Map.of("identifier", "temperature", "name", "温度", "dataType", "FLOAT", "unit", "℃",
                "accessMode", "R", "spec", Map.of("min", -40, "max", 125));
        call(HttpMethod.PUT, "/products/" + productId + "/thing-model",
                Map.of("properties", java.util.List.of(temp, temp)), adminToken, 400);

        Map<String, Object> model = Map.of("properties",
                java.util.List.of(temp,
                        Map.of("identifier", "switch", "name", "开关", "dataType", "BOOL", "accessMode", "RW")),
                "events",
                java.util.List.of(Map.of("identifier", "overheat", "name", "过热", "level", "WARNING")),
                "services", java.util.List.of(Map.of("identifier", "reboot", "name", "重启", "inputs",
                        java.util.List.of(Map.of("identifier", "delay", "name", "延迟", "dataType", "INT", "spec",
                                Map.of("min", 0, "max", 60))))));
        JsonNode saved = call(HttpMethod.PUT, "/products/" + productId + "/thing-model", model, adminToken, 200);
        assertThat(saved.at("/data/version").asInt()).isEqualTo(1);

        JsonNode d = call(HttpMethod.POST, "/devices",
                Map.of("name", "IT device", "productId", productId, "deviceKey", DEVICE_KEY), adminToken, 200);
        deviceId = d.at("/data/id").asLong();
        deviceSecret = d.at("/data/secret").asText();
        assertThat(deviceSecret).hasSizeGreaterThan(30);
        assertThat(d.at("/data/onlineStatus").asText()).isEqualTo("INACTIVE");

        // the secret is never returned again
        assertThat(call(HttpMethod.GET, "/devices/" + deviceId, null, adminToken, 200).toString())
                .doesNotContain(deviceSecret).doesNotContain("secret");
        assertThat(call(HttpMethod.GET, "/devices?keyword=" + DEVICE_KEY, null, adminToken, 200).toString())
                .doesNotContain(deviceSecret);

        call(HttpMethod.DELETE, "/products/" + productId, null, adminToken, 409);
    }

    @Test
    @Order(3)
    void deviceAuthenticationOverMqtt() throws Exception {
        assertThatThrownBy(() -> connectDevice("not-the-secret")).isInstanceOf(MqttException.class);
        MqttClient c = connectDevice(deviceSecret);
        assertThat(c.isConnected()).isTrue();
        c.disconnect();
        c.close();
    }

    @Test
    @Order(4)
    void propertyReportsAreValidatedStoredAndDeduplicated() throws Exception {
        MqttClient c = connectDevice(deviceSecret);
        publish(c, "status", Map.of("state", "online"));
        publish(c, "property/post", Map.of("id", "m1", "params", Map.of("temperature", 23.5)));
        publish(c, "property/post", Map.of("id", "m1", "params", Map.of("temperature", 23.5)));
        publish(c, "property/post", Map.of("id", "m2", "params", Map.of("temperature", 999)));
        publish(c, "property/post", Map.of("id", "m3", "params", Map.of("unknown", 1)));

        await().atMost(Duration.ofSeconds(15)).untilAsserted(() -> {
            JsonNode latest = call(HttpMethod.GET, "/devices/" + deviceId + "/latest", null, adminToken, 200);
            assertThat(latest.at("/data/0/identifier").asText()).isEqualTo("temperature");
            assertThat(latest.at("/data/0/value").asDouble()).isEqualTo(23.5);
        });
        await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> {
            JsonNode dev = call(HttpMethod.GET, "/devices/" + deviceId, null, adminToken, 200);
            assertThat(dev.at("/data/onlineStatus").asText()).isEqualTo("ONLINE");
            assertThat(dev.at("/data/mqttConnected").asBoolean()).isTrue();
        });
        JsonNode hist = call(HttpMethod.GET, "/devices/" + deviceId + "/history?identifiers=temperature", null,
                adminToken, 200);
        assertThat(hist.at("/data/total").asInt()).as("duplicate and invalid reports must not be stored").isEqualTo(1);
        JsonNode msgs = call(HttpMethod.GET, "/devices/" + deviceId + "/messages?size=20", null, adminToken, 200);
        assertThat(msgs.toString()).contains("INVALID");

        call(HttpMethod.GET, "/devices/" + deviceId + "/history?identifiers=temperature&start=2020-01-01T00:00:00Z",
                null, adminToken, 400);
        c.disconnect();
        c.close();
    }

    @Test
    @Order(5)
    void commandLifecycleWithDeviceReply() throws Exception {
        MqttClient c = connectDevice(deviceSecret);
        String base = "iot/" + PRODUCT_KEY + "/" + DEVICE_KEY + "/";
        c.subscribe(base + "command/down", 1, (topic, msg) -> {
            ObjectNode cmd = (ObjectNode) JSON.readTree(msg.getPayload());
            publish(c, "command/reply", Map.of("cmdId", cmd.get("cmdId").asText(), "status", "RECEIVED"));
            publish(c, "command/reply", Map.of("cmdId", cmd.get("cmdId").asText(), "status", "SUCCESS", "message",
                    "rebooted", "data", Map.of("ok", true)));
        });
        publish(c, "status", Map.of("state", "online"));
        await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> assertThat(
                call(HttpMethod.GET, "/devices/" + deviceId, null, adminToken, 200).at("/data/onlineStatus").asText())
                .isEqualTo("ONLINE"));

        call(HttpMethod.POST, "/devices/" + deviceId + "/commands",
                Map.of("type", "SERVICE", "identifier", "reboot", "params", Map.of("delay", 500)), adminToken, 400);
        call(HttpMethod.POST, "/devices/" + deviceId + "/commands",
                Map.of("type", "PROPERTY_SET", "identifier", "temperature", "params", Map.of("value", 1)), adminToken,
                400);

        JsonNode sent = call(HttpMethod.POST, "/devices/" + deviceId + "/commands",
                Map.of("type", "SERVICE", "identifier", "reboot", "params", Map.of("delay", 3)), adminToken, 200);
        long commandId = sent.at("/data/id").asLong();
        await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> {
            JsonNode cmd = call(HttpMethod.GET, "/commands/" + commandId, null, adminToken, 200);
            assertThat(cmd.at("/data/status").asText()).isEqualTo("SUCCESS");
            assertThat(cmd.at("/data/receivedAt").asText()).isNotBlank();
            assertThat(cmd.at("/data/result/ok").asBoolean()).isTrue();
        });
        c.disconnect();
        c.close();
    }

    @Test
    @Order(6)
    void thresholdAlarmRaisesDeduplicatesAndRecovers() throws Exception {
        call(HttpMethod.POST, "/alarm-rules", Map.of("name", "IT high temp " + SUFFIX, "scope", "DEVICE", "deviceId",
                deviceId, "ruleType", "THRESHOLD", "identifier", "temperature", "operator", "GT", "threshold", 50,
                "triggerCount", 1, "level", "CRITICAL"), adminToken, 200);
        MqttClient c = connectDevice(deviceSecret);
        publish(c, "property/post", Map.of("id", "a1", "params", Map.of("temperature", 80)));
        publish(c, "property/post", Map.of("id", "a2", "params", Map.of("temperature", 85)));
        await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> {
            JsonNode alarms = call(HttpMethod.GET, "/alarms?deviceId=" + deviceId + "&status=ACTIVE", null, adminToken,
                    200);
            assertThat(alarms.at("/data/total").asInt()).isEqualTo(1);
            assertThat(alarms.at("/data/records/0/triggerCount").asInt()).isGreaterThanOrEqualTo(2);
        });
        publish(c, "property/post", Map.of("id", "a3", "params", Map.of("temperature", 20)));
        await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> assertThat(call(HttpMethod.GET,
                "/alarms?deviceId=" + deviceId + "&status=RECOVERED", null, adminToken, 200).at("/data/total").asInt())
                .isEqualTo(1));
        JsonNode dash = call(HttpMethod.GET, "/dashboard/overview", null, adminToken, 200);
        assertThat(dash.at("/data/deviceCount").asInt()).isGreaterThanOrEqualTo(1);
        assertThat(dash.at("/data/todayAlarmCount").asInt()).isGreaterThanOrEqualTo(1);
        c.disconnect();
        c.close();
    }

    @Test
    @Order(7)
    void permissionsAreEnforcedOnTheServer() {
        JsonNode roles = call(HttpMethod.GET, "/system/roles", null, adminToken, 200);
        long viewerRole = 0;
        for (JsonNode r : roles.get("data")) {
            if ("VIEWER".equals(r.get("code").asText())) {
                viewerRole = r.get("id").asLong();
            }
        }
        String user = "viewer" + SUFFIX;
        String pw = "Viewer-Pass-" + SUFFIX;
        call(HttpMethod.POST, "/system/users",
                Map.of("username", user, "password", pw, "roleIds", java.util.List.of(viewerRole)), adminToken, 200);
        String vt = call(HttpMethod.POST, "/auth/login", Map.of("username", user, "password", pw), null, 200)
                .at("/data/token").asText();
        call(HttpMethod.GET, "/devices", null, vt, 200);
        call(HttpMethod.POST, "/devices/" + deviceId + "/commands",
                Map.of("type", "SERVICE", "identifier", "reboot", "params", Map.of("delay", 1)), vt, 403);
        call(HttpMethod.POST, "/devices/" + deviceId + "/rotate-secret", null, vt, 403);
        call(HttpMethod.DELETE, "/devices/" + deviceId, null, vt, 403);
        call(HttpMethod.GET, "/system/users", null, vt, 403);
        call(HttpMethod.POST, "/products", Map.of("name", "x", "productKey", "xxx-" + SUFFIX), vt, 403);

        // the operation log never contains the passwords that were submitted
        JsonNode log = call(HttpMethod.GET, "/system/logs/operation?size=100", null, adminToken, 200);
        assertThat(log.toString()).doesNotContain(pw).doesNotContain(deviceSecret).doesNotContain(ADMIN_PASSWORD);
    }

    @Test
    @Order(8)
    void rotatingTheSecretInvalidatesTheOldOne() throws Exception {
        String oldSecret = deviceSecret;
        deviceSecret = call(HttpMethod.POST, "/devices/" + deviceId + "/rotate-secret", null, adminToken, 200)
                .at("/data/secret").asText();
        assertThat(deviceSecret).isNotEqualTo(oldSecret);
        assertThatThrownBy(() -> connectDevice(oldSecret)).isInstanceOf(MqttException.class);
        MqttClient c = connectDevice(deviceSecret);
        assertThat(c.isConnected()).isTrue();
        c.disconnect();
        c.close();
        call(HttpMethod.PUT, "/devices/" + deviceId + "/enabled", Map.of("enabled", false), adminToken, 200);
        assertThatThrownBy(() -> connectDevice(deviceSecret)).isInstanceOf(MqttException.class);
        call(HttpMethod.POST, "/devices/" + deviceId + "/commands",
                Map.of("type", "SERVICE", "identifier", "reboot", "params", Map.of("delay", 1)), adminToken, 409);
    }
}
