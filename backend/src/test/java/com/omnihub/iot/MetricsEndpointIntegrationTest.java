package com.omnihub.iot;

import static org.assertj.core.api.Assertions.assertThat;

import com.omnihub.iot.dashboard.BusinessMetrics;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.actuate.observability.AutoConfigureObservability;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.ActiveProfiles;

/** /actuator/prometheus: closed without the token, and it exposes the throughput / backlog metrics the dashboards use. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureObservability
@ActiveProfiles("test")
class MetricsEndpointIntegrationTest {

    @LocalServerPort
    int port;
    @Autowired
    TestRestTemplate rest;
    @Autowired
    JdbcTemplate jdbc;
    @Autowired
    BusinessMetrics businessMetrics;

    private ResponseEntity<String> scrape(String authorization) {
        HttpHeaders h = new HttpHeaders();
        if (authorization != null) {
            h.set("Authorization", authorization);
        }
        return rest.exchange("http://localhost:" + port + "/actuator/prometheus", HttpMethod.GET, new HttpEntity<>(h),
                String.class);
    }

    @Test
    void closedWithoutOrWithWrongToken() {
        assertThat(scrape(null).getStatusCode().value()).isEqualTo(401);
        assertThat(scrape("Bearer wrong-token").getStatusCode().value()).isEqualTo(401);
    }

    @Test
    void aUserJwtDoesNotOpenTheMetricsEndpoint() {
        // a login token is a different credential: only the metrics token works
        assertThat(scrape("Bearer eyJhbGciOiJIUzI1NiJ9.e30.invalid").getStatusCode().value()).isEqualTo(401);
    }

    @Test
    void exposesBusinessFiguresFromTheDatabase() {
        Long tenant = jdbc.queryForObject("SELECT id FROM sys_tenant ORDER BY id LIMIT 1", Long.class);
        jdbc.update("INSERT INTO iot_product (tenant_id, name, product_key) VALUES (?, 'Metrics test product', 'bmpk')",
                tenant);
        Long product = jdbc.queryForObject("SELECT id FROM iot_product WHERE product_key = 'bmpk'", Long.class);
        String[][] devices = { { "d1", "ONLINE", "true" }, { "d2", "ONLINE", "true" }, { "d3", "OFFLINE", "true" },
                { "d4", "INACTIVE", "false" } };
        for (String[] d : devices) {
            jdbc.update("""
                    INSERT INTO iot_device (tenant_id, product_id, name, device_key, secret_hash, secret_salt, enabled,
                                            online_status) VALUES (?, ?, ?, ?, 'h', 's', ?, ?)""", tenant, product, d[0],
                    d[0], Boolean.parseBoolean(d[2]), d[1]);
        }
        try {
            businessMetrics.refresh();
            String body = scrape("Bearer test-metrics-token").getBody();
            assertThat(body)
                    .contains("iot_product_devices{application=\"iot-backend\",product_key=\"bmpk\",product_name=\"Metrics test product\",status=\"ONLINE\"} 2.0")
                    .contains("iot_product_devices{application=\"iot-backend\",product_key=\"bmpk\",product_name=\"Metrics test product\",status=\"OFFLINE\"} 1.0")
                    .contains("iot_product_devices{application=\"iot-backend\",product_key=\"bmpk\",product_name=\"Metrics test product\",status=\"INACTIVE\"} 1.0")
                    .contains("iot_devices{application=\"iot-backend\",status=\"ONLINE\"}")
                    .contains("iot_devices_disabled{application=\"iot-backend\"}")
                    .contains("iot_alarms_active{application=\"iot-backend\",level=\"CRITICAL\"}")
                    .contains("iot_commands_in_flight{application=\"iot-backend\",status=\"PENDING\"}")
                    .contains("iot_business_metrics_last_refresh_timestamp_seconds")
                    .contains("iot_devices_online_in_memory");
        } finally {
            jdbc.update("DELETE FROM iot_device WHERE product_id = ?", product);
            jdbc.update("DELETE FROM iot_product WHERE id = ?", product);
        }
        // the series of a deleted product disappear again at the next refresh
        businessMetrics.refresh();
        assertThat(scrape("Bearer test-metrics-token").getBody()).doesNotContain("product_key=\"bmpk\"");
    }

    @Test
    void exposesThroughputAndBacklogMetricsWithTheToken() {
        ResponseEntity<String> r = scrape("Bearer test-metrics-token");
        assertThat(r.getStatusCode().value()).isEqualTo(200);
        String body = r.getBody();
        assertThat(body).contains("iot_ingest_processed_total")
                .contains("iot_ingest_delay_ms_bucket")
                .contains("iot_batch_queue_pending{application=\"iot-backend\",queue=\"latest\",shard=\"0\"}")
                .contains("iot_batch_queue_pending{application=\"iot-backend\",queue=\"history\",shard=\"0\"}")
                .contains("iot_batch_queue_pending{application=\"iot-backend\",queue=\"message-log\",shard=\"0\"}")
                .contains("iot_batch_queue_dropped_total")
                .contains("iot_batch_flush_seconds_bucket")
                .contains("iot_lane_queue_pending")
                .contains("iot_mqtt_gateway_connected")
                .contains("jvm_memory_used_bytes")
                .contains("hikaricp_connections_active");
    }
}
