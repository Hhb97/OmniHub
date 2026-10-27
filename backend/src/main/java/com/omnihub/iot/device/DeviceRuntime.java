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
import java.util.concurrent.atomic.AtomicLong;
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
    /** Unknown devices are remembered only briefly so that a freshly created device can connect at once. */
    private static final long NEGATIVE_TTL_MS = 5_000;
    private static final long FALLBACK_POSITIVE_TTL_MS = 60_000;

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
    private final IotProperties.Device cfg;
    /** clientId (productKey.deviceKey) -> device snapshot. Replaced as a whole by {@link #refreshAuthCache()}. */
    private volatile Map<String, Cached> byClientId = new ConcurrentHashMap<>();
    /** Bumped on every invalidation so that a running full refresh does not overwrite newer data. */
    private final AtomicLong generation = new AtomicLong();
    /** device id -> last time a message was received (ms); flushed to iot_device.last_comm_at in batches. */
    private final Map<Long, Long> commAt = new ConcurrentHashMap<>();
    /** devices known to be ONLINE in the database: for them a received message needs no database access at all. */
    private final java.util.Set<Long> onlineKnown = ConcurrentHashMap.newKeySet();

    private static final org.springframework.jdbc.core.RowMapper<DeviceRef> REF_MAPPER = (rs, i) -> new DeviceRef(
            rs.getLong(1), rs.getLong(2), rs.getLong(3), rs.getString(4), rs.getString(5), rs.getString(6),
            rs.getBoolean(7), rs.getBoolean(8), rs.getString(9), rs.getString(10));
    private static final String REF_COLUMNS = """
            SELECT d.id, d.tenant_id, d.product_id, p.product_key, d.device_key, d.name, d.enabled,
                   (p.status = 'ENABLED'), d.secret_hash, d.secret_salt
            FROM iot_device d JOIN iot_product p ON p.id = d.product_id
            WHERE d.deleted = 0 AND p.deleted = 0""";

    public DeviceRuntime(JdbcTemplate jdbc, DeviceMapper deviceMapper, ProductMapper productMapper,
            ConfigService config, ApplicationEventPublisher events, IotProperties props) {
        this.jdbc = jdbc;
        this.deviceMapper = deviceMapper;
        this.productMapper = productMapper;
        this.config = config;
        this.events = events;
        this.cfg = props.getDevice();
    }

    public String clientId(Device d) {
        Product p = productMapper.selectById(d.getProductId());
        return p.getProductKey() + "." + d.getDeviceKey();
    }

    /** Drops the cached entry of this device (and any cached "unknown" answers); the next lookup reloads it. */
    public void invalidate(Device d) {
        generation.incrementAndGet();
        Long id = d.getId();
        byClientId.values().removeIf(c -> c.ref() == null || id.equals(c.ref().id()));
    }

    /** Loads the cache before the web server starts, so the first connect storm after a restart finds it ready. */
    @jakarta.annotation.PostConstruct
    void warmUpOnStartup() {
        refreshAuthCache();
    }

    private long positiveTtlMs() {
        // with the periodic full refresh entries stay valid until the next refresh (plus slack)
        return cfg.isAuthCacheWarmup() && cfg.getAuthCacheRefreshSeconds() > 0
                ? cfg.getAuthCacheRefreshSeconds() * 3000L
                : FALLBACK_POSITIVE_TTL_MS;
    }

    /**
     * Loads all devices into the authentication cache so that connect storms (thousands of devices connecting at
     * once) never hit the database. Runs shortly after startup and then periodically, which also picks up changes
     * made by other instances. Skipped when there are more devices than iot.device.auth-cache-max.
     */
    @Scheduled(initialDelayString = "#{${iot.device.auth-cache-refresh-seconds:300} * 1000}", fixedDelayString = "#{${iot.device.auth-cache-refresh-seconds:300} * 1000}")
    public void refreshAuthCache() {
        if (!cfg.isAuthCacheWarmup()) {
            return;
        }
        long gen = generation.get();
        long started = System.currentTimeMillis();
        try {
            Long total = jdbc.queryForObject("SELECT count(*) FROM iot_device WHERE deleted = 0", Long.class);
            if (total != null && total > cfg.getAuthCacheMax()) {
                log.warn("Device auth cache warm-up skipped: {} devices exceed iot.device.auth-cache-max={}", total,
                        cfg.getAuthCacheMax());
                return;
            }
            Map<String, Cached> fresh = new ConcurrentHashMap<>();
            long lastId = 0;
            while (true) {
                List<DeviceRef> page = jdbc.query(REF_COLUMNS + " AND d.id > ? ORDER BY d.id LIMIT 2000", REF_MAPPER,
                        lastId);
                if (page.isEmpty()) {
                    break;
                }
                long now = System.currentTimeMillis();
                for (DeviceRef r : page) {
                    fresh.put(r.productKey() + "." + r.deviceKey(), new Cached(r, now));
                    lastId = r.id();
                }
            }
            if (generation.get() != gen) {
                log.debug("Device auth cache refresh discarded: a device changed while loading");
                return;
            }
            byClientId = fresh;
            onlineKnown.addAll(jdbc.queryForList("SELECT id FROM iot_device WHERE online_status = 'ONLINE' AND deleted = 0", Long.class));
            log.info("Device auth cache loaded: {} devices in {} ms", fresh.size(), System.currentTimeMillis() - started);
        } catch (Exception e) {
            log.warn("Device auth cache refresh failed: {}", e.getMessage());
        }
    }

    /** Resolves productKey + deviceKey; empty when unknown or logically deleted. Served from memory when cached. */
    public Optional<DeviceRef> resolve(String productKey, String deviceKey) {
        String cid = productKey + "." + deviceKey;
        Cached c = byClientId.get(cid);
        long now = System.currentTimeMillis();
        if (c != null && now - c.at() < (c.ref() == null ? NEGATIVE_TTL_MS : positiveTtlMs())) {
            return Optional.ofNullable(c.ref());
        }
        List<DeviceRef> rows = jdbc.query(REF_COLUMNS + " AND p.product_key = ? AND d.device_key = ?", REF_MAPPER,
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
        onlineKnown.add(deviceId);
        if (!changed.isEmpty()) {
            publishStatus(deviceId, "ONLINE", now, true);
        }
    }

    public void markDisconnected(Long deviceId) {
        Instant now = Instant.now();
        onlineKnown.remove(deviceId);
        commAt.remove(deviceId);
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
        commAt.put(ref.id(), now);
        if (onlineKnown.contains(ref.id())) {
            return; // the common case: memory only, last_comm_at is written by flushLastComm()
        }
        Instant at = Instant.ofEpochMilli(now);
        List<Long> promoted = jdbc.query("""
                UPDATE iot_device SET last_comm_at = ?, mqtt_connected = TRUE, online_status = 'ONLINE',
                       last_online_at = CASE WHEN online_status = 'ONLINE' THEN last_online_at ELSE ? END
                WHERE id = ? AND deleted = 0 AND online_status <> 'ONLINE' RETURNING id""",
                (rs, i) -> rs.getLong(1), ts(at), ts(at), ref.id());
        onlineKnown.add(ref.id());
        if (!promoted.isEmpty()) {
            publishStatus(ref.id(), "ONLINE", at, true);
        }
    }

    /** Writes the pending last-communication times with one statement per 5000 devices. */
    @Scheduled(fixedDelay = 3000)
    public synchronized void flushLastComm() {
        if (commAt.isEmpty()) {
            return;
        }
        Map<Long, Long> snapshot = new java.util.HashMap<>(commAt);
        List<Map.Entry<Long, Long>> entries = new java.util.ArrayList<>(snapshot.entrySet());
        try {
            for (int from = 0; from < entries.size(); from += 5000) {
                List<Map.Entry<Long, Long>> part = entries.subList(from, Math.min(entries.size(), from + 5000));
                Long[] ids = new Long[part.size()];
                java.sql.Timestamp[] times = new java.sql.Timestamp[part.size()];
                for (int i = 0; i < part.size(); i++) {
                    ids[i] = part.get(i).getKey();
                    times[i] = new java.sql.Timestamp(part.get(i).getValue());
                }
                jdbc.execute((org.springframework.jdbc.core.ConnectionCallback<Void>) con -> {
                    try (java.sql.PreparedStatement ps = con.prepareStatement("""
                            UPDATE iot_device d SET last_comm_at = t.ts
                            FROM unnest(?::bigint[], ?::timestamptz[]) AS t(id, ts)
                            WHERE d.id = t.id AND (d.last_comm_at IS NULL OR d.last_comm_at < t.ts)""")) {
                        ps.setArray(1, con.createArrayOf("bigint", ids));
                        ps.setArray(2, con.createArrayOf("timestamptz", times));
                        ps.executeUpdate();
                    }
                    return null;
                });
            }
            snapshot.forEach(commAt::remove); // keep entries that were refreshed while writing
        } catch (Exception e) {
            log.warn("Flushing device last-communication times failed: {}", e.getMessage());
        }
    }

    /** Demotes devices that have been silent for longer than the configured timeout. */
    @Scheduled(fixedDelayString = "#{${iot.device.offline-sweep-seconds:15} * 1000}")
    public void sweepOffline() {
        try {
            flushLastComm(); // the sweep decides from last_comm_at, so make sure it is current
            int timeout = config.getInt("device.offline.timeout.seconds", 90);
            Instant now = Instant.now();
            List<Long> ids = jdbc.query("""
                    UPDATE iot_device SET online_status = 'OFFLINE', last_offline_at = ?
                    WHERE online_status = 'ONLINE' AND deleted = 0 AND last_comm_at < ? RETURNING id""",
                    (rs, i) -> rs.getLong(1), ts(now), ts(now.minusSeconds(timeout)));
            for (Long id : ids) {
                onlineKnown.remove(id);
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
