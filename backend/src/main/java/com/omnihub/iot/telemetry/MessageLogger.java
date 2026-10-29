package com.omnihub.iot.telemetry;

import com.omnihub.iot.common.ShardedBatchQueue;
import com.omnihub.iot.common.SensitiveMasker;
import com.omnihub.iot.config.IngestDb;
import com.omnihub.iot.config.IotProperties;
import jakarta.annotation.PreDestroy;
import java.sql.PreparedStatement;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * Writes the per-device communication record (iot_device_message_log). Rows are queued and inserted in batches by a
 * background thread (one INSERT ... SELECT unnest(...) per batch), so logging never costs a database round trip on the
 * MQTT message path. Payloads are masked and truncated in the background thread, not on the caller's.
 */
@Service
public class MessageLogger {

    private record Row(Long deviceId, String direction, String type, String topic, String payload, String result,
            String message, Instant at) {
    }

    private static final int CHUNK = 5000;

    private final JdbcTemplate jdbc;
    private final boolean logOkProperty;
    private final ShardedBatchQueue<Row> queue;

    public MessageLogger(IngestDb ingestDb, IotProperties props, io.micrometer.core.instrument.MeterRegistry meters) {
        this.jdbc = ingestDb.jdbc();
        IotProperties.Batch b = props.getIngest().getBatch();
        this.logOkProperty = b.isLogOkProperty();
        this.queue = new ShardedBatchQueue<Row>("message-log", b.getLogWorkers(), b.getQueueCapacity(), b.getMaxBatch(),
                100, b.getLingerMs(), r -> r.deviceId() == null ? 0L : r.deviceId(), this::write).bind(meters);
    }

    public void log(Long deviceId, String direction, String type, String topic, String payload, String result,
            String message) {
        if (!logOkProperty && "UP".equals(direction) && "PROPERTY".equals(type) && "OK".equals(result)) {
            return;
        }
        queue.offer(new Row(deviceId, direction, type, topic, SensitiveMasker.truncate(payload, 4000), result, message,
                Instant.now()));
    }

    public long dropped() {
        return queue.dropped();
    }

    @PreDestroy
    void close() {
        queue.close();
    }

    private void write(List<Row> rows) {
        for (int from = 0; from < rows.size(); from += CHUNK) {
            List<Row> part = rows.subList(from, Math.min(rows.size(), from + CHUNK));
            int n = part.size();
            Long[] dev = new Long[n];
            String[] dir = new String[n];
            String[] type = new String[n];
            String[] topic = new String[n];
            String[] payload = new String[n];
            String[] result = new String[n];
            String[] msg = new String[n];
            Timestamp[] at = new Timestamp[n];
            for (int i = 0; i < n; i++) {
                Row r = part.get(i);
                dev[i] = r.deviceId();
                dir[i] = r.direction();
                type[i] = r.type();
                topic[i] = SensitiveMasker.truncate(r.topic(), 255);
                payload[i] = SensitiveMasker.truncate(SensitiveMasker.maskJson(r.payload()), 2000);
                result[i] = r.result();
                msg[i] = SensitiveMasker.truncate(r.message(), 255);
                at[i] = Timestamp.from(r.at());
            }
            jdbc.execute((ConnectionCallback<Void>) con -> {
                try (PreparedStatement ps = con.prepareStatement("""
                        INSERT INTO iot_device_message_log (device_id, direction, type, topic, payload, result, message, created_at)
                        SELECT a, b, c, d, e, f, g, h FROM unnest(?::bigint[], ?::varchar[], ?::varchar[], ?::varchar[],
                                ?::varchar[], ?::varchar[], ?::varchar[], ?::timestamptz[]) AS x(a, b, c, d, e, f, g, h)""")) {
                    ps.setArray(1, con.createArrayOf("bigint", dev));
                    ps.setArray(2, con.createArrayOf("varchar", dir));
                    ps.setArray(3, con.createArrayOf("varchar", type));
                    ps.setArray(4, con.createArrayOf("varchar", topic));
                    ps.setArray(5, con.createArrayOf("varchar", payload));
                    ps.setArray(6, con.createArrayOf("varchar", result));
                    ps.setArray(7, con.createArrayOf("varchar", msg));
                    ps.setArray(8, con.createArrayOf("timestamptz", at));
                    ps.executeUpdate();
                }
                return null;
            });
        }
    }
}
