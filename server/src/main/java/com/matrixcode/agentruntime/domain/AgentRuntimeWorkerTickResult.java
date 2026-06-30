package com.matrixcode.agentruntime.domain;

import java.time.Instant;

public record AgentRuntimeWorkerTickResult(
        String projectId,
        String workerId,
        Instant tickedAt,
        int expiredRunCount,
        AgentRunRecord claimedRun
) {
    public AgentRuntimeWorkerTickResult {
        requireNotBlank(projectId, "projectId");
        requireNotBlank(workerId, "workerId");
        tickedAt = tickedAt == null ? Instant.EPOCH : tickedAt;
        expiredRunCount = Math.max(0, expiredRunCount);
    }

    private static void requireNotBlank(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " 不能为空");
        }
    }
}
