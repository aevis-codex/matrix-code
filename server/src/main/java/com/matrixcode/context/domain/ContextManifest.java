package com.matrixcode.context.domain;

import java.util.List;

public record ContextManifest(
        String role,
        List<ContextBlock> blocks,
        List<String> omittedTypes
) {
    public ContextManifest {
        if (role == null || role.isBlank()) {
            throw new IllegalArgumentException("角色不能为空");
        }
        if (blocks == null) {
            throw new IllegalArgumentException("允许上下文不能为空");
        }
        if (omittedTypes == null) {
            throw new IllegalArgumentException("省略上下文类型不能为空");
        }
        blocks = List.copyOf(blocks);
        omittedTypes = List.copyOf(omittedTypes);
    }
}
