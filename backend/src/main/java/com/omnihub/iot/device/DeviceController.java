package com.omnihub.iot.device;

import com.omnihub.iot.common.ApiResponse;
import com.omnihub.iot.common.PageResult;
import com.omnihub.iot.system.OpLog;
import jakarta.validation.Valid;
import java.util.Map;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api")
public class DeviceController {

    private final DeviceService service;

    public DeviceController(DeviceService service) {
        this.service = service;
    }

    @GetMapping("/devices")
    @PreAuthorize("hasAuthority('device:view')")
    public ApiResponse<PageResult<DeviceVO>> page(@RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer size, @RequestParam(required = false) String keyword,
            @RequestParam(required = false) Long productId, @RequestParam(required = false) String onlineStatus,
            @RequestParam(required = false) Boolean enabled) {
        return ApiResponse.ok(service.page(page, size, keyword, productId, onlineStatus, enabled));
    }

    @GetMapping("/products/{productId}/devices")
    @PreAuthorize("hasAuthority('device:view')")
    public ApiResponse<PageResult<DeviceVO>> byProduct(@PathVariable Long productId,
            @RequestParam(required = false) Integer page, @RequestParam(required = false) Integer size) {
        return ApiResponse.ok(service.pageByProduct(productId, page, size));
    }

    @GetMapping("/devices/{id}")
    @PreAuthorize("hasAuthority('device:view')")
    public ApiResponse<DeviceVO> get(@PathVariable Long id) {
        return ApiResponse.ok(service.get(id));
    }

    @PostMapping("/devices")
    @PreAuthorize("hasAuthority('device:create')")
    @OpLog(module = "设备", action = "新增设备")
    public ApiResponse<DeviceVO> create(@Valid @RequestBody DeviceRequest req) {
        return ApiResponse.ok(service.create(req));
    }

    @PutMapping("/devices/{id}")
    @PreAuthorize("hasAuthority('device:update')")
    @OpLog(module = "设备", action = "编辑设备")
    public ApiResponse<DeviceVO> update(@PathVariable Long id, @Valid @RequestBody DeviceRequest req) {
        return ApiResponse.ok(service.update(id, req));
    }

    @DeleteMapping("/devices/{id}")
    @PreAuthorize("hasAuthority('device:delete')")
    @OpLog(module = "设备", action = "删除设备")
    public ApiResponse<Void> delete(@PathVariable Long id) {
        service.delete(id);
        return ApiResponse.ok();
    }

    @PostMapping("/devices/{id}/rotate-secret")
    @PreAuthorize("hasAuthority('device:credential')")
    @OpLog(module = "设备", action = "轮换设备密钥")
    public ApiResponse<Map<String, String>> rotate(@PathVariable Long id) {
        return ApiResponse.ok(Map.of("secret", service.rotateSecret(id)));
    }

    @PutMapping("/devices/{id}/enabled")
    @PreAuthorize("hasAuthority('device:update')")
    @OpLog(module = "设备", action = "启用/禁用设备")
    public ApiResponse<DeviceVO> enabled(@PathVariable Long id, @Valid @RequestBody DeviceRequest.Enabled req) {
        return ApiResponse.ok(service.setEnabled(id, req.enabled()));
    }
}
