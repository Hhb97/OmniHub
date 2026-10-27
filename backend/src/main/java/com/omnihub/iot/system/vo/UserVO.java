package com.omnihub.iot.system.vo;

import java.time.Instant;
import java.util.List;

public record UserVO(Long id, String username, String nickname, String email, Boolean enabled, List<String> roles,
        Instant createdAt) {
}
