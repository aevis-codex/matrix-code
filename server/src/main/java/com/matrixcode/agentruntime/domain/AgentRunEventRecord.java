package com.matrixcode.agentruntime.domain;

import java.time.Instant;

public record AgentRunEventRecord(
        String id,
        String runId,
        String projectId,
        String eventType,
        String eventTitle,
        String eventPayload,
        Instant occurredAt
) {
    public AgentRunEventRecord {
        requireNotBlank(id, "id");
        requireNotBlank(runId, "runId");
        requireNotBlank(projectId, "projectId");
        requireNotBlank(eventType, "eventType");
        requireNotBlank(eventTitle, "eventTitle");
        occurredAt = occurredAt == null ? Instant.EPOCH : occurredAt;
    }

    private static void requireNotBlank(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " 不能为空");
        }
    }
}
