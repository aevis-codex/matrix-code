package com.matrixcode.modelgateway.domain;

import java.util.Arrays;

public enum ModelRole {
    PRODUCT("产品", "product"),
    DEVELOPER("开发", "developer"),
    TESTER("测试", "tester"),
    OPERATIONS("运维", "operations");

    private final String displayName;
    private final String slug;

    ModelRole(String displayName, String slug) {
        this.displayName = displayName;
        this.slug = slug;
    }

    public String displayName() {
        return displayName;
    }

    public String slug() {
        return slug;
    }

    public static ModelRole fromPath(String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("模型角色不能为空");
        }
        var normalized = value.trim();
        if ("ops".equalsIgnoreCase(normalized)) {
            return OPERATIONS;
        }
        return Arrays.stream(values())
                .filter(role -> role.slug.equalsIgnoreCase(normalized) || role.displayName.equals(normalized))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("模型角色不合法：" + value));
    }
}
