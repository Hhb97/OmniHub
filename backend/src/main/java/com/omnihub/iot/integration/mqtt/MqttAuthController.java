package com.omnihub.iot.integration.mqtt;

import com.omnihub.iot.config.IotProperties;
import com.omnihub.iot.device.DeviceRuntime;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Map;
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

    private final DeviceRuntime runtime;
    private final IotProperties props;

    public MqttAuthController(DeviceRuntime runtime, IotProperties props) {
        this.runtime = runtime;
        this.props = props;
    }

    @PostMapping("/auth")
    public Map<String, String> auth(@RequestHeader(value = "X-Broker-Token", required = false) String token,
            @RequestBody AuthRequest req) {
        checkToken(token);
        if (req.username() != null && req.username().equals(props.getMqtt().getPlatformUsername())) {
            String expected = props.getMqtt().getPlatformPassword();
            boolean ok = !expected.isEmpty() && req.password() != null && MessageDigest.isEqual(
                    req.password().getBytes(StandardCharsets.UTF_8), expected.getBytes(StandardCharsets.UTF_8));
            return Map.of("result", ok ? "allow" : "deny");
        }
        return Map.of("result", runtime.authenticate(req.clientid(), req.username(), req.password()) ? "allow" : "deny");
    }

    @PostMapping("/acl")
    public Map<String, String> acl(@RequestHeader(value = "X-Broker-Token", required = false) String token,
            @RequestBody AclRequest req) {
        checkToken(token);
        String user = req.username();
        if (user == null) {
            return Map.of("result", "deny");
        }
        if (user.equals(props.getMqtt().getPlatformUsername())) {
            return Map.of("result", "allow");
        }
        if (user.indexOf('.') <= 0 || req.topic() == null) {
            return Map.of("result", "deny");
        }
        String prefix = Topics.prefix(user);
        boolean ok;
        if ("publish".equals(req.action())) {
            ok = req.topic().startsWith(prefix) && Topics.UPLINK_KINDS.contains(req.topic().substring(prefix.length()));
        } else {
            ok = req.topic().equals(prefix + "command/down");
        }
        return Map.of("result", ok ? "allow" : "deny");
    }

    private void checkToken(String token) {
        String expected = props.getMqtt().getHttpAuthToken();
        if (expected.isEmpty() || token == null || !MessageDigest.isEqual(token.getBytes(StandardCharsets.UTF_8),
                expected.getBytes(StandardCharsets.UTF_8))) {
            log.warn("Broker hook rejected: header X-Broker-Token is {} (must equal iot.mqtt.http-auth-token)",
                    expected.isEmpty() ? "irrelevant, server token is not configured" : token == null ? "missing" : "wrong");
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED);
        }
    }
}
