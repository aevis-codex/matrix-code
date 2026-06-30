package com.matrixcode.execution.domain;

public record AgentHeartbeat(
        String agentId,
        String projectId,
        String userId,
        String status
) {
    public AgentHeartbeat {
        requireNotBlank(agentId, "agentId 不能为空");
        requireNotBlank(projectId, "projectId 不能为空");
        requireNotBlank(userId, "userId 不能为空");
        requireNotBlank(status, "status 不能为空");
    }

    private static void requireNotBlank(String value, String message) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(message);
        }
    }
}
