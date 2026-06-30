package com.matrixcode.deployment.domain;

import java.time.Instant;

public record ComposeOperationRecord(
        String id,
        String projectId,
        String environmentId,
        String actorId,
        ComposeOperationType type,
        ComposeOperationStatus status,
        String summary,
        String logExcerpt,
        Instant createdAt
) {
    public ComposeOperationRecord {
        actorId = actorId == null ? "" : actorId.trim();
        summary = summary == null ? "" : summary.trim();
        logExcerpt = logExcerpt == null ? "" : logExcerpt.trim();
        if (type == null) {
            throw new IllegalArgumentException("Compose 操作类型不能为空");
        }
        if (status == null) {
            throw new IllegalArgumentException("Compose 操作状态不能为空");
        }
        if (createdAt == null) {
            throw new IllegalArgumentException("Compose 操作时间不能为空");
        }
    }
}
