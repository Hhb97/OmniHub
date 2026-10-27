package com.omnihub.iot.telemetry;

import com.omnihub.iot.common.SensitiveMasker;
import java.sql.Timestamp;
import java.time.Instant;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/** Writes the per-device communication record (iot_device_message_log). Payloads are masked and truncated. */
@Service
public class MessageLogger {

    private final JdbcTemplate jdbc;

    public MessageLogger(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void log(Long deviceId, String direction, String type, String topic, String payload, String result,
            String message) {
        jdbc.update("""
                INSERT INTO iot_device_message_log (device_id, direction, type, topic, payload, result, message, created_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?)""", deviceId, direction, type, SensitiveMasker.truncate(topic, 255),
                SensitiveMasker.truncate(SensitiveMasker.maskJson(payload), 2000), result,
                SensitiveMasker.truncate(message, 255), Timestamp.from(Instant.now()));
    }
}
