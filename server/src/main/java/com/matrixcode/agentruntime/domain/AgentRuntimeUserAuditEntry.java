package com.matrixcode.agentruntime.domain;

import java.time.Instant;

/**
 * 用户级 Agent Runtime 审计报告中的单条运行责任摘要。
 *
 * <p>该对象只返回运行身份、责任人、事件计数和模型请求低敏线索，不包含完整 prompt、
 * 模型响应全文、向量正文、命令输出、文件内容、API Key 或数据库密码。</p>
 */
public record AgentRuntimeUserAuditEntry(
        String projectId,
        String runId,
        String userId,
        String responsibleUserId,
        String responsibilitySource,
        String roleKey,
        String agentKind,
        String status,
        String actorUserId,
        String claimedByUserId,
        String goal,
        String summary,
        String failureSummary,
        int eventCount,
        int toolTraceCount,
        int modelRequestCount,
        String lastEventType,
        String lastEventTitle,
        String lastModelRequestId,
        Instant updatedAt
) {
    public AgentRuntimeUserAuditEntry {
        projectId = requireText(projectId, "项目编号不能为空");
        runId = requireText(runId, "运行编号不能为空");
        userId = requireText(userId, "用户编号不能为空");
        responsibleUserId = requireText(responsibleUserId, "责任人不能为空");
        responsibilitySource = requireText(responsibilitySource, "责任来源不能为空");
        roleKey = requireText(roleKey, "角色不能为空");
        agentKind = requireText(agentKind, "Agent 类型不能为空");
        status = requireText(status, "运行状态不能为空");
        actorUserId = requireText(actorUserId, "操作者不能为空");
        claimedByUserId = optionalText(claimedByUserId);
        goal = requireText(goal, "运行目标不能为空");
        summary = optionalText(summary);
        failureSummary = optionalText(failureSummary);
        eventCount = Math.max(0, eventCount);
        toolTraceCount = Math.max(0, toolTraceCount);
        modelRequestCount = Math.max(0, modelRequestCount);
        lastEventType = optionalText(lastEventType);
        lastEventTitle = optionalText(lastEventTitle);
        lastModelRequestId = optionalText(lastModelRequestId);
        updatedAt = updatedAt == null ? Instant.EPOCH : updatedAt;
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
