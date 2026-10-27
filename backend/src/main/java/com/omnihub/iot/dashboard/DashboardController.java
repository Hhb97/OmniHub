package com.omnihub.iot.dashboard;

import com.omnihub.iot.common.ApiResponse;
import com.omnihub.iot.dashboard.DashboardService.AlarmStats;
import com.omnihub.iot.dashboard.DashboardService.Overview;
import com.omnihub.iot.dashboard.DashboardService.TopDevice;
import com.omnihub.iot.dashboard.DashboardService.TrendPoint;
import com.omnihub.iot.device.DeviceVO;
import java.util.List;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/dashboard")
@PreAuthorize("hasAuthority('dashboard:view')")
public class DashboardController {

    private final DashboardService service;

    public DashboardController(DashboardService service) {
        this.service = service;
    }

    @GetMapping("/overview")
    public ApiResponse<Overview> overview() {
        return ApiResponse.ok(service.overview());
    }

    @GetMapping("/report-trend")
    public ApiResponse<List<TrendPoint>> reportTrend(@RequestParam(defaultValue = "24") int hours) {
        return ApiResponse.ok(service.reportTrend(hours));
    }

    @GetMapping("/alarm-stats")
    public ApiResponse<AlarmStats> alarmStats(@RequestParam(defaultValue = "7") int days) {
        return ApiResponse.ok(service.alarmStats(days));
    }

    @GetMapping("/recent-devices")
    public ApiResponse<List<DeviceVO>> recent(@RequestParam(defaultValue = "10") int limit) {
        return ApiResponse.ok(service.recentDevices(limit));
    }

    @GetMapping("/top-devices")
    public ApiResponse<List<TopDevice>> top(@RequestParam(defaultValue = "5") int limit) {
        return ApiResponse.ok(service.topDevices(limit));
    }
}
