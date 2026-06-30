package com.matrixcode.agentruntime.domain;

/**
 * Worker 触发模型网关请求后的低敏结果。
 *
 * <p>该对象只返回请求编号、供应商、模型和回答摘要，供运行中心或调度器判断本次模型步骤是否完成。
 * 它不会返回完整 prompt、模型响应全文、向量正文、工具输出、文件内容、API Key 或数据库密码。</p>
 */
public record AgentRuntimeWorkerModelExecutionResult(
        String projectId,
        String runId,
        String workerId,
        boolean executed,
        String blockedReason,
        String requestId,
        String providerId,
        String modelName,
        String answerSummary
) {
    public AgentRuntimeWorkerModelExecutionResult {
        projectId = requireText(projectId, "项目编号不能为空");
        runId = requireText(runId, "运行编号不能为空");
        workerId = requireText(workerId, "Worker 编号不能为空");
        blockedReason = optionalText(blockedReason);
        requestId = optionalText(requestId);
        providerId = optionalText(providerId);
        modelName = optionalText(modelName);
        answerSummary = optionalText(answerSummary);
        if (executed) {
            requireText(requestId, "模型请求编号不能为空");
            requireText(providerId, "模型供应商不能为空");
            requireText(modelName, "模型名称不能为空");
            requireText(answerSummary, "模型回答摘要不能为空");
        }
        if (!executed && blockedReason.isBlank()) {
            throw new IllegalArgumentException("阻塞模型执行必须包含原因");
        }
    }

    /**
     * 创建模型请求已执行的结果；调用方只传入低敏摘要字段。
     */
    public static AgentRuntimeWorkerModelExecutionResult executed(
            String projectId,
            String runId,
            String workerId,
            String requestId,
            String providerId,
            String modelName,
            String answerSummary
    ) {
        return new AgentRuntimeWorkerModelExecutionResult(
                projectId,
                runId,
                workerId,
                true,
                "",
                requestId,
                providerId,
                modelName,
                answerSummary
        );
    }

    /**
     * 创建被租约、认领人或运行状态阻止的结果。
     */
    public static AgentRuntimeWorkerModelExecutionResult blocked(
            String projectId,
            String runId,
            String workerId,
            String blockedReason
    ) {
        return new AgentRuntimeWorkerModelExecutionResult(
                projectId,
                runId,
                workerId,
                false,
                blockedReason,
                "",
                "",
                "",
                ""
        );
    }

    private static String requireText(String value, String message) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(message);
        }
        return value.trim();
    }

    private static String optionalText(String value) {
        return value == null ? "" : value.trim();
    }
}
