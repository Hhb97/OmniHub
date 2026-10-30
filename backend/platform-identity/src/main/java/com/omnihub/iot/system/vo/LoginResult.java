package com.omnihub.iot.system.vo;

public record LoginResult(String token, long expiresIn, UserInfo user) {
}
