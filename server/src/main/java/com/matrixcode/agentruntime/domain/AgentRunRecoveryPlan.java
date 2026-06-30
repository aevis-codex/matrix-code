package com.matrixcode.agentruntime.domain;

import java.util.Optional;

/**
 * Agent 运行失败恢复计划。
 *
 * <p>该记录只描述一次运行是否允许恢复重试，以及不能恢复时的阻塞原因。它不承载完整 prompt、
 * 模型响应、工具输出或异常堆栈，避免恢复入口扩大敏感数据暴露面。</p>
 */
public record AgentRunRecoveryPlan(
        String sourceRunId,
        boolean canRetry,
        String blockedReason,
        String recommendedAction,
        Optional<AgentRunRecord> sourceRun
) {
    public AgentRunRecoveryPlan {
        sourceRunId = trimToEmpty(sourceRunId);
        blockedReason = trimToEmpty(blockedReason);
        recommendedAction = trimToEmpty(recommendedAction);
        sourceRun = sourceRun == null ? Optional.empty() : sourceRun;
    }

    /**
     * 创建可恢复计划。
     *
     * <p>调用方必须传入已校验为失败且可重试的来源运行。该方法只组装领域结果，
     * 不写数据库，也不创建新的 Agent Run。</p>
     */
    public static AgentRunRecoveryPlan canRetry(AgentRunRecord sourceRun, String recommendedAction) {
        if (sourceRun == null) {
            throw new IllegalArgumentException("恢复来源运行不能为空");
        }
        return new AgentRunRecoveryPlan(sourceRun.id(), true, "", recommendedAction, Optional.of(sourceRun));
    }

    /**
     * 创建不可恢复计划。
     *
     * <p>该方法用于来源不存在、跨项目、非失败状态或标记为不可重试等场景。阻塞原因保持中文可读，
     * 便于前端直接展示，也便于阶段验收和日志排查。</p>
     */
    public static AgentRunRecoveryPlan blocked(String sourceRunId, String blockedReason, AgentRunRecord sourceRun) {
        return new AgentRunRecoveryPlan(
                sourceRunId,
                false,
                blockedReason,
                "",
                Optional.ofNullable(sourceRun)
        );
    }

    private static String trimToEmpty(String value) {
        return value == null ? "" : value.trim();
    }
}
