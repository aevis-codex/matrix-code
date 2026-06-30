package com.matrixcode.codingagent.domain;

public record CodingAgentExecutionStep(
        int order,
        CodingAgentStepType type,
        String title,
        String localTool,
        CodingAgentExecutionStatus status,
        String referenceId,
        String summary
) {
    public CodingAgentExecutionStep {
        if (order <= 0) {
            throw new IllegalArgumentException("步骤序号必须大于 0");
        }
        if (type == null) {
            throw new IllegalArgumentException("步骤类型不能为空");
        }
        title = requireText(title, "步骤标题不能为空");
        localTool = localTool == null ? "" : localTool.trim();
        if (status == null) {
            throw new IllegalArgumentException("执行状态不能为空");
        }
        referenceId = referenceId == null ? "" : referenceId.trim();
        summary = requireText(summary, "执行摘要不能为空");
    }

    private static String requireText(String value, String message) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(message);
        }
        return value.trim();
    }
}
