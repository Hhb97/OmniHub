package com.omnihub.iot.telemetry;

import com.fasterxml.jackson.databind.JsonNode;
import com.omnihub.iot.common.Jsons;
import com.omnihub.iot.config.IotProperties;
import com.omnihub.iot.thingmodel.ValueValidator;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import jakarta.annotation.PostConstruct;
import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * InfluxDB 1.x (tested target: 1.8) history store over the HTTP API.
 * Measurement `device_property`; tags: device_id, identifier; fields: num (float, numeric/boolean values only)
 * and json (the value as JSON text). Writes use line protocol with millisecond precision.
 * Expiry is delegated to an InfluxDB retention policy (see docs/data-model.md).
 *
 * Not exercised against a real InfluxDB in this repository's test run: no instance was available.
 */
@Component
@ConditionalOnProperty(prefix = "iot.history", name = "store", havingValue = "influxdb")
public class InfluxHistoryStore implements HistoryStore {

    private static final Logger log = LoggerFactory.getLogger(InfluxHistoryStore.class);
    private static final String M = "device_property";
    private static final Pattern IDENT = Pattern.compile("^[a-zA-Z][a-zA-Z0-9_]{0,63}$");

    private final IotProperties.Influxdb cfg;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();

    /** build = line protocol assembly per request, http = the POST until the response; they split a flush's time. */
    private final Timer buildTimer;
    private final Timer httpTimer;
    private final Counter requestsOk;
    private final Counter requestsError;
    private final Counter pointsCounter;

    public InfluxHistoryStore(IotProperties props, MeterRegistry meters) {
        this.cfg = props.getInfluxdb();
        this.buildTimer = Timer.builder("iot_influx_write_seconds").description("InfluxDB history write, per request")
                .tag("phase", "build").publishPercentileHistogram().register(meters);
        this.httpTimer = Timer.builder("iot_influx_write_seconds").tag("phase", "http").publishPercentileHistogram()
                .register(meters);
        this.requestsOk = Counter.builder("iot_influx_write_requests_total").tag("result", "ok").register(meters);
        this.requestsError = Counter.builder("iot_influx_write_requests_total").tag("result", "error")
                .register(meters);
        this.pointsCounter = Counter.builder("iot_influx_write_points_total").description("Points accepted by InfluxDB")
                .register(meters);
    }

    @PostConstruct
    void init() {
        log.info("InfluxDB history store: {} database '{}' (user {})", cfg.getUrl(), cfg.getDatabase(),
                cfg.getUsername().isBlank() ? "none" : cfg.getUsername());
        if (cfg.isCreateDatabase()) {
            try {
                query("POST", "CREATE DATABASE " + quoteIdent(cfg.getDatabase()));
                if (cfg.getRetentionDays() > 0) {
                    String rp = "iot_" + cfg.getRetentionDays() + "d";
                    query("POST", "CREATE RETENTION POLICY " + quoteIdent(rp) + " ON " + quoteIdent(cfg.getDatabase())
                            + " DURATION " + cfg.getRetentionDays() + "d REPLICATION 1 DEFAULT");
                }
                log.info("InfluxDB database '{}' ready at {}", cfg.getDatabase(), cfg.getUrl());
            } catch (Exception e) {
                log.warn("Could not prepare InfluxDB database '{}': {}", cfg.getDatabase(), e.getMessage());
            }
        }
    }

    private static final int LINES_PER_REQUEST = 5000;

    /** Line protocol, millisecond precision; one HTTP request per 5000 points instead of one per message. */
    @Override
    public void writeBatch(List<Sample> samples) {
        StringBuilder body = new StringBuilder();
        int lines = 0;
        long buildStart = System.nanoTime();
        for (Sample s : samples) {
            if (!IDENT.matcher(s.identifier()).matches()) {
                continue;
            }
            Double num = ValueValidator.numeric(s.value());
            body.append(M).append(",device_id=").append(s.deviceId()).append(",identifier=").append(s.identifier())
                    .append(' ');
            if (num != null) {
                body.append("num=").append(num).append(',');
            }
            body.append("json=\"").append(Jsons.toJson(s.value()).replace("\\", "\\\\").replace("\"", "\\\""))
                    .append("\" ").append(s.ts().toEpochMilli()).append('\n');
            if (++lines >= LINES_PER_REQUEST) {
                buildTimer.record(System.nanoTime() - buildStart, TimeUnit.NANOSECONDS);
                post(body.toString(), lines);
                body.setLength(0);
                lines = 0;
                buildStart = System.nanoTime();
            }
        }
        if (lines > 0) {
            buildTimer.record(System.nanoTime() - buildStart, TimeUnit.NANOSECONDS);
            post(body.toString(), lines);
        }
    }

    private void post(String body, int points) {
        String url = cfg.getUrl() + "/write?db=" + enc(cfg.getDatabase()) + "&precision=ms"
                + (cfg.getRetentionPolicy().isBlank() ? "" : "&rp=" + enc(cfg.getRetentionPolicy()));
        long t0 = System.nanoTime();
        boolean ok = false;
        try {
            HttpResponse<String> r = http.send(auth(HttpRequest.newBuilder(URI.create(url))
                    .timeout(Duration.ofSeconds(10)).POST(HttpRequest.BodyPublishers.ofString(body))).build(),
                    HttpResponse.BodyHandlers.ofString());
            if (r.statusCode() != 204) {
                throw new IllegalStateException("InfluxDB write failed: HTTP " + r.statusCode() + " " + r.body());
            }
            ok = true;
            pointsCounter.increment(points);
        } catch (IOException | InterruptedException e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            throw new IllegalStateException("InfluxDB write failed (" + url + "): " + describe(e), e);
        } finally {
            httpTimer.record(System.nanoTime() - t0, TimeUnit.NANOSECONDS);
            (ok ? requestsOk : requestsError).increment();
        }
    }

    /** Exception class and message down the cause chain: IOExceptions often have no message of their own. */
    static String describe(Throwable e) {
        StringBuilder sb = new StringBuilder();
        for (Throwable t = e; t != null && sb.length() < 400; t = t.getCause() == t ? null : t.getCause()) {
            if (sb.length() > 0) {
                sb.append(" <- ");
            }
            sb.append(t.getClass().getSimpleName());
            if (t.getMessage() != null) {
                sb.append(": ").append(t.getMessage());
            }
        }
        return sb.toString();
    }

    @Override
    public Page page(Long deviceId, List<String> ids, Instant from, Instant to, int size, int offset) {
        String cond = condition(deviceId, ids, from, to);
        JsonNode count = firstSeries(query("GET", "SELECT count(\"json\") FROM " + M + " WHERE " + cond));
        long total = count == null ? 0 : count.at("/values/0/1").asLong();
        List<Row> rows = new ArrayList<>();
        JsonNode s = firstSeries(query("GET", "SELECT \"identifier\", \"json\" FROM " + M + " WHERE " + cond
                + " ORDER BY time DESC LIMIT " + size + " OFFSET " + offset));
        if (s != null) {
            for (JsonNode v : s.get("values")) {
                rows.add(new Row(v.get(1).asText(), Jsons.parse(v.get(2).asText()),
                        Instant.ofEpochMilli(v.get(0).asLong())));
            }
        }
        return new Page(rows, total);
    }

    @Override
    public List<List<Object>> series(Long deviceId, String id, Instant from, Instant to, long bucket, int maxPoints) {
        String cond = condition(deviceId, List.of(id), from, to);
        List<List<Object>> points = new ArrayList<>();
        if (bucket == 0) {
            JsonNode s = firstSeries(query("GET", "SELECT \"json\" FROM " + M + " WHERE " + cond
                    + " ORDER BY time DESC LIMIT " + maxPoints));
            if (s != null) {
                for (JsonNode v : s.get("values")) {
                    points.add(List.of(v.get(0).asLong(), Jsons.parse(v.get(1).asText())));
                }
            }
            Collections.reverse(points);
        } else {
            JsonNode s = firstSeries(query("GET", "SELECT mean(\"num\") FROM " + M + " WHERE " + cond
                    + " GROUP BY time(" + bucket + "s) fill(none)"));
            if (s != null) {
                for (JsonNode v : s.get("values")) {
                    if (!v.get(1).isNull()) {
                        points.add(List.of(v.get(0).asLong(), Math.round(v.get(1).asDouble() * 1000.0) / 1000.0));
                    }
                }
            }
        }
        return points;
    }

    @Override
    public void purgeOlderThan(int days) {
        // expiry is handled by the InfluxDB retention policy
    }

    // ------------------------------------------------------------------ helpers

    private static String condition(Long deviceId, List<String> ids, Instant from, Instant to) {
        List<String> or = new ArrayList<>();
        for (String id : ids) {
            if (!IDENT.matcher(id).matches()) {
                throw new IllegalArgumentException("invalid identifier");
            }
            or.add("\"identifier\"='" + id + "'");
        }
        return "\"device_id\"='" + deviceId.longValue() + "' AND (" + String.join(" OR ", or) + ") AND time >= '" + from
                + "' AND time < '" + to + "'";
    }

    private JsonNode query(String method, String q) {
        String url = cfg.getUrl() + "/query?db=" + enc(cfg.getDatabase()) + "&epoch=ms&q=" + enc(q);
        try {
            HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(15));
            b = "POST".equals(method) ? b.POST(HttpRequest.BodyPublishers.noBody()) : b.GET();
            HttpResponse<String> r = http.send(auth(b).build(), HttpResponse.BodyHandlers.ofString());
            if (r.statusCode() / 100 != 2) {
                throw new IllegalStateException("InfluxDB query failed: HTTP " + r.statusCode() + " " + r.body());
            }
            JsonNode root = Jsons.parse(r.body());
            JsonNode err = root.at("/results/0/error");
            if (!err.isMissingNode()) {
                throw new IllegalStateException("InfluxDB query error: " + err.asText());
            }
            return root;
        } catch (IOException | InterruptedException e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            throw new IllegalStateException("InfluxDB query failed: " + describe(e), e);
        }
    }

    private static JsonNode firstSeries(JsonNode root) {
        JsonNode s = root.at("/results/0/series/0");
        return s.isMissingNode() ? null : s;
    }

    private HttpRequest.Builder auth(HttpRequest.Builder b) {
        if (!cfg.getUsername().isBlank()) {
            String token = Base64.getEncoder()
                    .encodeToString((cfg.getUsername() + ":" + cfg.getPassword()).getBytes(StandardCharsets.UTF_8));
            b.header("Authorization", "Basic " + token);
        }
        return b;
    }

    private static String enc(String s) {
        return URLEncoder.encode(s, StandardCharsets.UTF_8);
    }

    private static String quoteIdent(String s) {
        return "\"" + s.replace("\"", "") + "\"";
    }
}
