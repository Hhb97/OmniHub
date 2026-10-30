package com.omnihub.iot.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.MutablePropertySources;
import org.springframework.core.env.PropertySource;
import org.springframework.core.env.PropertySourcesPropertyResolver;
import org.springframework.core.io.ClassPathResource;

/**
 * The Docker deployment passes the InfluxDB settings as IOT_INFLUX_* environment variables. They only reach the
 * application if application.yml maps them: without the mapping the backend silently used http://localhost:8086
 * and every history write failed.
 *
 * <p>This test lives in the module that owns application.yml (core), so it keeps working no matter how the code is
 * split into modules.
 */
class ApplicationYamlTest {

    private PropertySourcesPropertyResolver resolverWith(Map<String, Object> env) throws IOException {
        List<PropertySource<?>> yml = new YamlPropertySourceLoader().load("application", new ClassPathResource("application.yml"));
        MutablePropertySources sources = new MutablePropertySources();
        sources.addFirst(new MapPropertySource("env", env));
        yml.forEach(sources::addLast);
        return new PropertySourcesPropertyResolver(sources);
    }

    @Test
    void influxSettingsComeFromTheEnvironmentVariablesOfTheComposeFile() throws IOException {
        PropertySourcesPropertyResolver r = resolverWith(Map.of("IOT_INFLUX_URL", "http://influxdb:8086",
                "IOT_INFLUX_DB", "metrics", "IOT_INFLUX_USER", "writer", "IOT_INFLUX_PASSWORD", "pw",
                "IOT_INFLUX_CREATE_DB", "true", "IOT_INFLUX_RETENTION_DAYS", "90", "IOT_HISTORY_STORE", "influxdb"));
        assertThat(r.getProperty("iot.influxdb.url")).isEqualTo("http://influxdb:8086");
        assertThat(r.getProperty("iot.influxdb.database")).isEqualTo("metrics");
        assertThat(r.getProperty("iot.influxdb.username")).isEqualTo("writer");
        assertThat(r.getProperty("iot.influxdb.password")).isEqualTo("pw");
        assertThat(r.getProperty("iot.influxdb.create-database")).isEqualTo("true");
        assertThat(r.getProperty("iot.influxdb.retention-days")).isEqualTo("90");
        assertThat(r.getProperty("iot.history.store")).isEqualTo("influxdb");
    }

    @Test
    void defaultsKeepPostgresAndDoNotTouchInfluxDb() throws IOException {
        PropertySourcesPropertyResolver r = resolverWith(Map.of());
        assertThat(r.getProperty("iot.history.store")).isEqualTo("postgres");
        assertThat(r.getProperty("iot.influxdb.create-database")).isEqualTo("false");
    }

    @Test
    void platformMqttClientUsesACleanSessionByDefault() throws IOException {
        assertThat(resolverWith(Map.of()).getProperty("iot.mqtt.clean-session")).isEqualTo("true");
    }
}
