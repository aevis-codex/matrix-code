package com.matrixcode.modelgateway.domain;

import com.matrixcode.usage.domain.UsageRecord;

import java.time.Instant;
import java.util.List;

public record ModelRequestRecord(
        String requestId,
        String projectId,
        ModelRole role,
        String providerId,
        String model,
        String answerSummary,
        String actorUserId,
        String agentRunId,
        UsageRecord usage,
        List<String> contextTypes,
        Instant createdAt
) {
    /**
     * 构造不含操作者和 Agent 运行关联的历史模型请求记录。
     *
     * <p>该兼容入口用于旧文件状态和旧测试 fixture，恢复后的记录不会被视为强运行关联。</p>
     */
    public ModelRequestRecord(
            String requestId,
            String projectId,
            ModelRole role,
            String providerId,
            String model,
            String answerSummary,
            UsageRecord usage,
            List<String> contextTypes,
            Instant createdAt
    ) {
        this(requestId, projectId, role, providerId, model, answerSummary, "", "", usage, contextTypes, createdAt);
    }

    /**
     * 构造只含操作者、不含 Agent 运行关联的模型请求记录。
     *
     * <p>用于保留第 40 阶段用户归属语义，同时让历史调用继续保持无强关联状态。</p>
     */
    public ModelRequestRecord(
            String requestId,
            String projectId,
            ModelRole role,
            String providerId,
            String model,
            String answerSummary,
            String actorUserId,
            UsageRecord usage,
            List<String> contextTypes,
            Instant createdAt
    ) {
        this(requestId, projectId, role, providerId, model, answerSummary, actorUserId, "", usage, contextTypes, createdAt);
    }

    /**
     * 规范化模型请求记录的审计字段和用量上下文。
     *
     * <p>`agentRunId` 是可选低敏关联 ID；为空时前端只能展示项目级模型请求线索。</p>
     */
    public ModelRequestRecord {
        requestId = requireText(requestId, "请求 ID 不能为空");
        projectId = requireText(projectId, "项目编号不能为空");
        if (role == null) {
            throw new IllegalArgumentException("模型角色不能为空");
        }
        providerId = requireText(providerId, "供应商 ID 不能为空");
        model = requireText(model, "模型名称不能为空");
        answerSummary = requireText(answerSummary, "回答摘要不能为空");
        actorUserId = optionalText(actorUserId);
        agentRunId = optionalText(agentRunId);
        if (usage == null) {
            throw new IllegalArgumentException("用量记录不能为空");
        }
        contextTypes = contextTypes == null ? List.of() : List.copyOf(contextTypes);
        if (createdAt == null) {
            throw new IllegalArgumentException("创建时间不能为空");
        }
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
