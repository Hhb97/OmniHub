package com.omnihub.iot;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
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
    void exposesThroughputAndBacklogMetricsWithTheToken() {
        ResponseEntity<String> r = scrape("Bearer test-metrics-token");
        assertThat(r.getStatusCode().value()).isEqualTo(200);
        String body = r.getBody();
        assertThat(body).contains("iot_ingest_processed_total")
                .contains("iot_ingest_delay_ms_bucket")
                .contains("iot_batch_queue_pending{application=\"iot-backend\",queue=\"latest\"}")
                .contains("iot_batch_queue_pending{application=\"iot-backend\",queue=\"history\"}")
                .contains("iot_batch_queue_pending{application=\"iot-backend\",queue=\"message-log\"}")
                .contains("iot_batch_queue_dropped_total")
                .contains("iot_batch_flush_seconds_bucket")
                .contains("iot_lane_queue_pending")
                .contains("iot_mqtt_gateway_connected")
                .contains("jvm_memory_used_bytes")
                .contains("hikaricp_connections_active");
    }
}
