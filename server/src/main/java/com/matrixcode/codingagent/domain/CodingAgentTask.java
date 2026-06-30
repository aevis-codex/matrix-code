package com.matrixcode.codingagent.domain;

import com.matrixcode.modelgateway.domain.ModelRole;

import java.time.Instant;
import java.util.List;

public record CodingAgentTask(
        String taskId,
        String projectId,
        ModelRole role,
        String goal,
        String workspaceId,
        CodingAgentTaskStatus status,
        Instant createdAt,
        List<CodingAgentStep> steps
) {
    public CodingAgentTask {
        taskId = requireText(taskId, "任务编号不能为空");
        projectId = requireText(projectId, "项目编号不能为空");
        if (role == null) {
            throw new IllegalArgumentException("角色不能为空");
        }
        goal = requireText(goal, "编码目标不能为空");
        workspaceId = requireText(workspaceId, "工作区编号不能为空");
        status = status == null ? CodingAgentTaskStatus.PLANNED : status;
        createdAt = createdAt == null ? Instant.EPOCH : createdAt;
        steps = steps == null ? List.of() : List.copyOf(steps);
    }

    private static String requireText(String value, String message) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(message);
        }
        return value.trim();
    }
}
