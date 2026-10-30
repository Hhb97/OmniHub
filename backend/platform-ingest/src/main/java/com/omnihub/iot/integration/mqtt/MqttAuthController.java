package com.omnihub.iot.integration.mqtt;

import com.omnihub.iot.device.DeviceRuntime;
import com.omnihub.iot.messaging.Topics;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * HTTP authentication / authorization hooks for an EXTERNAL broker (EMQX HTTP authn + authz). The broker calls
 * these endpoints; they are protected by a shared token (header X-Broker-Token) instead of a user JWT.
 * Not used with the embedded development broker. This path has not been exercised against a real EMQX in this
 * repository's test run (no Docker available) — see docs/mqtt-protocol.md.
 */
@RestController
@RequestMapping("/api/mqtt")
public class MqttAuthController {

    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(MqttAuthController.class);

    public record AuthRequest(String clientid, String username, String password) {
    }

    public record AclRequest(String clientid, String username, String topic, String action) {
    }

    private static final long LOG_EVERY_MS = 10_000;

    private final DeviceRuntime runtime;
    private final MqttProperties props;
    private final MeterRegistry registry;
    private final Map<String, Counter> counters = new ConcurrentHashMap<>();
    /** last time a refusal with this reason was logged: one sample per reason every few seconds, never a flood */
    private final Map<String, Long> lastLogged = new ConcurrentHashMap<>();

    public MqttAuthController(DeviceRuntime runtime, MqttProperties props, MeterRegistry registry) {
        this.runtime = runtime;
        this.props = props;
        this.registry = registry;
    }

    /** Counts the outcome (iot_mqtt_auth_total / iot_mqtt_acl_total) and logs a sample of every refusal reason. */
    private void count(String kind, boolean allow, String reason, String sample) {
        String result = allow ? "allow" : "deny";
        counters.computeIfAbsent(kind + "|" + result + "|" + reason, k -> Counter.builder("iot_mqtt_" + kind + "_total")
                .description("Broker " + kind + " hook decisions by result and reason").tag("result", result)
                .tag("reason", reason).register(registry)).increment();
        if (!allow) {
            long now = System.currentTimeMillis();
            Long last = lastLogged.get(kind + reason);
            if ((last == null || now - last > LOG_EVERY_MS) && lastLogged.put(kind + reason, now) == last) {
                log.warn("MQTT {} refused: reason={} {} (one sample per {} s and reason; see iot_mqtt_{}_total)", kind,
                        reason, sample, LOG_EVERY_MS / 1000, kind);
            }
        }
    }

    @PostMapping("/auth")
    public Map<String, String> auth(@RequestHeader(value = "X-Broker-Token", required = false) String token,
            @RequestBody AuthRequest req) {
        checkToken(token);
        if (req.username() != null && req.username().equals(props.getPlatformUsername())) {
            String expected = props.getPlatformPassword();
            boolean ok = !expected.isEmpty() && req.password() != null && MessageDigest.isEqual(
                    req.password().getBytes(StandardCharsets.UTF_8), expected.getBytes(StandardCharsets.UTF_8));
            count("auth", ok, ok ? "platform" : "platform_bad_password", "username=" + req.username());
            return Map.of("result", ok ? "allow" : "deny");
        }
        DeviceRuntime.AuthResult r = runtime.check(req.clientid(), req.username(), req.password());
        boolean ok = r == DeviceRuntime.AuthResult.ALLOW;
        // never the user name or the password themselves: their lengths are enough to tell "empty" from "wrong"
        count("auth", ok, ok ? "device" : r.reason(), "clientid=" + req.clientid() + " usernameEqualsClientid="
                + (req.clientid() != null && req.clientid().equals(req.username())) + " usernameLength="
                + (req.username() == null ? -1 : req.username().length()) + " passwordLength="
                + (req.password() == null ? -1 : req.password().length()));
        return Map.of("result", ok ? "allow" : "deny");
    }

    @PostMapping("/acl")
    public Map<String, String> acl(@RequestHeader(value = "X-Broker-Token", required = false) String token,
            @RequestBody AclRequest req) {
        checkToken(token);
        String user = req.username();
        if (user == null) {
            count("acl", false, "no_username", "clientid=" + req.clientid());
            return Map.of("result", "deny");
        }
        if (user.equals(props.getPlatformUsername())) {
            count("acl", true, "platform", "");
            return Map.of("result", "allow");
        }
        if (user.indexOf('.') <= 0 || req.topic() == null) {
            count("acl", false, "malformed", "username=" + user + " topic=" + req.topic());
            return Map.of("result", "deny");
        }
        String prefix = Topics.prefix(user);
        boolean ok;
        if ("publish".equals(req.action())) {
            ok = req.topic().startsWith(prefix) && Topics.UPLINK_KINDS.contains(req.topic().substring(prefix.length()));
        } else {
            ok = req.topic().equals(prefix + "command/down");
        }
        count("acl", ok, ok ? "device" : "topic_not_allowed", "username=" + user + " action=" + req.action() + " topic="
                + req.topic());
        return Map.of("result", ok ? "allow" : "deny");
    }

    private void checkToken(String token) {
        String expected = props.getHttpAuthToken();
        if (expected.isEmpty() || token == null || !MessageDigest.isEqual(token.getBytes(StandardCharsets.UTF_8),
                expected.getBytes(StandardCharsets.UTF_8))) {
            log.warn("Broker hook rejected: header X-Broker-Token is {} (must equal iot.mqtt.http-auth-token)",
                    expected.isEmpty() ? "irrelevant, server token is not configured" : token == null ? "missing" : "wrong");
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED);
        }
    }
}
