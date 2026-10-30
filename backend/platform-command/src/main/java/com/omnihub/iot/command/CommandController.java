package com.omnihub.iot.command;

import com.omnihub.iot.common.ApiResponse;
import com.omnihub.iot.common.PageResult;
import com.omnihub.iot.system.OpLog;
import jakarta.validation.Valid;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api")
public class CommandController {

    private final CommandService service;

    public CommandController(CommandService service) {
        this.service = service;
    }

    @PostMapping("/devices/{id}/commands")
    @PreAuthorize("hasAuthority('device:control')")
    @OpLog(module = "设备控制", action = "下发指令")
    public ApiResponse<CommandVO> send(@PathVariable Long id, @Valid @RequestBody CommandRequest req) {
        return ApiResponse.ok(service.send(id, req));
    }

    @GetMapping("/devices/{id}/commands")
    @PreAuthorize("hasAuthority('device:view')")
    public ApiResponse<PageResult<CommandVO>> page(@PathVariable Long id, @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer size, @RequestParam(required = false) String status) {
        return ApiResponse.ok(service.page(id, page, size, status));
    }

    @GetMapping("/commands/{id}")
    @PreAuthorize("hasAuthority('device:view')")
    public ApiResponse<CommandVO> get(@PathVariable Long id) {
        return ApiResponse.ok(service.get(id));
    }
}
