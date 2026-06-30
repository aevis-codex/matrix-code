package com.matrixcode.deployment.domain;

import java.time.Instant;

public record DeploymentHealthCheck(
        String id,
        String projectId,
        String targetId,
        String actorId,
        DeploymentHealthStatus status,
        Integer httpStatus,
        long durationMillis,
        String summary,
        Instant checkedAt
) {
    public DeploymentHealthCheck {
        actorId = actorId == null ? "" : actorId.trim();
        summary = summary == null ? "" : summary.trim();
    }
}
