package com.matrixcode.identity.domain;

import java.time.Instant;

public record ProjectMember(
        String id,
        String projectId,
        String userId,
        String roleKey,
        String status,
        Instant joinedAt,
        Instant createdAt,
        Instant updatedAt
) {
}
