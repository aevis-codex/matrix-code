package com.matrixcode.workbench.domain;

import com.matrixcode.deployment.domain.ComposeEnvironmentStatus;
import com.matrixcode.deployment.domain.ComposeOperationRecord;

public record ComposeRuntimeView(
        String environmentId,
        String targetId,
        ComposeEnvironmentStatus status,
        String composeFilePath,
        String projectName,
        String serviceName,
        ComposeOperationRecord latestOperation
) {
}
