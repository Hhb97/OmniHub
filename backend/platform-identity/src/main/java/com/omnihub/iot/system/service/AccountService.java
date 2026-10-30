package com.omnihub.iot.system.service;

import com.omnihub.iot.identity.core.LoginUser;
import com.omnihub.iot.system.entity.SysUser;
import com.omnihub.iot.system.mapper.SysUserMapper;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/** Resolves a user id to a {@link LoginUser} with current roles and permissions. */
@Service
public class AccountService {

    private final SysUserMapper userMapper;
    private final JdbcTemplate jdbc;

    public AccountService(SysUserMapper userMapper, JdbcTemplate jdbc) {
        this.userMapper = userMapper;
        this.jdbc = jdbc;
    }

    public Optional<LoginUser> loadLoginUser(long userId) {
        SysUser u = userMapper.selectById(userId);
        if (u == null || !Boolean.TRUE.equals(u.getEnabled())) {
            return Optional.empty();
        }
        List<String> roles = jdbc.queryForList("""
                SELECT r.code FROM sys_role r JOIN sys_user_role ur ON ur.role_id = r.id
                WHERE ur.user_id = ? AND r.deleted = 0 ORDER BY r.code""", String.class, userId);
        Set<String> perms = new HashSet<>(jdbc.queryForList("""
                SELECT DISTINCT rp.permission_code FROM sys_role_permission rp
                JOIN sys_user_role ur ON ur.role_id = rp.role_id
                JOIN sys_role r ON r.id = rp.role_id AND r.deleted = 0
                WHERE ur.user_id = ?""", String.class, userId));
        return Optional.of(new LoginUser(u.getId(), u.getTenantId(), u.getUsername(), u.getNickname(), roles, perms));
    }
}
