package com.matrixcode.agentruntime.domain;

/**
 * Worker 执行计划中的单个受控步骤。
 *
 * <p>步骤只记录低敏状态机信息：步骤顺序、工具边界、当前状态、是否需要审批和摘要。
 * 它不代表工具已经执行；后续真实执行器必须根据 {@code requiresApproval} 和 {@code status}
 * 再决定是否允许模型请求、命令执行、文件写入或交付回溯。</p>
 */
public record AgentRuntimeWorkerExecutionStep(
        int order,
        String stepKey,
        String title,
        String toolName,
        String status,
        boolean requiresApproval,
        String summary
) {
    public AgentRuntimeWorkerExecutionStep {
        if (order <= 0) {
            throw new IllegalArgumentException("步骤序号必须大于 0");
        }
        stepKey = requireText(stepKey, "步骤标识不能为空").toUpperCase();
        title = requireText(title, "步骤标题不能为空");
        toolName = requireText(toolName, "工具名称不能为空");
        status = requireText(status, "步骤状态不能为空").toUpperCase();
        summary = requireText(summary, "步骤摘要不能为空");
    }

    private static String requireText(String value, String message) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(message);
        }
        return value.trim();
    }
}
