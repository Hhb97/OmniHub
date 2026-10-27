package com.omnihub.iot.system.vo;

import java.util.List;

public record PermissionNode(String code, String name, String type, List<PermissionNode> children) {
}
