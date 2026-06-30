package com.matrixcode.modelgateway.application;

import com.matrixcode.modelgateway.domain.ModelRole;

public record VectorContextDocument(
        String projectId,
        ModelRole role,
        String type,
        String summary
) {
    public VectorContextDocument {
        projectId = requireText(projectId, "项目编号不能为空");
        if (role == null) {
            throw new IllegalArgumentException("模型角色不能为空");
        }
        type = requireText(type, "向量上下文类型不能为空");
        summary = requireText(summary, "向量上下文摘要不能为空");
    }

    private static String requireText(String value, String message) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(message);
        }
        return value.trim();
    }
}
