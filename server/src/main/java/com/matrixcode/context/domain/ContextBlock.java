package com.matrixcode.context.domain;

public record ContextBlock(
        String type,
        String summary,
        boolean allowedByGate
) {
    public ContextBlock {
        if (type == null || type.isBlank()) {
            throw new IllegalArgumentException("上下文类型不能为空");
        }
        if (summary == null || summary.isBlank()) {
            throw new IllegalArgumentException("上下文摘要不能为空");
        }
    }
}
