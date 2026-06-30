package com.matrixcode.agentruntime.domain;

import java.time.Instant;
import java.util.List;

/**
 * Worker 对单次 Agent 运行的执行准备结果。
 *
 * <p>该对象只描述“当前是否允许执行、被阻塞的原因、准备执行哪些步骤”，不保存模型响应、
 * 文件内容、命令输出或密钥。可执行计划用于后续真实 Worker 按步骤推进；阻塞计划用于前端和调度器
 * 展示明确原因，避免非认领人、过期租约或非运行态任务被误执行。</p>
 */
public record AgentRuntimeWorkerExecutionPlan(
        String projectId,
        String runId,
        String workerId,
        Instant plannedAt,
        boolean executable,
        String blockedReason,
        List<AgentRuntimeWorkerExecutionStep> steps
) {
    public AgentRuntimeWorkerExecutionPlan {
        projectId = requireText(projectId, "项目编号不能为空");
        runId = requireText(runId, "运行编号不能为空");
        workerId = requireText(workerId, "Worker 编号不能为空");
        plannedAt = plannedAt == null ? Instant.EPOCH : plannedAt;
        blockedReason = blockedReason == null ? "" : blockedReason.trim();
        steps = steps == null ? List.of() : List.copyOf(steps);
        if (!executable && blockedReason.isBlank()) {
            throw new IllegalArgumentException("阻塞计划必须包含原因");
        }
    }

    /**
     * 创建可执行计划；调用方必须已经完成运行状态、认领人和租约校验。
     */
    public static AgentRuntimeWorkerExecutionPlan executable(
            String projectId,
            String runId,
            String workerId,
            Instant plannedAt,
            List<AgentRuntimeWorkerExecutionStep> steps
    ) {
        return new AgentRuntimeWorkerExecutionPlan(projectId, runId, workerId, plannedAt, true, "", steps);
    }

    /**
     * 创建阻塞计划；阻塞原因会直接返回给前端和调度器用于解释不可执行状态。
     */
    public static AgentRuntimeWorkerExecutionPlan blocked(
            String projectId,
            String runId,
            String workerId,
            Instant plannedAt,
            String blockedReason
    ) {
        return new AgentRuntimeWorkerExecutionPlan(projectId, runId, workerId, plannedAt, false, blockedReason, List.of());
    }

    private static String requireText(String value, String message) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(message);
        }
        return value.trim();
    }
}
