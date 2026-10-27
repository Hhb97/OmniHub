package com.omnihub.iot.telemetry;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.omnihub.iot.config.IotProperties;
import com.omnihub.iot.telemetry.HistoryStore.Sample;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** InfluxDB writes are batched: line protocol, many points per HTTP request. Uses a local stub HTTP server. */
class InfluxHistoryStoreTest {

    private record Req(String query, String auth, String body) {
    }

    private static final ObjectMapper JSON = new ObjectMapper();
    private final List<Req> requests = Collections.synchronizedList(new ArrayList<>());
    private HttpServer server;
    private volatile int status = 204;
    private InfluxHistoryStore store;
    private IotProperties props;

    @BeforeEach
    void start() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/write", ex -> {
            String body = new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            requests.add(new Req(ex.getRequestURI().getQuery(), ex.getRequestHeaders().getFirst("Authorization"), body));
            ex.sendResponseHeaders(status, -1);
            ex.close();
        });
        server.start();
        props = new IotProperties();
        props.getInfluxdb().setUrl("http://127.0.0.1:" + server.getAddress().getPort());
        props.getInfluxdb().setDatabase("iot");
        props.getInfluxdb().setCreateDatabase(false);
        store = new InfluxHistoryStore(props);
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    private static Sample num(long dev, String id, double v, long ms) throws Exception {
        return new Sample(dev, id, JSON.readTree(String.valueOf(v)), Instant.ofEpochMilli(ms));
    }

    @Test
    void manySamplesGoOutInFewRequestsInLineProtocol() throws Exception {
        List<Sample> samples = new ArrayList<>();
        for (int i = 0; i < 12_000; i++) {
            samples.add(num(i % 50, "temperature", 20 + (i % 10) / 10.0, 1_700_000_000_000L + i));
        }
        store.writeBatch(samples);

        assertThat(requests).hasSize(3); // 5000 + 5000 + 2000 points
        assertThat(requests.stream().mapToInt(r -> r.body().split("\n").length).sum()).isEqualTo(12_000);
        Req first = requests.get(0);
        assertThat(first.query()).contains("db=iot").contains("precision=ms").doesNotContain("rp=");
        assertThat(first.body().split("\n")[0])
                .isEqualTo("device_property,device_id=0,identifier=temperature num=20.0,json=\"20.0\" 1700000000000");
    }

    @Test
    void booleansAreNumericAndStringsAreEscapedAndNonNumericHaveNoNumField() throws Exception {
        store.writeBatch(List.of(
                new Sample(1L, "switch", JSON.readTree("true"), Instant.ofEpochMilli(1000)),
                new Sample(1L, "label", JSON.readTree("\"a \\\"b\\\" c\""), Instant.ofEpochMilli(2000))));
        String[] lines = requests.get(0).body().split("\n");
        assertThat(lines[0]).isEqualTo("device_property,device_id=1,identifier=switch num=1.0,json=\"true\" 1000");
        // JSON text "a \"b\" c" in line protocol: every backslash and quote of the text is escaped once more
        String expected = "device_property,device_id=1,identifier=label json=\"" + "\\\"a " + "\\\\\\\"b\\\\\\\"" + " c\\\"" + "\" 2000";
        assertThat(lines[1]).isEqualTo(expected).doesNotContain("num=");
    }

    @Test
    void invalidIdentifiersAreSkippedSoTheyCannotInjectLineProtocol() throws Exception {
        store.writeBatch(List.of(num(1, "bad id,x=1", 1, 1000), num(1, "ok", 2, 2000)));
        assertThat(requests).hasSize(1);
        assertThat(requests.get(0).body().split("\n")).hasSize(1).allMatch(l -> l.contains("identifier=ok "));
    }

    @Test
    void retentionPolicyAndBasicAuthAreSent() throws Exception {
        props.getInfluxdb().setRetentionPolicy("iot_90d");
        props.getInfluxdb().setUsername("u");
        props.getInfluxdb().setPassword("p");
        store.writeBatch(List.of(num(1, "t", 1, 1000)));
        assertThat(requests.get(0).query()).contains("rp=iot_90d");
        assertThat(requests.get(0).auth()).isEqualTo("Basic dTpw");
    }

    @Test
    void aFailedWriteIsReportedSoTheCallerCanRetryOrLogIt() throws Exception {
        status = 500;
        assertThatThrownBy(() -> store.writeBatch(List.of(num(1, "t", 1, 1000))))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("HTTP 500");
    }

    @Test
    void emptyBatchSendsNothing() {
        store.writeBatch(List.of());
        assertThat(requests).isEmpty();
    }
}
