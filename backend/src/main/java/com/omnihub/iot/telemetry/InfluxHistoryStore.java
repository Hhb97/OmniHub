package com.omnihub.iot.telemetry;

import com.fasterxml.jackson.databind.JsonNode;
import com.omnihub.iot.common.Jsons;
import com.omnihub.iot.config.IotProperties;
import com.omnihub.iot.thingmodel.ValueValidator;
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
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * InfluxDB 1.x (tested target: 1.8) history store over the HTTP API.
 * Measurement `device_property`; tags: device_id, identifier; fields: num (float, numeric/boolean values only)
 * and json (the value as JSON text). Writes use line protocol with millisecond precision.
 * Expiry is delegated to an InfluxDB retention policy (see docs/local-infra.md).
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

    public InfluxHistoryStore(IotProperties props) {
        this.cfg = props.getInfluxdb();
    }

    @PostConstruct
    void init() {
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

    @Override
    public void write(Long deviceId, Map<String, JsonNode> values, Instant ts) {
        StringBuilder body = new StringBuilder();
        values.forEach((id, v) -> {
            if (!IDENT.matcher(id).matches()) {
                return;
            }
            Double num = ValueValidator.numeric(v);
            body.append(M).append(",device_id=").append(deviceId).append(",identifier=").append(id).append(' ');
            if (num != null) {
                body.append("num=").append(num).append(',');
            }
            body.append("json=\"").append(Jsons.toJson(v).replace("\\", "\\\\").replace("\"", "\\\"")).append("\" ")
                    .append(ts.toEpochMilli()).append('\n');
        });
        if (body.isEmpty()) {
            return;
        }
        String url = cfg.getUrl() + "/write?db=" + enc(cfg.getDatabase()) + "&precision=ms"
                + (cfg.getRetentionPolicy().isBlank() ? "" : "&rp=" + enc(cfg.getRetentionPolicy()));
        try {
            HttpResponse<String> r = http.send(auth(HttpRequest.newBuilder(URI.create(url))
                    .timeout(Duration.ofSeconds(10)).POST(HttpRequest.BodyPublishers.ofString(body.toString()))).build(),
                    HttpResponse.BodyHandlers.ofString());
            if (r.statusCode() != 204) {
                throw new IllegalStateException("InfluxDB write failed: HTTP " + r.statusCode() + " " + r.body());
            }
        } catch (IOException | InterruptedException e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            throw new IllegalStateException("InfluxDB write failed: " + e.getMessage(), e);
        }
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
            throw new IllegalStateException("InfluxDB query failed: " + e.getMessage(), e);
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
