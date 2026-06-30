package com.matrixcode.identity.domain;

import java.time.Instant;

public record ProjectInvitation(
        String id,
        String projectId,
        String inviteeUserId,
        String displayName,
        String roleKey,
        String status,
        String createdByUserId,
        Instant expiresAt,
        Instant acceptedAt,
        Instant createdAt,
        Instant updatedAt
) {
}
