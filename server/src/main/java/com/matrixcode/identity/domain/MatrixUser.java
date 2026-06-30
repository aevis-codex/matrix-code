package com.matrixcode.identity.domain;

import java.time.Instant;

public record MatrixUser(
        String id,
        String username,
        String displayName,
        String email,
        String status,
        Instant createdAt,
        Instant updatedAt
) {
}
