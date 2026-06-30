package com.matrixcode.approval.domain;

import java.time.Instant;

public record AuditRecord(
        String id,
        String taskId,
        String actorId,
        String toolType,
        String workspacePath,
        String summary,
        ApprovalDecision decision,
        Instant occurredAt
) {
}
