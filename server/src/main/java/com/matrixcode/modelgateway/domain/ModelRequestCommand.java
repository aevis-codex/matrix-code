package com.matrixcode.modelgateway.domain;

import com.matrixcode.context.domain.ContextBlock;

import java.util.List;

public record ModelRequestCommand(
        String projectId,
        ModelRole role,
        String actorUserId,
        String agentRunId,
        String instruction,
        List<ContextBlock> contextBlocks,
        ModelRequestRuntimeOptions runtimeOptions
) {
    /**
     * 构造不绑定具体 Agent 运行的模型请求命令。
     *
     * <p>这是产品草稿等历史调用的兼容入口；请求仍会记录项目、角色和上下文，
     * 但不会在审计视图中被标记为某次 Agent 运行的强关联请求。</p>
     */
    public ModelRequestCommand(String projectId, ModelRole role, String instruction, List<ContextBlock> contextBlocks) {
        this(projectId, role, "", "", instruction, contextBlocks, ModelRequestRuntimeOptions.defaults());
    }

    /**
     * 构造带操作者但不绑定具体 Agent 运行的模型请求命令。
     *
     * <p>用于保留用户归属审计，同时兼容尚未接入 Agent Runtime 的调用路径。</p>
     */
    public ModelRequestCommand(
            String projectId,
            ModelRole role,
            String actorUserId,
            String instruction,
            List<ContextBlock> contextBlocks
    ) {
        this(projectId, role, actorUserId, "", instruction, contextBlocks, ModelRequestRuntimeOptions.defaults());
    }

    /**
     * 构造绑定具体 Agent 运行但使用默认 Composer 选项的模型请求命令。
     *
     * <p>该兼容入口保留运行 trace 关联，同时不要求旧调用方感知前端 Composer 新增控制项。</p>
     */
    public ModelRequestCommand(
            String projectId,
            ModelRole role,
            String actorUserId,
            String agentRunId,
            String instruction,
            List<ContextBlock> contextBlocks
    ) {
        this(projectId, role, actorUserId, agentRunId, instruction, contextBlocks, ModelRequestRuntimeOptions.defaults());
    }

    /**
     * 规范化模型请求命令的必填字段和可选审计标识。
     *
     * <p>`agentRunId` 只保存低敏运行 ID；prompt、用户输入正文、工具输出和密钥不进入该字段。</p>
     */
    public ModelRequestCommand {
        projectId = requireText(projectId, "项目编号不能为空");
        if (role == null) {
            throw new IllegalArgumentException("模型角色不能为空");
        }
        actorUserId = optionalText(actorUserId);
        agentRunId = optionalText(agentRunId);
        instruction = requireText(instruction, "模型请求指令不能为空");
        contextBlocks = contextBlocks == null ? List.of() : List.copyOf(contextBlocks);
        runtimeOptions = runtimeOptions == null ? ModelRequestRuntimeOptions.defaults() : runtimeOptions;
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
