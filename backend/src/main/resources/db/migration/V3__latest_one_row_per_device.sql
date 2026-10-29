-- One row per device instead of one row per (device, property).
--
-- A property message carries every property of the device. With the old shape (device_id, identifier) that single
-- message produced 7 row versions: at 10000 devices x 1 message/s the latest-value table alone needed 70000 row
-- updates per second, which PostgreSQL cannot sustain -- the batch queue filled up, the Kafka consumer was pushed
-- back, and the broker ended up dropping the uplink.
--
-- The properties now live in one JSONB document per device:
--   {"temperature": {"v": 21.5, "t": 1793179493731}, "switch": {"v": true, "t": 1793179493731}, ...}
-- "t" keeps the report time of each property (the API returns it per property), and the upsert replaces an entry
-- only when its report time is newer, so a late report still cannot overwrite a newer value.
CREATE TABLE iot_device_latest_new (
    device_id BIGINT PRIMARY KEY,
    values    JSONB  NOT NULL
);

INSERT INTO iot_device_latest_new (device_id, values)
SELECT device_id,
       jsonb_object_agg(identifier, jsonb_build_object(
               'v', value,
               't', (extract(epoch FROM reported_at) * 1000)::bigint))
FROM iot_device_latest
GROUP BY device_id;

DROP TABLE iot_device_latest;
ALTER TABLE iot_device_latest_new RENAME TO iot_device_latest;
