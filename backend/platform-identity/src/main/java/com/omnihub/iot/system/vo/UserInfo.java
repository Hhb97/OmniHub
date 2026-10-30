package com.omnihub.iot.system.vo;

import com.omnihub.iot.identity.core.LoginUser;
import java.util.List;

public record UserInfo(Long id, String username, String nickname, Long tenantId, List<String> roles,
        List<String> permissions) {

    public static UserInfo of(LoginUser u) {
        return new UserInfo(u.userId(), u.username(), u.nickname(), u.tenantId(), u.roles(),
                u.permissions().stream().sorted().toList());
    }
}
