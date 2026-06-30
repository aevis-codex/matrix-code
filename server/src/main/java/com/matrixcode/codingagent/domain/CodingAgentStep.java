package com.matrixcode.codingagent.domain;

public record CodingAgentStep(
        int order,
        CodingAgentStepType type,
        String title,
        String description,
        String tool,
        boolean requiresApproval
) {
    public CodingAgentStep {
        if (order <= 0) {
            throw new IllegalArgumentException("步骤序号必须大于 0");
        }
        if (type == null) {
            throw new IllegalArgumentException("步骤类型不能为空");
        }
        title = requireText(title, "步骤标题不能为空");
        description = requireText(description, "步骤说明不能为空");
        tool = tool == null ? "" : tool.trim();
    }

    private static String requireText(String value, String message) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(message);
        }
        return value.trim();
    }
}
