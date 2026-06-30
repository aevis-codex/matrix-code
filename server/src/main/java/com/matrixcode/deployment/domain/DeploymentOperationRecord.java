package com.matrixcode.deployment.domain;

import java.time.Instant;

public record DeploymentOperationRecord(
        String id,
        String projectId,
        String targetId,
        String actorId,
        DeploymentOperationType type,
        DeploymentOperationStatus status,
        String note,
        Instant createdAt
) {
    public DeploymentOperationRecord {
        actorId = actorId == null ? "" : actorId.trim();
        note = note == null ? "" : note.trim();
    }
}
