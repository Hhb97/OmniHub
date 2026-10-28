package com.omnihub.iot.device;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.omnihub.iot.config.IotProperties;
import com.omnihub.iot.device.DeviceRuntime.DeviceRef;
import com.omnihub.iot.product.ProductMapper;
import com.omnihub.iot.system.service.ConfigService;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

/** MQTT authentication must be answered from memory: no database access per connect. */
class DeviceRuntimeAuthCacheTest {

    private static final String SECRET = "s3cret-value";
    private final JdbcTemplate jdbc = mock(JdbcTemplate.class);
    private final IotProperties props = new IotProperties();
    private DeviceRuntime runtime;
    private DeviceRef ref;

    @BeforeEach
    void setUp() {
        String salt = DeviceCredentials.newSalt();
        ref = new DeviceRef(7L, 1L, 3L, "pk", "dk", "dev", true, true, DeviceCredentials.hash(salt, SECRET), salt);
        runtime = new DeviceRuntime(jdbc, mock(DeviceMapper.class), mock(ProductMapper.class), mock(ConfigService.class),
                mock(ApplicationEventPublisher.class), props);
    }

    @SuppressWarnings("unchecked")
    private void lookupReturns(List<DeviceRef> rows) {
        when(jdbc.query(contains("p.product_key = ?"), any(RowMapper.class), eq("pk"), eq("dk"))).thenReturn(rows);
    }

    @Test
    void checkNamesTheReasonForEveryRefusal() {
        lookupReturns(List.of(ref));
        assertThat(runtime.check("pk.dk", "pk.dk", SECRET)).isEqualTo(DeviceRuntime.AuthResult.ALLOW);
        assertThat(runtime.check("pk.dk", "pk.dk", "wrong")).isEqualTo(DeviceRuntime.AuthResult.BAD_SECRET);
        assertThat(runtime.check("pk.dk", "other", SECRET)).isEqualTo(DeviceRuntime.AuthResult.MALFORMED);
        assertThat(runtime.check(null, null, SECRET)).isEqualTo(DeviceRuntime.AuthResult.MALFORMED);
        assertThat(runtime.check("pk.dk", "pk.dk", null)).isEqualTo(DeviceRuntime.AuthResult.NO_PASSWORD);
        assertThat(runtime.check("pk.dk", "pk.dk", "")).isEqualTo(DeviceRuntime.AuthResult.NO_PASSWORD);
        assertThat(DeviceRuntime.AuthResult.BAD_SECRET.reason()).isEqualTo("bad_secret");
    }

    @Test
    void secondAuthenticationDoesNotTouchTheDatabase() {
        lookupReturns(List.of(ref));
        assertThat(runtime.authenticate("pk.dk", "pk.dk", SECRET)).isTrue();
        assertThat(runtime.authenticate("pk.dk", "pk.dk", SECRET)).isTrue();
        assertThat(runtime.authenticate("pk.dk", "pk.dk", "wrong")).isFalse();
        verify(jdbc, times(1)).query(contains("p.product_key = ?"), any(RowMapper.class), eq("pk"), eq("dk"));
    }

    @Test
    void invalidateForcesAReloadSoANewSecretIsPickedUp() {
        lookupReturns(List.of(ref));
        assertThat(runtime.authenticate("pk.dk", "pk.dk", SECRET)).isTrue();

        String salt2 = DeviceCredentials.newSalt();
        DeviceRef rotated = new DeviceRef(7L, 1L, 3L, "pk", "dk", "dev", true, true,
                DeviceCredentials.hash(salt2, "new-secret"), salt2);
        lookupReturns(List.of(rotated));
        Device d = new Device();
        d.setId(7L);
        runtime.invalidate(d);

        assertThat(runtime.authenticate("pk.dk", "pk.dk", SECRET)).isFalse();
        assertThat(runtime.authenticate("pk.dk", "pk.dk", "new-secret")).isTrue();
    }

    @Test
    void disabledDeviceIsRejected() {
        DeviceRef disabled = new DeviceRef(7L, 1L, 3L, "pk", "dk", "dev", false, true, ref.secretHash(), ref.secretSalt());
        lookupReturns(List.of(disabled));
        assertThat(runtime.authenticate("pk.dk", "pk.dk", SECRET)).isFalse();
    }

    @Test
    void unknownDeviceIsRememberedBrieflyAndClientIdMustMatchUsername() {
        lookupReturns(List.of());
        assertThat(runtime.authenticate("pk.dk", "pk.dk", SECRET)).isFalse();
        assertThat(runtime.authenticate("pk.dk", "pk.dk", SECRET)).isFalse();
        verify(jdbc, times(1)).query(contains("p.product_key = ?"), any(RowMapper.class), eq("pk"), eq("dk"));
        assertThat(runtime.authenticate("pk.dk", "other.user", SECRET)).isFalse();
        assertThat(runtime.authenticate("nodot", "nodot", SECRET)).isFalse();
    }

    @Test
    @SuppressWarnings("unchecked")
    void warmUpLoadsAllDevicesSoConnectsNeverQueryTheDatabase() {
        when(jdbc.queryForObject(anyString(), eq(Long.class))).thenReturn(1L);
        when(jdbc.query(contains("d.id > ?"), any(RowMapper.class), eq(0L))).thenReturn(List.of(ref));
        when(jdbc.query(contains("d.id > ?"), any(RowMapper.class), eq(7L))).thenReturn(List.of());

        runtime.refreshAuthCache();

        assertThat(runtime.authenticate("pk.dk", "pk.dk", SECRET)).isTrue();
        verify(jdbc, never()).query(contains("p.product_key = ?"), any(RowMapper.class), any(), any());
    }

    @Test
    @SuppressWarnings("unchecked")
    void warmUpIsSkippedWhenThereAreTooManyDevices() {
        props.getDevice().setAuthCacheMax(10);
        when(jdbc.queryForObject(anyString(), eq(Long.class))).thenReturn(11L);
        runtime.refreshAuthCache();
        verify(jdbc, never()).query(contains("d.id > ?"), any(RowMapper.class), any());
    }
}
