package com.matrixcode.deployment.application;

import com.matrixcode.deployment.domain.DeploymentHealthStatus;

public record DeploymentHealthProbe(
        DeploymentHealthStatus status,
        Integer httpStatus,
        long durationMillis,
        String summary
) {
}
