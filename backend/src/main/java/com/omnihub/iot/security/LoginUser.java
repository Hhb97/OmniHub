package com.omnihub.iot.security;

import java.util.List;
import java.util.Set;

/** Authenticated principal placed in the SecurityContext. */
public record LoginUser(Long userId, Long tenantId, String username, String nickname, List<String> roles,
        Set<String> permissions) {

    public boolean has(String permission) {
        return permissions.contains(permission);
    }
}
