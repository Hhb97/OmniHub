package com.omnihub.iot.config;

import static org.assertj.core.api.Assertions.assertThat;

import com.omnihub.iot.alarm.AlarmProperties;
import com.omnihub.iot.dashboard.DashboardProperties;
import com.omnihub.iot.device.DeviceProperties;
import com.omnihub.iot.identity.core.SecurityProperties;
import com.omnihub.iot.identity.web.MetricsProperties;
import com.omnihub.iot.integration.mqtt.IngestProperties;
import com.omnihub.iot.integration.mqtt.MqttProperties;
import com.omnihub.iot.integration.ws.WsProperties;
import com.omnihub.iot.system.AdminProperties;
import com.omnihub.iot.telemetry.InfluxProperties;
import com.omnihub.iot.telemetry.TelemetryProperties;
import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.bind.PropertySourcesPlaceholdersResolver;
import org.springframework.boot.context.properties.source.ConfigurationPropertySources;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.MutablePropertySources;
import org.springframework.core.env.PropertySource;
import org.springframework.core.io.ClassPathResource;

/**
 * Every module owns the configuration class of its own settings, but the keys in application.yml (and with them the
 * IOT_* environment variables of docker-compose.yml, .env and users' application-local*.yml) did not change when
 * the single IotProperties class was split. This binds each class from the real application.yml with non-default
 * environment values, so a moved key, a wrong prefix or two classes sharing a prefix (iot, iot.ingest) fail here
 * instead of silently falling back to a default in production.
 */
class ConfigurationBindingTest {

    /** alternating names and values: Map.of stops at ten pairs */
    private static Map<String, Object> env(String... namesAndValues) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i < namesAndValues.length; i += 2) {
            m.put(namesAndValues[i], namesAndValues[i + 1]);
        }
        return m;
    }

    private Binder binderWith(Map<String, Object> env) throws IOException {
        List<PropertySource<?>> yml = new YamlPropertySourceLoader().load("application", new ClassPathResource("application.yml"));
        MutablePropertySources sources = new MutablePropertySources();
        sources.addFirst(new MapPropertySource("env", env));
        yml.forEach(sources::addLast);
        // placeholders such as ${IOT_BATCH_WORKERS:4} are resolved against the environment, as Spring Boot does at startup
        return new Binder(ConfigurationPropertySources.from(sources), new PropertySourcesPlaceholdersResolver(sources));
    }

    @Test
    void ingestKeysReachTheirOwningModuleEvenThoughThreeClassesShareThePrefix() throws IOException {
        Binder b = binderWith(env("IOT_INGEST_MODE", "kafka", "IOT_INGEST_LANES", "12", "IOT_LANE_OVERFLOW", "drop",
                "IOT_KAFKA_TOPIC", "uplink-x", "IOT_KAFKA_PARTITIONS", "3",
                "IOT_BATCH_HISTORY_WORKERS", "7", "IOT_BATCH_LINGER_MS", "25", "IOT_LOG_OK_PROPERTY", "true",
                "IOT_LATEST_MIN_INTERVAL_MS", "500",
                "IOT_INGEST_DB_POOL", "33", "IOT_INGEST_DB_TIMEOUT_MS", "1500", "IOT_INGEST_SYNCHRONOUS_COMMIT", "on"));

        IngestProperties ingest = b.bind("iot.ingest", Bindable.of(IngestProperties.class)).get();
        assertThat(ingest.getMode()).isEqualTo("kafka");
        assertThat(ingest.getLanes()).isEqualTo(12);
        assertThat(ingest.getLaneOverflow()).isEqualTo("drop");
        assertThat(ingest.getKafka().getTopic()).isEqualTo("uplink-x");
        assertThat(ingest.getKafka().getPartitions()).isEqualTo(3);

        TelemetryProperties telemetry = b.bind("iot.ingest", Bindable.of(TelemetryProperties.class)).get();
        assertThat(telemetry.getBatch().getHistoryWorkers()).isEqualTo(7);
        assertThat(telemetry.getBatch().getLingerMs()).isEqualTo(25);
        assertThat(telemetry.getBatch().isLogOkProperty()).isTrue();
        assertThat(telemetry.getLatestMinIntervalMs()).isEqualTo(500);

        IngestDbProperties db = b.bind("iot.ingest", Bindable.of(IngestDbProperties.class)).get();
        assertThat(db.getDbPool()).isEqualTo(33);
        assertThat(db.getDbTimeoutMs()).isEqualTo(1500);
        assertThat(db.getSynchronousCommit()).isEqualTo("on");
    }

    @Test
    void defaultsMatchTheMeasuredProfile() throws IOException {
        Binder b = binderWith(env());
        assertThat(b.bind("iot.ingest", Bindable.of(TelemetryProperties.class)).get().getBatch().getHistoryWorkers())
                .isEqualTo(4);
        assertThat(b.bind("iot.ingest", Bindable.of(IngestProperties.class)).get().getMode()).isEqualTo("direct");
        assertThat(b.bind("iot.mqtt", Bindable.of(MqttProperties.class)).get().getIngestConnections()).isEqualTo(16);
        assertThat(b.bind("iot.ingest", Bindable.of(IngestDbProperties.class)).get().getSynchronousCommit()).isEqualTo("off");
    }

    @Test
    void mqttSecurityAdminAndMetricsKeysBind() throws IOException {
        Binder b = binderWith(env("IOT_MQTT_BROKER_URL", "tcp://emqx:1883", "IOT_MQTT_USERNAME", "svc",
                "IOT_MQTT_INGEST_CONNECTIONS", "4", "IOT_MQTT_SHARED_GROUP", "g2",
                "IOT_JWT_SECRET", "s3cret-s3cret-s3cret-s3cret-s3cret!!", "IOT_JWT_TTL_MINUTES", "30",
                "IOT_ADMIN_USERNAME", "root", "IOT_METRICS_TOKEN", "tok"));
        MqttProperties mqtt = b.bind("iot.mqtt", Bindable.of(MqttProperties.class)).get();
        assertThat(mqtt.getBrokerUrl()).isEqualTo("tcp://emqx:1883");
        assertThat(mqtt.getPlatformUsername()).isEqualTo("svc");
        assertThat(mqtt.getIngestConnections()).isEqualTo(4);
        assertThat(mqtt.getSharedGroup()).isEqualTo("g2");
        assertThat(mqtt.getEmbeddedBroker().isEnabled()).isFalse();

        SecurityProperties sec = b.bind("iot.security", Bindable.of(SecurityProperties.class)).get();
        assertThat(sec.getJwtSecret()).startsWith("s3cret");
        assertThat(sec.getJwtTtlMinutes()).isEqualTo(30);
        assertThat(b.bind("iot.admin", Bindable.of(AdminProperties.class)).get().getUsername()).isEqualTo("root");
        assertThat(b.bind("iot.metrics", Bindable.of(MetricsProperties.class)).get().getToken()).isEqualTo("tok");
    }

    @Test
    void topLevelKeysStayDirectlyUnderIot() throws IOException {
        Binder b = binderWith(env("IOT_CORS_ORIGINS", "https://a.example,https://b.example",
                "IOT_DASHBOARD_CACHE_SECONDS", "0"));
        assertThat(b.bind("iot", Bindable.of(CorsProperties.class)).get().getCorsAllowedOrigins())
                .isEqualTo("https://a.example,https://b.example");
        DashboardProperties dash = b.bind("iot", Bindable.of(DashboardProperties.class)).get();
        assertThat(dash.getDashboardCacheSeconds()).isZero();
        assertThat(dash.getDashboardZone()).isEqualTo("UTC");
    }

    @Test
    void deviceAlarmAndWebSocketKeysBind() throws IOException {
        Binder b = binderWith(env("IOT_AUTH_CACHE_MAX", "10", "IOT_ALARM_QUEUE_CAPACITY", "7",
                "IOT_WS_COALESCE_MILLIS", "0", "IOT_INFLUX_URL", "http://influxdb:8086", "IOT_INFLUX_RP", "rp1"));
        assertThat(b.bind("iot.device", Bindable.of(DeviceProperties.class)).get().getAuthCacheMax()).isEqualTo(10);
        assertThat(b.bind("iot.alarm", Bindable.of(AlarmProperties.class)).get().getQueueCapacity()).isEqualTo(7);
        assertThat(b.bind("iot.ws", Bindable.of(WsProperties.class)).get().getCoalesceMillis()).isZero();
        InfluxProperties influx = b.bind("iot.influxdb", Bindable.of(InfluxProperties.class)).get();
        assertThat(influx.getUrl()).isEqualTo("http://influxdb:8086");
        assertThat(influx.getRetentionPolicy()).isEqualTo("rp1");
    }
}
