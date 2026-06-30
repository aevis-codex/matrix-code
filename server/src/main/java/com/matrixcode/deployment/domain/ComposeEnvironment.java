package com.matrixcode.deployment.domain;

import java.time.Instant;

public record ComposeEnvironment(
        String id,
        String projectId,
        String targetId,
        String workspaceId,
        String composeFilePath,
        String projectName,
        String serviceName,
        ComposeEnvironmentStatus status,
        Instant createdAt,
        Instant updatedAt
) {
    public ComposeEnvironment {
        composeFilePath = composeFilePath == null ? "" : composeFilePath.trim();
        projectName = projectName == null ? "" : projectName.trim();
        serviceName = serviceName == null ? "" : serviceName.trim();
        if (status == null) {
            throw new IllegalArgumentException("Compose 环境状态不能为空");
        }
        if (createdAt == null || updatedAt == null) {
            throw new IllegalArgumentException("Compose 环境时间不能为空");
        }
    }

    public ComposeEnvironment withStatus(ComposeEnvironmentStatus nextStatus) {
        return new ComposeEnvironment(
                id,
                projectId,
                targetId,
                workspaceId,
                composeFilePath,
                projectName,
                serviceName,
                nextStatus,
                createdAt,
                Instant.now()
        );
    }
}
