package com.omnihub.iot.identity.core;

import com.omnihub.iot.common.BizException;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

public final class CurrentUser {

    private CurrentUser() {
    }

    public static LoginUser get() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth != null && auth.getPrincipal() instanceof LoginUser u) {
            return u;
        }
        throw new BizException(HttpStatus.UNAUTHORIZED, "未登录或登录已过期");
    }

    public static Long tenantId() {
        return get().tenantId();
    }

    public static String username() {
        return get().username();
    }
}
