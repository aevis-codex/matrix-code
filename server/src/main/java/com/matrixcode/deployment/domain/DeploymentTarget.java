package com.matrixcode.deployment.domain;

import java.time.Instant;

public record DeploymentTarget(
        String id,
        String projectId,
        String environmentName,
        String environmentUrl,
        String sshAddress,
        String deployNote,
        String healthCheckUrl,
        String rollbackNote,
        DeploymentTargetStatus status,
        boolean remoteExecuted,
        Instant updatedAt
) {
}
