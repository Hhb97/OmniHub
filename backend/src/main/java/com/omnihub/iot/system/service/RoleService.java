package com.omnihub.iot.system.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.omnihub.iot.common.BizException;
import com.omnihub.iot.security.CurrentUser;
import com.omnihub.iot.system.dto.RoleRequest;
import com.omnihub.iot.system.entity.SysPermission;
import com.omnihub.iot.system.entity.SysRole;
import com.omnihub.iot.system.mapper.SysPermissionMapper;
import com.omnihub.iot.system.mapper.SysRoleMapper;
import com.omnihub.iot.system.vo.PermissionNode;
import com.omnihub.iot.system.vo.RoleVO;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class RoleService {

    private static final Set<String> BUILT_IN = Set.of("ADMIN", "OPERATOR", "VIEWER");

    private final SysRoleMapper roleMapper;
    private final SysPermissionMapper permMapper;
    private final JdbcTemplate jdbc;

    public RoleService(SysRoleMapper roleMapper, SysPermissionMapper permMapper, JdbcTemplate jdbc) {
        this.roleMapper = roleMapper;
        this.permMapper = permMapper;
        this.jdbc = jdbc;
    }

    public List<RoleVO> list() {
        List<SysRole> roles = roleMapper.selectList(new LambdaQueryWrapper<SysRole>()
                .eq(SysRole::getTenantId, CurrentUser.tenantId()).orderByAsc(SysRole::getId));
        Map<Long, List<String>> perms = new HashMap<>();
        jdbc.query("SELECT role_id, permission_code FROM sys_role_permission ORDER BY permission_code",
                rs -> {
                    perms.computeIfAbsent(rs.getLong(1), k -> new ArrayList<>()).add(rs.getString(2));
                });
        return roles.stream().map(r -> toVO(r, perms.getOrDefault(r.getId(), List.of()))).toList();
    }

    @Transactional
    public RoleVO create(RoleRequest req) {
        Long tenantId = CurrentUser.tenantId();
        if (roleMapper.selectCount(new LambdaQueryWrapper<SysRole>().eq(SysRole::getTenantId, tenantId)
                .eq(SysRole::getCode, req.code())) > 0) {
            throw BizException.conflict("角色编码已存在");
        }
        SysRole r = new SysRole();
        r.setTenantId(tenantId);
        r.setCode(req.code());
        r.setName(req.name());
        r.setDescription(req.description());
        roleMapper.insert(r);
        savePermissions(r.getId(), req.permissions());
        return toVO(r, req.permissions() == null ? List.of() : req.permissions());
    }

    @Transactional
    public RoleVO update(Long id, RoleRequest req) {
        SysRole r = require(id);
        if ("ADMIN".equals(r.getCode())) {
            throw BizException.badRequest("内置管理员角色不可修改");
        }
        r.setName(req.name());
        r.setDescription(req.description());
        roleMapper.updateById(r);
        savePermissions(id, req.permissions());
        return toVO(r, req.permissions() == null ? List.of() : req.permissions());
    }

    @Transactional
    public void delete(Long id) {
        SysRole r = require(id);
        if (BUILT_IN.contains(r.getCode())) {
            throw BizException.badRequest("内置角色不可删除");
        }
        Integer used = jdbc.queryForObject("SELECT count(*) FROM sys_user_role WHERE role_id = ?", Integer.class, id);
        if (used != null && used > 0) {
            throw BizException.conflict("角色仍被用户使用，请先解除关联");
        }
        jdbc.update("DELETE FROM sys_role_permission WHERE role_id = ?", id);
        roleMapper.deleteById(id);
    }

    public List<PermissionNode> permissionTree() {
        List<SysPermission> all = permMapper.selectList(null);
        all.sort((a, b) -> Integer.compare(a.getSort(), b.getSort()));
        Map<String, PermissionNode> roots = new java.util.LinkedHashMap<>();
        for (SysPermission p : all) {
            if (p.getParentCode() == null) {
                roots.put(p.getCode(), new PermissionNode(p.getCode(), p.getName(), p.getType(), new ArrayList<>()));
            }
        }
        for (SysPermission p : all) {
            if (p.getParentCode() != null && roots.containsKey(p.getParentCode())) {
                roots.get(p.getParentCode()).children()
                        .add(new PermissionNode(p.getCode(), p.getName(), p.getType(), new ArrayList<>()));
            }
        }
        return new ArrayList<>(roots.values());
    }

    private SysRole require(Long id) {
        SysRole r = roleMapper.selectById(id);
        if (r == null || !r.getTenantId().equals(CurrentUser.tenantId())) {
            throw BizException.notFound("角色不存在");
        }
        return r;
    }

    private void savePermissions(Long roleId, List<String> codes) {
        jdbc.update("DELETE FROM sys_role_permission WHERE role_id = ?", roleId);
        if (codes == null) {
            return;
        }
        Set<String> valid = new java.util.HashSet<>(
                jdbc.queryForList("SELECT code FROM sys_permission WHERE type = 'API'", String.class));
        for (String c : codes.stream().distinct().toList()) {
            if (!valid.contains(c)) {
                throw BizException.badRequest("未知权限码: " + c);
            }
            jdbc.update("INSERT INTO sys_role_permission (role_id, permission_code) VALUES (?, ?)", roleId, c);
        }
    }

    private static RoleVO toVO(SysRole r, List<String> perms) {
        return new RoleVO(r.getId(), r.getCode(), r.getName(), r.getDescription(), perms);
    }
}
