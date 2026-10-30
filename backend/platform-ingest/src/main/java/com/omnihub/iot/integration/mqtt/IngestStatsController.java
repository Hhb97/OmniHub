package com.omnihub.iot.integration.mqtt;

import com.omnihub.iot.common.ApiResponse;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Live uplink throughput for the dashboard page. It lives with the ingest module because it reads the ingest
 * counters, but keeps the dashboard URL and permission so the frontend does not have to know that.
 */
@RestController
@RequestMapping("/api/dashboard")
public class IngestStatsController {

    private final IngestStats stats;

    public IngestStatsController(IngestStats stats) {
        this.stats = stats;
    }

    /** Platform-wide uplink throughput (TPS), queue delay and last 60 seconds; not tenant-filtered. */
    @GetMapping("/ingest-stats")
    @PreAuthorize("hasAuthority('dashboard:view')")
    public ApiResponse<IngestStats.Snapshot> ingestStats() {
        return ApiResponse.ok(stats.snapshot());
    }
}
