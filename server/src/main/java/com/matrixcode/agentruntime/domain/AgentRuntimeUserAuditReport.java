package com.matrixcode.agentruntime.domain;

import java.util.List;

/**
 * 单个用户在项目内的 Agent Runtime 责任审计报告。
 *
 * <p>报告面向运行中心和阶段验收使用，只汇总低敏运行摘要、责任人和计数信息。
 * 它不替代登录认证或权限系统；当前 userId 仍来自桌面协作上下文。</p>
 */
public record AgentRuntimeUserAuditReport(
        String projectId,
        String userId,
        int totalRuns,
        int activeResponsibilities,
        int modelRequestCount,
        List<AgentRuntimeUserAuditEntry> entries
) {
    public AgentRuntimeUserAuditReport {
        projectId = requireText(projectId, "项目编号不能为空");
        userId = requireText(userId, "用户编号不能为空");
        entries = entries == null ? List.of() : List.copyOf(entries);
        totalRuns = entries.size();
        activeResponsibilities = Math.max(0, activeResponsibilities);
        modelRequestCount = Math.max(0, modelRequestCount);
    }

    /**
     * 根据审计条目创建报告，统一计算运行数量、活跃责任和模型请求数量。
     */
    public static AgentRuntimeUserAuditReport from(
            String projectId,
            String userId,
            List<AgentRuntimeUserAuditEntry> entries
    ) {
        var normalizedEntries = entries == null ? List.<AgentRuntimeUserAuditEntry>of() : List.copyOf(entries);
        var activeResponsibilities = normalizedEntries.stream()
                .filter(entry -> entry.responsibleUserId().equals(userId))
                .filter(entry -> "RUNNING".equals(entry.status()) || "QUEUED".equals(entry.status()))
                .count();
        var modelRequestCount = normalizedEntries.stream()
                .mapToInt(AgentRuntimeUserAuditEntry::modelRequestCount)
                .sum();
        return new AgentRuntimeUserAuditReport(
                projectId,
                userId,
                normalizedEntries.size(),
                Math.toIntExact(activeResponsibilities),
                modelRequestCount,
                normalizedEntries
        );
    }

    private static String requireText(String value, String message) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(message);
        }
        return value.trim();
    }
}
