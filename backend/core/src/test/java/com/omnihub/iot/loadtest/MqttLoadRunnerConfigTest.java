package com.omnihub.iot.loadtest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.omnihub.iot.loadtest.MqttLoadRunner.Config;
import com.omnihub.iot.loadtest.MqttLoadRunner.Device;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class MqttLoadRunnerConfigTest {

    @TempDir
    Path dir;

    private Path props(String text) throws Exception {
        Path f = dir.resolve("loadtest.properties");
        Files.writeString(f, text);
        return f;
    }

    @Test
    void settingsComeFromTheFileAndCommandLineOverridesThem() throws Exception {
        Path f = props("""
                broker=mqtt://emqx.example:1883
                devices=D:/tmp/devices.json
                rate=0.2
                ramp=50
                report=1
                """);
        Config c = Config.parse(new String[] { "--config", f.toString(), "--rate", "2" });
        assertThat(c.broker()).isEqualTo("mqtt://emqx.example:1883");
        assertThat(c.devicesFile()).isEqualTo("D:/tmp/devices.json");
        assertThat(c.ramp()).isEqualTo(50);
        assertThat(c.reportSeconds()).isEqualTo(1);
        assertThat(c.rate()).as("command line wins over the file").isEqualTo(2.0);
        assertThat(c.qos()).as("default when neither file nor command line set it").isEqualTo(1);
    }

    @Test
    void missingExplicitConfigFileIsAnError() {
        assertThatThrownBy(() -> Config.parse(new String[] { "--config", dir.resolve("nope.properties").toString() }))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("config file not found");
    }

    @Test
    void brokerFallsBackToTheUrlInTheDeviceFileAndIgnoresPlaceholders() {
        Config none = new Config("d.json", null, null, 1, 0, 0, 0, 1, 1, 0, 200, true, null, 5);
        assertThat(MqttLoadRunner.resolveBroker(none, List.of(new Device(1, "p", "d1", "s", "mqtt://<host>:1883"),
                new Device(2, "p", "d2", "s", "mqtt://10.0.0.5:1883")))).isEqualTo("mqtt://10.0.0.5:1883");
        assertThat(MqttLoadRunner.resolveBroker(none, List.of(new Device(1, "p", "d1", "s", null))))
                .isEqualTo("mqtt://localhost:1883");
        Config set = new Config("d.json", null, "mqtt://explicit:1883", 1, 0, 0, 0, 1, 1, 0, 200, true, null, 5);
        assertThat(MqttLoadRunner.resolveBroker(set, List.of(new Device(1, "p", "d1", "s", "mqtt://10.0.0.5:1883"))))
                .isEqualTo("mqtt://explicit:1883");
    }
}
