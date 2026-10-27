package com.omnihub.iot.system.controller;

import com.omnihub.iot.common.ApiResponse;
import com.omnihub.iot.security.CurrentUser;
import com.omnihub.iot.system.dto.LoginRequest;
import com.omnihub.iot.system.dto.PasswordChangeRequest;
import com.omnihub.iot.system.service.AuthService;
import com.omnihub.iot.system.vo.LoginResult;
import com.omnihub.iot.system.vo.UserInfo;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/auth")
public class AuthController {

    private final AuthService authService;

    public AuthController(AuthService authService) {
        this.authService = authService;
    }

    @PostMapping("/login")
    public ApiResponse<LoginResult> login(@Valid @RequestBody LoginRequest req, HttpServletRequest http) {
        return ApiResponse.ok(authService.login(req, http.getRemoteAddr()));
    }

    @PostMapping("/logout")
    public ApiResponse<Void> logout(HttpServletRequest http) {
        String header = http.getHeader("Authorization");
        String token = header != null && header.startsWith("Bearer ") ? header.substring(7) : null;
        authService.logout(token, CurrentUser.username(), http.getRemoteAddr());
        return ApiResponse.ok();
    }

    @GetMapping("/me")
    public ApiResponse<UserInfo> me() {
        return ApiResponse.ok(UserInfo.of(CurrentUser.get()));
    }

    @PutMapping("/password")
    public ApiResponse<Void> password(@Valid @RequestBody PasswordChangeRequest req) {
        authService.changePassword(req);
        return ApiResponse.ok();
    }
}
