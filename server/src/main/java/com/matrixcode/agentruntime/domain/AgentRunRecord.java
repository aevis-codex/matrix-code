package com.matrixcode.agentruntime.domain;

import java.time.Instant;

public record AgentRunRecord(
        String id,
        String projectId,
        String roleKey,
        String agentKind,
        String actorUserId,
        String providerId,
        String modelName,
        AgentRunStatus status,
        String goal,
        String summary,
        String failureSummary,
        boolean retryable,
        String retryOfRunId,
        Instant createdAt,
        Instant startedAt,
        Instant finishedAt,
        Instant updatedAt,
        String claimedByUserId,
        Instant claimedAt,
        Instant claimExpiresAt
) {
    public AgentRunRecord(
            String id,
            String projectId,
            String roleKey,
            String agentKind,
            String actorUserId,
            String providerId,
            String modelName,
            AgentRunStatus status,
            String goal,
            String summary,
            Instant createdAt,
            Instant startedAt,
            Instant finishedAt,
            Instant updatedAt
    ) {
        this(
                id,
                projectId,
                roleKey,
                agentKind,
                actorUserId,
                providerId,
                modelName,
                status,
                goal,
                summary,
                "",
                false,
                "",
                createdAt,
                startedAt,
                finishedAt,
                updatedAt,
                null,
                null,
                null
        );
    }

    public AgentRunRecord(
            String id,
            String projectId,
            String roleKey,
            String agentKind,
            String actorUserId,
            String providerId,
            String modelName,
            AgentRunStatus status,
            String goal,
            String summary,
            String failureSummary,
            boolean retryable,
            String retryOfRunId,
            Instant createdAt,
            Instant startedAt,
            Instant finishedAt,
            Instant updatedAt
    ) {
        this(
                id,
                projectId,
                roleKey,
                agentKind,
                actorUserId,
                providerId,
                modelName,
                status,
                goal,
                summary,
                failureSummary,
                retryable,
                retryOfRunId,
                createdAt,
                startedAt,
                finishedAt,
                updatedAt,
                null,
                null,
                null
        );
    }

    public AgentRunRecord {
        requireNotBlank(id, "id");
        requireNotBlank(projectId, "projectId");
        requireNotBlank(roleKey, "roleKey");
        requireNotBlank(agentKind, "agentKind");
        requireNotBlank(actorUserId, "actorUserId");
        requireNotBlank(providerId, "providerId");
        requireNotBlank(modelName, "modelName");
        requireNotBlank(goal, "goal");
        if (status == null) {
            throw new IllegalArgumentException("status 不能为空");
        }
        summary = trimToEmpty(summary);
        failureSummary = trimToEmpty(failureSummary);
        retryOfRunId = trimToEmpty(retryOfRunId);
        claimedByUserId = trimToNull(claimedByUserId);
        createdAt = createdAt == null ? Instant.EPOCH : createdAt;
        updatedAt = updatedAt == null ? createdAt : updatedAt;
    }

    private static void requireNotBlank(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " 不能为空");
        }
    }

    private static String trimToEmpty(String value) {
        return value == null ? "" : value.trim();
    }

    private static String trimToNull(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return value.trim();
    }
}
