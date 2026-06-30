package com.matrixcode.localexecution.domain;

import java.time.Instant;

public record WorkspaceAuthorization(
        String id,
        String projectId,
        String name,
        String rootPath,
        WorkspaceStatus status,
        Instant createdAt,
        Instant lastAccessedAt
) {
    public WorkspaceAuthorization {
        id = requireText(id, "工作区编号不能为空");
        projectId = requireText(projectId, "项目编号不能为空");
        name = requireText(name, "工作区名称不能为空");
        rootPath = requireText(rootPath, "工作区路径不能为空");
        if (status == null) {
            throw new IllegalArgumentException("工作区状态不能为空");
        }
        if (createdAt == null) {
            throw new IllegalArgumentException("授权创建时间不能为空");
        }
        if (lastAccessedAt == null) {
            throw new IllegalArgumentException("最近访问时间不能为空");
        }
    }

    public WorkspaceAuthorization withStatus(WorkspaceStatus nextStatus) {
        return new WorkspaceAuthorization(id, projectId, name, rootPath, nextStatus, createdAt, Instant.now());
    }

    public WorkspaceAuthorization accessedNow() {
        return new WorkspaceAuthorization(id, projectId, name, rootPath, status, createdAt, Instant.now());
    }

    private static String requireText(String value, String message) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(message);
        }
        return value.trim();
    }
}
