package com.matrixcode.realtime.domain;

import java.time.Instant;
import java.util.UUID;

public record ProjectEvent(
        String id,
        String projectId,
        String type,
        String message,
        Instant occurredAt,
        String sourceRole,
        String sourceId
) {
    public ProjectEvent {
        id = requireText(id, "id");
        projectId = requireText(projectId, "projectId");
        type = requireText(type, "type");
        message = requireText(message, "message");
        if (occurredAt == null) {
            throw new IllegalArgumentException("occurredAt 不能为空");
        }
        sourceRole = optionalText(sourceRole);
        sourceId = optionalText(sourceId);
    }

    public ProjectEvent(String id, String projectId, String type, String message, Instant occurredAt) {
        this(id, projectId, type, message, occurredAt, "", "");
    }

    public ProjectEvent(String projectId, String type, String message) {
        this(UUID.randomUUID().toString(), projectId, type, message, Instant.now(), "", "");
    }

    public ProjectEvent(String projectId, String type, String message, String sourceRole, String sourceId) {
        this(UUID.randomUUID().toString(), projectId, type, message, Instant.now(), sourceRole, sourceId);
    }

    private static String requireText(String value, String fieldName) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(fieldName + " 不能为空");
        }
        return value;
    }

    private static String optionalText(String value) {
        return value == null || value.isBlank() ? "" : value.trim();
    }
}
