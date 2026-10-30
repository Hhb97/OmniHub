package com.omnihub.iot.system.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.omnihub.iot.common.BizException;
import com.omnihub.iot.identity.core.CurrentUser;
import com.omnihub.iot.identity.core.JwtService;
import com.omnihub.iot.identity.core.LoginUser;
import com.omnihub.iot.system.dto.LoginRequest;
import com.omnihub.iot.system.dto.PasswordChangeRequest;
import com.omnihub.iot.system.entity.LoginLog;
import com.omnihub.iot.system.entity.SysUser;
import com.omnihub.iot.system.mapper.LoginLogMapper;
import com.omnihub.iot.system.mapper.SysUserMapper;
import com.omnihub.iot.system.vo.LoginResult;
import com.omnihub.iot.system.vo.UserInfo;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

@Service
public class AuthService {

    private static final int MAX_FAILURES = 5;
    private static final long WINDOW_SECONDS = 300;

    private final SysUserMapper userMapper;
    private final LoginLogMapper loginLogMapper;
    private final PasswordEncoder encoder;
    private final JwtService jwtService;
    private final AccountService accountService;
    /** username -> recent failure timestamps (simple brute force throttle, single instance only). */
    private final Map<String, Deque<Instant>> failures = new ConcurrentHashMap<>();

    public AuthService(SysUserMapper userMapper, LoginLogMapper loginLogMapper, PasswordEncoder encoder,
            JwtService jwtService, AccountService accountService) {
        this.userMapper = userMapper;
        this.loginLogMapper = loginLogMapper;
        this.encoder = encoder;
        this.jwtService = jwtService;
        this.accountService = accountService;
    }

    public LoginResult login(LoginRequest req, String ip) {
        String username = req.username().trim();
        if (isLocked(username)) {
            record(username, ip, false, "登录失败次数过多，已临时锁定");
            throw new BizException(HttpStatus.TOO_MANY_REQUESTS, "登录失败次数过多，请5分钟后再试");
        }
        SysUser user = userMapper.selectOne(new LambdaQueryWrapper<SysUser>().eq(SysUser::getUsername, username));
        // same message for unknown user / wrong password / disabled: do not reveal which
        if (user == null || !Boolean.TRUE.equals(user.getEnabled())
                || !encoder.matches(req.password(), user.getPasswordHash())) {
            registerFailure(username);
            record(username, ip, false, "用户名或密码错误");
            throw new BizException(HttpStatus.UNAUTHORIZED, "用户名或密码错误");
        }
        failures.remove(username);
        record(username, ip, true, "登录成功");
        LoginUser lu = accountService.loadLoginUser(user.getId()).orElseThrow();
        String token = jwtService.issue(user.getId(), user.getTenantId(), user.getUsername());
        return new LoginResult(token, jwtService.ttlSeconds(), UserInfo.of(lu));
    }

    public void logout(String token, String username, String ip) {
        if (token != null) {
            jwtService.revoke(token);
        }
        record(username, ip, true, "退出登录");
    }

    public void changePassword(PasswordChangeRequest req) {
        LoginUser lu = CurrentUser.get();
        SysUser user = userMapper.selectById(lu.userId());
        if (!encoder.matches(req.oldPassword(), user.getPasswordHash())) {
            throw BizException.badRequest("原密码错误");
        }
        SysUser upd = new SysUser();
        upd.setId(user.getId());
        upd.setPasswordHash(encoder.encode(req.newPassword()));
        userMapper.updateById(upd);
    }

    private boolean isLocked(String username) {
        Deque<Instant> q = failures.get(username);
        if (q == null) {
            return false;
        }
        synchronized (q) {
            Instant cutoff = Instant.now().minusSeconds(WINDOW_SECONDS);
            while (!q.isEmpty() && q.peekFirst().isBefore(cutoff)) {
                q.pollFirst();
            }
            return q.size() >= MAX_FAILURES;
        }
    }

    private void registerFailure(String username) {
        Deque<Instant> q = failures.computeIfAbsent(username, k -> new ArrayDeque<>());
        synchronized (q) {
            q.addLast(Instant.now());
        }
    }

    private void record(String username, String ip, boolean ok, String msg) {
        LoginLog l = new LoginLog();
        l.setUsername(username);
        l.setIp(ip);
        l.setSuccess(ok);
        l.setMessage(msg);
        l.setCreatedAt(Instant.now());
        loginLogMapper.insert(l);
    }
}
