package com.matrixcode.deployment.application;

import com.matrixcode.deployment.domain.DeploymentOperationRecord;

import java.util.List;

public record DeploymentReleaseAuditImportResult(
        int importedCount,
        int skippedCount,
        List<DeploymentOperationRecord> records
) {
    public DeploymentReleaseAuditImportResult {
        records = records == null ? List.of() : List.copyOf(records);
    }
}
