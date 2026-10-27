package com.omnihub.iot.telemetry;

import com.omnihub.iot.common.ApiResponse;
import com.omnihub.iot.common.PageResult;
import com.omnihub.iot.telemetry.TelemetryService.EventVO;
import com.omnihub.iot.telemetry.TelemetryService.HistoryVO;
import com.omnihub.iot.telemetry.TelemetryService.LatestVO;
import com.omnihub.iot.telemetry.TelemetryService.MessageVO;
import com.omnihub.iot.telemetry.TelemetryService.TrendVO;
import java.util.List;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/devices/{id}")
@PreAuthorize("hasAuthority('telemetry:view')")
public class TelemetryController {

    private final TelemetryService service;

    public TelemetryController(TelemetryService service) {
        this.service = service;
    }

    @GetMapping("/latest")
    public ApiResponse<List<LatestVO>> latest(@PathVariable Long id) {
        return ApiResponse.ok(service.latest(id));
    }

    @GetMapping("/history")
    public ApiResponse<PageResult<HistoryVO>> history(@PathVariable Long id, @RequestParam List<String> identifiers,
            @RequestParam(required = false) String start, @RequestParam(required = false) String end,
            @RequestParam(required = false) Integer page, @RequestParam(required = false) Integer size) {
        return ApiResponse.ok(service.history(id, identifiers, start, end, page, size));
    }

    @GetMapping("/trend")
    public ApiResponse<TrendVO> trend(@PathVariable Long id, @RequestParam List<String> identifiers,
            @RequestParam(required = false) String start, @RequestParam(required = false) String end,
            @RequestParam(required = false) String interval) {
        return ApiResponse.ok(service.trend(id, identifiers, start, end, interval));
    }

    @GetMapping("/events")
    public ApiResponse<PageResult<EventVO>> events(@PathVariable Long id, @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer size, @RequestParam(required = false) String level) {
        return ApiResponse.ok(service.events(id, page, size, level));
    }

    @GetMapping("/messages")
    public ApiResponse<PageResult<MessageVO>> messages(@PathVariable Long id,
            @RequestParam(required = false) Integer page, @RequestParam(required = false) Integer size,
            @RequestParam(required = false) String direction) {
        return ApiResponse.ok(service.messages(id, page, size, direction));
    }
}
