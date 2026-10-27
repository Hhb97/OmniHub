package com.omnihub.iot.system.controller;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.omnihub.iot.common.ApiResponse;
import com.omnihub.iot.common.BizException;
import com.omnihub.iot.common.PageQuery;
import com.omnihub.iot.common.PageResult;
import com.omnihub.iot.security.CurrentUser;
import com.omnihub.iot.system.OpLog;
import com.omnihub.iot.system.entity.LoginLog;
import com.omnihub.iot.system.entity.OperationLog;
import com.omnihub.iot.system.mapper.LoginLogMapper;
import com.omnihub.iot.system.mapper.OperationLogMapper;
import com.omnihub.iot.system.service.ConfigService;
import com.omnihub.iot.system.vo.ConfigVO;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.util.List;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Operation logs, login logs and platform configuration. */
@RestController
@RequestMapping("/api/system")
public class SystemController {

    public record ConfigUpdate(@NotBlank String key, @NotBlank @Size(max = 512) String value) {
    }

    private final OperationLogMapper opLogMapper;
    private final LoginLogMapper loginLogMapper;
    private final ConfigService configService;

    public SystemController(OperationLogMapper opLogMapper, LoginLogMapper loginLogMapper,
            ConfigService configService) {
        this.opLogMapper = opLogMapper;
        this.loginLogMapper = loginLogMapper;
        this.configService = configService;
    }

    @GetMapping("/logs/operation")
    @PreAuthorize("hasAuthority('system:log')")
    public ApiResponse<PageResult<OperationLog>> operationLogs(@RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer size, @RequestParam(required = false) String keyword,
            @RequestParam(required = false) String username) {
        LambdaQueryWrapper<OperationLog> q = new LambdaQueryWrapper<OperationLog>()
                .eq(OperationLog::getTenantId, CurrentUser.tenantId()).orderByDesc(OperationLog::getId);
        if (username != null && !username.isBlank()) {
            q.eq(OperationLog::getUsername, username);
        }
        if (keyword != null && !keyword.isBlank()) {
            q.and(w -> w.like(OperationLog::getModule, keyword).or().like(OperationLog::getAction, keyword)
                    .or().like(OperationLog::getPath, keyword));
        }
        return ApiResponse.ok(PageResult.of(opLogMapper.selectPage(PageQuery.of(page, size), q), o -> o));
    }

    @GetMapping("/logs/login")
    @PreAuthorize("hasAuthority('system:log')")
    public ApiResponse<PageResult<LoginLog>> loginLogs(@RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer size, @RequestParam(required = false) String username) {
        LambdaQueryWrapper<LoginLog> q = new LambdaQueryWrapper<LoginLog>().orderByDesc(LoginLog::getId);
        if (username != null && !username.isBlank()) {
            q.eq(LoginLog::getUsername, username);
        }
        return ApiResponse.ok(PageResult.of(loginLogMapper.selectPage(PageQuery.of(page, size), q), o -> o));
    }

    @GetMapping("/configs")
    @PreAuthorize("hasAuthority('system:config')")
    public ApiResponse<List<ConfigVO>> configs() {
        return ApiResponse.ok(configService.list().stream()
                .map(c -> new ConfigVO(c.getConfigKey(), c.getConfigValue(), c.getDescription())).toList());
    }

    @PutMapping("/configs")
    @PreAuthorize("hasAuthority('system:config')")
    @OpLog(module = "系统配置", action = "修改配置")
    public ApiResponse<Void> updateConfig(@Valid @RequestBody ConfigUpdate req) {
        if (req.key().endsWith(".seconds") || req.key().endsWith(".days")) {
            try {
                if (Integer.parseInt(req.value().trim()) <= 0) {
                    throw BizException.badRequest("该配置必须为正整数");
                }
            } catch (NumberFormatException e) {
                throw BizException.badRequest("该配置必须为正整数");
            }
        }
        configService.update(req.key(), req.value());
        return ApiResponse.ok();
    }
}
