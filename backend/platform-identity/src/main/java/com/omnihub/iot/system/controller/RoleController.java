package com.omnihub.iot.system.controller;

import com.omnihub.iot.common.ApiResponse;
import com.omnihub.iot.system.dto.RoleRequest;
import com.omnihub.iot.system.OpLog;
import com.omnihub.iot.system.service.RoleService;
import com.omnihub.iot.system.vo.PermissionNode;
import com.omnihub.iot.system.vo.RoleVO;
import jakarta.validation.Valid;
import java.util.List;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/system")
public class RoleController {

    private final RoleService service;

    public RoleController(RoleService service) {
        this.service = service;
    }

    // roles are also needed by the user form, hence user OR role permission
    @GetMapping("/roles")
    @PreAuthorize("hasAnyAuthority('system:role','system:user')")
    public ApiResponse<List<RoleVO>> list() {
        return ApiResponse.ok(service.list());
    }

    @PostMapping("/roles")
    @PreAuthorize("hasAuthority('system:role')")
    @OpLog(module = "角色", action = "新增角色")
    public ApiResponse<RoleVO> create(@Valid @RequestBody RoleRequest req) {
        return ApiResponse.ok(service.create(req));
    }

    @PutMapping("/roles/{id}")
    @PreAuthorize("hasAuthority('system:role')")
    @OpLog(module = "角色", action = "编辑角色")
    public ApiResponse<RoleVO> update(@PathVariable Long id, @Valid @RequestBody RoleRequest req) {
        return ApiResponse.ok(service.update(id, req));
    }

    @DeleteMapping("/roles/{id}")
    @PreAuthorize("hasAuthority('system:role')")
    @OpLog(module = "角色", action = "删除角色")
    public ApiResponse<Void> delete(@PathVariable Long id) {
        service.delete(id);
        return ApiResponse.ok();
    }

    @GetMapping("/permissions")
    @PreAuthorize("hasAuthority('system:role')")
    public ApiResponse<List<PermissionNode>> permissions() {
        return ApiResponse.ok(service.permissionTree());
    }
}
