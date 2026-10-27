package com.omnihub.iot.system.controller;

import com.omnihub.iot.common.ApiResponse;
import com.omnihub.iot.common.PageResult;
import com.omnihub.iot.system.dto.ResetPasswordRequest;
import com.omnihub.iot.system.dto.UserCreateRequest;
import com.omnihub.iot.system.dto.UserUpdateRequest;
import com.omnihub.iot.system.service.UserService;
import com.omnihub.iot.system.vo.UserVO;
import com.omnihub.iot.system.OpLog;
import jakarta.validation.Valid;
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
@RequestMapping("/api/system/users")
@PreAuthorize("hasAuthority('system:user')")
public class UserController {

    private final UserService service;

    public UserController(UserService service) {
        this.service = service;
    }

    @GetMapping
    public ApiResponse<PageResult<UserVO>> page(@RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer size, @RequestParam(required = false) String keyword) {
        return ApiResponse.ok(service.page(page, size, keyword));
    }

    @PostMapping
    @OpLog(module = "用户", action = "新增用户")
    public ApiResponse<UserVO> create(@Valid @RequestBody UserCreateRequest req) {
        return ApiResponse.ok(service.create(req));
    }

    @PutMapping("/{id}")
    @OpLog(module = "用户", action = "编辑用户")
    public ApiResponse<UserVO> update(@PathVariable Long id, @Valid @RequestBody UserUpdateRequest req) {
        return ApiResponse.ok(service.update(id, req));
    }

    @DeleteMapping("/{id}")
    @OpLog(module = "用户", action = "删除用户")
    public ApiResponse<Void> delete(@PathVariable Long id) {
        service.delete(id);
        return ApiResponse.ok();
    }

    @PutMapping("/{id}/password")
    @OpLog(module = "用户", action = "重置密码")
    public ApiResponse<Void> resetPassword(@PathVariable Long id, @Valid @RequestBody ResetPasswordRequest req) {
        service.resetPassword(id, req.newPassword());
        return ApiResponse.ok();
    }
}
