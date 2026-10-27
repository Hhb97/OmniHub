package com.omnihub.iot.telemetry;

import com.fasterxml.jackson.databind.JsonNode;
import com.omnihub.iot.common.Jsons;
import com.omnihub.iot.thingmodel.ValueValidator;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/** Default history store: iot_device_property_history in PostgreSQL. */
@Component
@ConditionalOnProperty(prefix = "iot.history", name = "store", havingValue = "postgres", matchIfMissing = true)
public class PostgresHistoryStore implements HistoryStore {

    private final JdbcTemplate jdbc;

    public PostgresHistoryStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public void write(Long deviceId, Map<String, JsonNode> values, Instant ts) {
        Timestamp t = Timestamp.from(ts);
        List<Object[]> rows = new ArrayList<>();
        values.forEach((k, v) -> rows
                .add(new Object[] { deviceId, k, ValueValidator.numeric(v), Jsons.toJson(v), t }));
        jdbc.batchUpdate("""
                INSERT INTO iot_device_property_history (device_id, identifier, num_value, value, ts)
                VALUES (?, ?, ?, ?::jsonb, ?)""", rows);
    }

    @Override
    public Page page(Long deviceId, List<String> ids, Instant from, Instant to, int size, int offset) {
        String in = String.join(",", Collections.nCopies(ids.size(), "?"));
        List<Object> args = new ArrayList<>(List.of(deviceId));
        args.addAll(ids);
        args.add(Timestamp.from(from));
        args.add(Timestamp.from(to));
        String where = " WHERE device_id = ? AND identifier IN (" + in + ") AND ts >= ? AND ts < ?";
        Long total = jdbc.queryForObject("SELECT count(*) FROM iot_device_property_history" + where, Long.class,
                args.toArray());
        List<Object> pageArgs = new ArrayList<>(args);
        pageArgs.add(size);
        pageArgs.add(offset);
        List<Row> rows = jdbc.query("SELECT identifier, value::text, ts FROM iot_device_property_history" + where
                + " ORDER BY ts DESC, id DESC LIMIT ? OFFSET ?",
                (rs, i) -> new Row(rs.getString(1), Jsons.parse(rs.getString(2)), rs.getTimestamp(3).toInstant()),
                pageArgs.toArray());
        return new Page(rows, total == null ? 0 : total);
    }

    @Override
    public List<List<Object>> series(Long deviceId, String id, Instant from, Instant to, long bucket, int maxPoints) {
        List<List<Object>> points = new ArrayList<>();
        if (bucket == 0) {
            jdbc.query("""
                    SELECT ts, value::text FROM iot_device_property_history
                    WHERE device_id = ? AND identifier = ? AND ts >= ? AND ts < ? ORDER BY ts DESC LIMIT ?""",
                    rs -> {
                        points.add(List.of(rs.getTimestamp(1).getTime(), Jsons.parse(rs.getString(2))));
                    }, deviceId, id, Timestamp.from(from), Timestamp.from(to), maxPoints);
            Collections.reverse(points);
        } else {
            jdbc.query("""
                    SELECT floor(extract(epoch FROM ts) / ?) * ? AS bucket, avg(num_value)
                    FROM iot_device_property_history
                    WHERE device_id = ? AND identifier = ? AND ts >= ? AND ts < ? AND num_value IS NOT NULL
                    GROUP BY 1 ORDER BY 1""", rs -> {
                        points.add(List.of((long) (rs.getDouble(1) * 1000),
                                Math.round(rs.getDouble(2) * 1000.0) / 1000.0));
                    }, bucket, bucket, deviceId, id, Timestamp.from(from), Timestamp.from(to));
        }
        return points;
    }

    @Override
    public void purgeOlderThan(int days) {
        jdbc.update("DELETE FROM iot_device_property_history WHERE ts < now() - make_interval(days => ?)", days);
    }
}
