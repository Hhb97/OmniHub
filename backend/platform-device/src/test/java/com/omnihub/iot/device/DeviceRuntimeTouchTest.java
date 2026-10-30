package com.omnihub.iot.device;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import com.omnihub.iot.config.IngestDb;
import com.omnihub.iot.config.IotProperties;
import com.omnihub.iot.device.DeviceRuntime.DeviceRef;
import com.omnihub.iot.product.ProductMapper;
import com.omnihub.iot.system.service.ConfigService;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

/** A message from an already-online device must cost no database access; last_comm_at is written in batches. */
class DeviceRuntimeTouchTest {

    private final JdbcTemplate jdbc = mock(JdbcTemplate.class);
    private DeviceRuntime runtime;
    private final DeviceRef ref = new DeviceRef(7L, 1L, 3L, "pk", "dk", "dev", true, true, "h", "s");

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        IngestDb ingestDb = mock(IngestDb.class);
        when(ingestDb.jdbc()).thenReturn(jdbc);
        runtime = new DeviceRuntime(ingestDb, mock(DeviceMapper.class), mock(ProductMapper.class),
                mock(ConfigService.class), mock(ApplicationEventPublisher.class), new IotProperties());
        when(jdbc.query(contains("online_status <> 'ONLINE'"), any(RowMapper.class), any(), any(), eq(7L)))
                .thenReturn(List.of(7L));
    }

    @SuppressWarnings("unchecked")
    private void verifyPromotions(int times) {
        verify(jdbc, times(times)).query(contains("online_status <> 'ONLINE'"), any(RowMapper.class), any(), any(),
                eq(7L));
    }

    @Test
    void onlyTheFirstMessageTouchesTheDatabase() {
        for (int i = 0; i < 1000; i++) {
            runtime.touch(ref);
        }
        verifyPromotions(1);
        verifyNoMoreInteractions(jdbc);
    }

    @Test
    void lastCommunicationIsFlushedInOneStatementAndNotRepeated() {
        runtime.touch(ref);
        runtime.touch(new DeviceRef(8L, 1L, 3L, "pk", "dk2", "dev2", true, true, "h", "s"));
        runtime.flushLastComm();
        verify(jdbc, times(1)).execute(any(ConnectionCallback.class));
        runtime.flushLastComm(); // nothing new since the last flush
        verify(jdbc, times(1)).execute(any(ConnectionCallback.class));
    }

    @Test
    void afterADisconnectTheNextMessageChecksTheDatabaseAgain() {
        runtime.touch(ref);
        runtime.markDisconnected(7L);
        runtime.touch(ref);
        assertThat(true).isTrue();
        verifyPromotions(2);
    }
}
