package com.matrixcode.identity.domain;

import java.time.Instant;

public record UserAuditRecord(
        String id,
        String projectId,
        String actorUserId,
        String actorRole,
        String actionKey,
        String targetType,
        String targetId,
        String decision,
        String summary,
        Instant occurredAt
) {
}
