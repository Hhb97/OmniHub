package com.omnihub.iot.device;

import com.omnihub.iot.config.IotProperties;
import com.omnihub.iot.product.Product;
import com.omnihub.iot.product.ProductMapper;
import com.omnihub.iot.system.service.ConfigService;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

/**
 * Device state driven by the MQTT side: identity resolution, authentication and connection / online state.
 *
 * Two independent states are kept per device:
 * - mqtt_connected: the transport. True after a broker connect / "online" status message / any upstream message,
 *   false after disconnect or the last-will "offline" message.
 * - online_status (business): ONLINE only while the device is connected AND has been heard from within the
 *   configured timeout. The sweeper demotes silent devices to OFFLINE even if the transport still looks connected.
 */
@Service
public class DeviceRuntime {

    private static final Logger log = LoggerFactory.getLogger(DeviceRuntime.class);
    private static final long CACHE_TTL_MS = 60_000;
    private static final long TOUCH_WRITE_INTERVAL_MS = 5_000;

    /** Immutable snapshot of what the ingestion path needs about a device. */
    public record DeviceRef(Long id, Long tenantId, Long productId, String productKey, String deviceKey, String name,
            boolean enabled, boolean productEnabled, String secretHash, String secretSalt) {
    }

    private record Cached(DeviceRef ref, long at) {
    }

    private final JdbcTemplate jdbc;
    private final DeviceMapper deviceMapper;
    private final ProductMapper productMapper;
    private final ConfigService config;
    private final ApplicationEventPublisher events;
    private final Map<String, Cached> byClientId = new ConcurrentHashMap<>();
    private final Map<Long, Long> lastTouchWrite = new ConcurrentHashMap<>();

    public DeviceRuntime(JdbcTemplate jdbc, DeviceMapper deviceMapper, ProductMapper productMapper,
            ConfigService config, ApplicationEventPublisher events, IotProperties props) {
        this.jdbc = jdbc;
        this.deviceMapper = deviceMapper;
        this.productMapper = productMapper;
        this.config = config;
        this.events = events;
    }

    public String clientId(Device d) {
        Product p = productMapper.selectById(d.getProductId());
        return p.getProductKey() + "." + d.getDeviceKey();
    }

    public void invalidate(Device d) {
        byClientId.clear();
        lastTouchWrite.remove(d.getId());
    }

    /** Resolves productKey + deviceKey; empty when unknown or logically deleted. Cached briefly. */
    public Optional<DeviceRef> resolve(String productKey, String deviceKey) {
        String cid = productKey + "." + deviceKey;
        Cached c = byClientId.get(cid);
        long now = System.currentTimeMillis();
        if (c != null && now - c.at() < CACHE_TTL_MS) {
            return Optional.ofNullable(c.ref());
        }
        List<DeviceRef> rows = jdbc.query("""
                SELECT d.id, d.tenant_id, d.product_id, p.product_key, d.device_key, d.name, d.enabled,
                       (p.status = 'ENABLED'), d.secret_hash, d.secret_salt
                FROM iot_device d JOIN iot_product p ON p.id = d.product_id
                WHERE p.product_key = ? AND d.device_key = ? AND d.deleted = 0 AND p.deleted = 0""",
                (rs, i) -> new DeviceRef(rs.getLong(1), rs.getLong(2), rs.getLong(3), rs.getString(4),
                        rs.getString(5), rs.getString(6), rs.getBoolean(7), rs.getBoolean(8), rs.getString(9),
                        rs.getString(10)),
                productKey, deviceKey);
        DeviceRef ref = rows.isEmpty() ? null : rows.get(0);
        byClientId.put(cid, new Cached(ref, now));
        return Optional.ofNullable(ref);
    }

    public Optional<DeviceRef> resolveClientId(String clientId) {
        int dot = clientId == null ? -1 : clientId.indexOf('.');
        if (dot <= 0 || dot == clientId.length() - 1) {
            return Optional.empty();
        }
        return resolve(clientId.substring(0, dot), clientId.substring(dot + 1));
    }

    /** MQTT connect authentication: clientId == username == productKey.deviceKey, password == device secret. */
    public boolean authenticate(String clientId, String username, String password) {
        if (clientId == null || !clientId.equals(username) || password == null) {
            return false;
        }
        return resolveClientId(clientId)
                .filter(r -> r.enabled() && r.productEnabled())
                .map(r -> DeviceCredentials.matches(r.secretSalt(), r.secretHash(), password)).orElse(false);
    }

    public void markConnected(Long deviceId) {
        Instant now = Instant.now();
        List<Long> changed = jdbc.query("""
                UPDATE iot_device SET mqtt_connected = TRUE, online_status = 'ONLINE', last_comm_at = ?,
                       last_online_at = CASE WHEN online_status = 'ONLINE' THEN last_online_at ELSE ? END
                WHERE id = ? AND deleted = 0 RETURNING id""", (rs, i) -> rs.getLong(1), ts(now), ts(now), deviceId);
        lastTouchWrite.put(deviceId, now.toEpochMilli());
        if (!changed.isEmpty()) {
            publishStatus(deviceId, "ONLINE", now, true);
        }
    }

    public void markDisconnected(Long deviceId) {
        Instant now = Instant.now();
        jdbc.update("""
                UPDATE iot_device SET mqtt_connected = FALSE,
                       last_offline_at = CASE WHEN online_status = 'ONLINE' THEN ? ELSE last_offline_at END,
                       online_status = CASE WHEN online_status = 'INACTIVE' THEN 'INACTIVE' ELSE 'OFFLINE' END
                WHERE id = ? AND deleted = 0""", ts(now), deviceId);
        publishStatus(deviceId, "OFFLINE", now, false);
    }

    /**
     * Records that an upstream message was received. A device that is publishing is by definition connected,
     * so a stale OFFLINE flag is corrected here. DB writes are throttled for the common already-online case.
     */
    public void touch(DeviceRef ref) {
        long now = System.currentTimeMillis();
        Long last = lastTouchWrite.get(ref.id());
        if (last != null && now - last < TOUCH_WRITE_INTERVAL_MS) {
            return;
        }
        lastTouchWrite.put(ref.id(), now);
        Instant at = Instant.ofEpochMilli(now);
        List<Long> promoted = jdbc.query("""
                UPDATE iot_device SET last_comm_at = ?, mqtt_connected = TRUE, online_status = 'ONLINE',
                       last_online_at = CASE WHEN online_status = 'ONLINE' THEN last_online_at ELSE ? END
                WHERE id = ? AND deleted = 0 AND online_status <> 'ONLINE' RETURNING id""",
                (rs, i) -> rs.getLong(1), ts(at), ts(at), ref.id());
        if (!promoted.isEmpty()) {
            publishStatus(ref.id(), "ONLINE", at, true);
        } else {
            jdbc.update("UPDATE iot_device SET last_comm_at = ? WHERE id = ?", ts(at), ref.id());
        }
    }

    /** Demotes devices that have been silent for longer than the configured timeout. */
    @Scheduled(fixedDelayString = "#{${iot.device.offline-sweep-seconds:15} * 1000}")
    public void sweepOffline() {
        try {
            int timeout = config.getInt("device.offline.timeout.seconds", 90);
            Instant now = Instant.now();
            List<Long> ids = jdbc.query("""
                    UPDATE iot_device SET online_status = 'OFFLINE', last_offline_at = ?
                    WHERE online_status = 'ONLINE' AND deleted = 0 AND last_comm_at < ? RETURNING id""",
                    (rs, i) -> rs.getLong(1), ts(now), ts(now.minusSeconds(timeout)));
            for (Long id : ids) {
                log.info("Device {} marked OFFLINE: no message for more than {}s", id, timeout);
                publishStatus(id, "OFFLINE", now, null);
            }
        } catch (Exception e) {
            log.warn("Offline sweep failed: {}", e.getMessage());
        }
    }

    private void publishStatus(Long deviceId, String status, Instant at, Boolean connected) {
        Device d = deviceMapper.selectById(deviceId);
        if (d == null) {
            return;
        }
        events.publishEvent(new DeviceStatusEvent(deviceId, d.getTenantId(), status, at));
    }

    private static java.sql.Timestamp ts(Instant i) {
        return java.sql.Timestamp.from(i);
    }
}
