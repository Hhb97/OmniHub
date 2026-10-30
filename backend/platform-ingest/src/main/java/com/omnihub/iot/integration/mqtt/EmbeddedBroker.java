package com.omnihub.iot.integration.mqtt;

import com.omnihub.iot.device.DeviceRuntime;
import com.omnihub.iot.device.DeviceRuntime.DeviceRef;
import io.moquette.broker.Server;
import io.moquette.broker.config.IConfig;
import io.moquette.broker.config.MemoryConfig;
import io.moquette.broker.security.IAuthenticator;
import io.moquette.broker.security.IAuthorizatorPolicy;
import io.moquette.broker.subscriptions.Topic;
import io.moquette.interception.AbstractInterceptHandler;
import io.moquette.interception.messages.InterceptConnectMessage;
import io.moquette.interception.messages.InterceptConnectionLostMessage;
import io.moquette.interception.messages.InterceptDisconnectMessage;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.List;
import java.util.Optional;
import java.util.Properties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import com.omnihub.iot.messaging.Topics;
import com.omnihub.iot.messaging.BrokerAdmin;

/**
 * In-process MQTT broker (Moquette) for local development and tests. It is NOT meant for production: use
 * EMQX / Mosquitto there (see docker-compose.yml) and leave iot.mqtt.embedded-broker.enabled=false.
 *
 * The platform stays in charge of identity: authentication and topic ACLs are delegated to {@link DeviceRuntime}.
 * Broker connect / disconnect callbacks feed the device's MQTT connection state.
 */
@Component
@ConditionalOnProperty(prefix = "iot.mqtt.embedded-broker", name = "enabled", havingValue = "true")
public class EmbeddedBroker implements BrokerAdmin {

    private static final Logger log = LoggerFactory.getLogger(EmbeddedBroker.class);

    private final MqttProperties props;
    private final DeviceRuntime runtime;
    private final Server server = new Server();

    public EmbeddedBroker(MqttProperties props, DeviceRuntime runtime) {
        this.props = props;
        this.runtime = runtime;
    }

    @PostConstruct
    void start() throws IOException {
        MqttProperties.EmbeddedBroker cfg = props.getEmbeddedBroker();
        Properties p = new Properties();
        p.setProperty(IConfig.HOST_PROPERTY_NAME, cfg.getHost());
        p.setProperty(IConfig.PORT_PROPERTY_NAME, String.valueOf(cfg.getPort()));
        p.setProperty(IConfig.ALLOW_ANONYMOUS_PROPERTY_NAME, "false");
        p.setProperty(IConfig.PERSISTENCE_ENABLED_PROPERTY_NAME, "false");
        p.setProperty(IConfig.ENABLE_TELEMETRY_NAME, "false");
        p.setProperty(IConfig.NETTY_MAX_BYTES_PROPERTY_NAME, "65536");
        server.startServer(new MemoryConfig(p), List.of(new ConnectionListener()), null, authenticator(), policy());
        log.info("Embedded MQTT broker listening on {}:{} (development only)", cfg.getHost(), cfg.getPort());
    }

    @PreDestroy
    void stop() {
        server.stopServer();
    }

    @Override
    public void disconnect(String clientId) {
        try {
            boolean dropped = server.disconnectClient(clientId);
            log.info("Disconnect requested for client {} -> {}", clientId, dropped);
        } catch (Exception e) {
            log.warn("Could not disconnect client {}: {}", clientId, e.getMessage());
        }
    }

    private boolean isPlatform(String username) {
        return username != null && username.equals(props.getPlatformUsername());
    }

    private IAuthenticator authenticator() {
        return (clientId, username, password) -> {
            String pwd = password == null ? null : new String(password, StandardCharsets.UTF_8);
            if (isPlatform(username)) {
                String expected = props.getPlatformPassword();
                return pwd != null && !expected.isEmpty()
                        && MessageDigest.isEqual(pwd.getBytes(StandardCharsets.UTF_8),
                                expected.getBytes(StandardCharsets.UTF_8));
            }
            boolean ok = runtime.authenticate(clientId, username, pwd);
            if (!ok) {
                log.warn("MQTT authentication rejected for client {}", clientId);
            }
            return ok;
        };
    }

    /** Devices may only publish to / read their own topics. The platform account is unrestricted. */
    private IAuthorizatorPolicy policy() {
        return new IAuthorizatorPolicy() {
            @Override
            public boolean canWrite(Topic topic, String user, String client) {
                if (isPlatform(user)) {
                    return true;
                }
                if (!isDeviceUser(user)) {
                    return false;
                }
                String t = topic.toString();
                String prefix = Topics.prefix(user);
                return t.startsWith(prefix) && Topics.UPLINK_KINDS.contains(t.substring(prefix.length()));
            }

            @Override
            public boolean canRead(Topic topic, String user, String client) {
                if (isPlatform(user)) {
                    return true;
                }
                return isDeviceUser(user) && topic.toString().equals(Topics.prefix(user) + "command/down");
            }
        };
    }

    private static boolean isDeviceUser(String user) {
        return user != null && user.indexOf('.') > 0 && user.indexOf('.') < user.length() - 1;
    }

    /** Broker-level connection events -> device mqtt_connected state. */
    private final class ConnectionListener extends AbstractInterceptHandler {

        @Override
        public String getID() {
            return "iot-connection-listener";
        }

        @Override
        public void onSessionLoopError(Throwable error) {
            log.warn("Embedded broker session loop error: {}", error.toString());
        }

        @Override
        public void onConnect(InterceptConnectMessage msg) {
            withDevice(msg.getClientID(), msg.getUsername(), ref -> runtime.markConnected(ref.id()));
        }

        @Override
        public void onDisconnect(InterceptDisconnectMessage msg) {
            withDevice(msg.getClientID(), msg.getUsername(), ref -> runtime.markDisconnected(ref.id()));
        }

        @Override
        public void onConnectionLost(InterceptConnectionLostMessage msg) {
            withDevice(msg.getClientID(), msg.getUsername(), ref -> runtime.markDisconnected(ref.id()));
        }

        private void withDevice(String clientId, String username, java.util.function.Consumer<DeviceRef> action) {
            if (isPlatform(username)) {
                return;
            }
            Optional<DeviceRef> ref = runtime.resolveClientId(clientId);
            ref.ifPresent(r -> {
                try {
                    action.accept(r);
                } catch (Exception e) {
                    log.warn("Handling connection event for {} failed: {}", clientId, e.getMessage());
                }
            });
        }
    }
}
