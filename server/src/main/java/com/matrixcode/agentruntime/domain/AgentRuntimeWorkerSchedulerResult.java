package com.matrixcode.agentruntime.domain;

/**
 * Agent Runtime Worker 单次调度结果。
 *
 * <p>该结果只记录调度开关、认领运行、过期回收数量和受控模型请求摘要，不包含完整 prompt、模型回答全文、
 * 工具输出、文件内容或任何密钥。它用于测试、日志和后续运行诊断展示。</p>
 */
public record AgentRuntimeWorkerSchedulerResult(
        String projectId,
        String workerId,
        boolean enabled,
        boolean ticked,
        int expiredRunCount,
        String claimedRunId,
        boolean modelExecuted,
        String modelRequestId,
        String blockedReason
) {
    public AgentRuntimeWorkerSchedulerResult {
        projectId = requireText(projectId, "项目编号不能为空");
        workerId = requireText(workerId, "Worker 编号不能为空");
        expiredRunCount = Math.max(0, expiredRunCount);
        claimedRunId = optionalText(claimedRunId);
        modelRequestId = optionalText(modelRequestId);
        blockedReason = optionalText(blockedReason);
    }

    /**
     * 创建调度关闭结果。
     */
    public static AgentRuntimeWorkerSchedulerResult disabled(String projectId, String workerId) {
        return new AgentRuntimeWorkerSchedulerResult(projectId, workerId, false, false, 0, "", false, "", "");
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
