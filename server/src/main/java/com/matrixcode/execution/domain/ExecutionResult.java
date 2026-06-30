package com.matrixcode.execution.domain;

public record ExecutionResult(
        String taskId,
        String agentId,
        String status,
        String summary
) {
    public ExecutionResult {
        requireNotBlank(taskId, "taskId 不能为空");
        requireNotBlank(agentId, "agentId 不能为空");
        requireNotBlank(status, "status 不能为空");
        requireNotBlank(summary, "summary 不能为空");
    }

    private static void requireNotBlank(String value, String message) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(message);
        }
    }
}
