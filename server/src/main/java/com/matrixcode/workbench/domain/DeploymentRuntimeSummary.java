package com.matrixcode.workbench.domain;

import com.matrixcode.deployment.domain.DeploymentHealthCheck;
import com.matrixcode.deployment.domain.DeploymentOperationRecord;

public record DeploymentRuntimeSummary(
        String targetId,
        DeploymentHealthCheck latestHealthCheck,
        DeploymentOperationRecord latestDeploymentOperation,
        DeploymentOperationRecord latestRollbackOperation
) {
}
