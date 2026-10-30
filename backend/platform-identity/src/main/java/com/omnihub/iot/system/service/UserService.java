package com.omnihub.iot.system.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.omnihub.iot.common.BizException;
import com.omnihub.iot.common.PageQuery;
import com.omnihub.iot.common.PageResult;
import com.omnihub.iot.identity.core.CurrentUser;
import com.omnihub.iot.system.dto.UserCreateRequest;
import com.omnihub.iot.system.dto.UserUpdateRequest;
import com.omnihub.iot.system.entity.SysUser;
import com.omnihub.iot.system.mapper.SysUserMapper;
import com.omnihub.iot.system.vo.UserVO;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class UserService {

    private final SysUserMapper mapper;
    private final JdbcTemplate jdbc;
    private final PasswordEncoder encoder;

    public UserService(SysUserMapper mapper, JdbcTemplate jdbc, PasswordEncoder encoder) {
        this.mapper = mapper;
        this.jdbc = jdbc;
        this.encoder = encoder;
    }

    public PageResult<UserVO> page(Integer page, Integer size, String keyword) {
        LambdaQueryWrapper<SysUser> q = new LambdaQueryWrapper<SysUser>()
                .eq(SysUser::getTenantId, CurrentUser.tenantId()).orderByDesc(SysUser::getId);
        if (keyword != null && !keyword.isBlank()) {
            q.and(w -> w.like(SysUser::getUsername, keyword).or().like(SysUser::getNickname, keyword));
        }
        Page<SysUser> p = mapper.selectPage(PageQuery.of(page, size), q);
        Map<Long, List<String>> roles = rolesOf(p.getRecords().stream().map(SysUser::getId).toList());
        return PageResult.of(p, u -> toVO(u, roles.getOrDefault(u.getId(), List.of())));
    }

    @Transactional
    public UserVO create(UserCreateRequest req) {
        Long tenantId = CurrentUser.tenantId();
        if (mapper.selectCount(new LambdaQueryWrapper<SysUser>().eq(SysUser::getUsername, req.username())) > 0) {
            throw BizException.conflict("用户名已存在");
        }
        SysUser u = new SysUser();
        u.setTenantId(tenantId);
        u.setUsername(req.username());
        u.setPasswordHash(encoder.encode(req.password()));
        u.setNickname(req.nickname());
        u.setEmail(req.email());
        u.setEnabled(true);
        mapper.insert(u);
        assignRoles(u.getId(), req.roleIds());
        return toVO(u, rolesOf(List.of(u.getId())).getOrDefault(u.getId(), List.of()));
    }

    @Transactional
    public UserVO update(Long id, UserUpdateRequest req) {
        SysUser u = require(id);
        if (id.equals(CurrentUser.get().userId()) && Boolean.FALSE.equals(req.enabled())) {
            throw BizException.badRequest("不能禁用当前登录账号");
        }
        u.setNickname(req.nickname());
        u.setEmail(req.email());
        if (req.enabled() != null) {
            u.setEnabled(req.enabled());
        }
        mapper.updateById(u);
        if (req.roleIds() != null) {
            assignRoles(id, req.roleIds());
        }
        return toVO(u, rolesOf(List.of(id)).getOrDefault(id, List.of()));
    }

    @Transactional
    public void delete(Long id) {
        if (id.equals(CurrentUser.get().userId())) {
            throw BizException.badRequest("不能删除当前登录账号");
        }
        require(id);
        jdbc.update("DELETE FROM sys_user_role WHERE user_id = ?", id);
        mapper.deleteById(id);
    }

    public void resetPassword(Long id, String newPassword) {
        SysUser u = require(id);
        SysUser upd = new SysUser();
        upd.setId(u.getId());
        upd.setPasswordHash(encoder.encode(newPassword));
        mapper.updateById(upd);
    }

    private SysUser require(Long id) {
        SysUser u = mapper.selectById(id);
        if (u == null || !u.getTenantId().equals(CurrentUser.tenantId())) {
            throw BizException.notFound("用户不存在");
        }
        return u;
    }

    private void assignRoles(Long userId, List<Long> roleIds) {
        jdbc.update("DELETE FROM sys_user_role WHERE user_id = ?", userId);
        if (roleIds == null) {
            return;
        }
        for (Long rid : roleIds.stream().distinct().toList()) {
            Integer ok = jdbc.queryForObject(
                    "SELECT count(*) FROM sys_role WHERE id = ? AND tenant_id = ? AND deleted = 0", Integer.class, rid,
                    CurrentUser.tenantId());
            if (ok == null || ok == 0) {
                throw BizException.badRequest("角色不存在: " + rid);
            }
            jdbc.update("INSERT INTO sys_user_role (user_id, role_id) VALUES (?, ?)", userId, rid);
        }
    }

    private Map<Long, List<String>> rolesOf(List<Long> userIds) {
        if (userIds.isEmpty()) {
            return Map.of();
        }
        String in = userIds.stream().map(String::valueOf).collect(Collectors.joining(","));
        return jdbc.query("""
                SELECT ur.user_id, r.code FROM sys_user_role ur JOIN sys_role r ON r.id = ur.role_id
                WHERE r.deleted = 0 AND ur.user_id IN (""" + in + ") ORDER BY r.code", rs -> {
            Map<Long, List<String>> m = new java.util.HashMap<>();
            while (rs.next()) {
                m.computeIfAbsent(rs.getLong(1), k -> new java.util.ArrayList<>()).add(rs.getString(2));
            }
            return m;
        });
    }

    private static UserVO toVO(SysUser u, List<String> roles) {
        return new UserVO(u.getId(), u.getUsername(), u.getNickname(), u.getEmail(), u.getEnabled(), roles,
                u.getCreatedAt());
    }
}
