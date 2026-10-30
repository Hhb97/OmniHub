package com.omnihub.iot.system.vo;

import java.util.List;

public record RoleVO(Long id, String code, String name, String description, List<String> permissions) {
}
