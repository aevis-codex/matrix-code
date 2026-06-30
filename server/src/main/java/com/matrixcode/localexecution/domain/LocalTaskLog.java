package com.matrixcode.localexecution.domain;

import java.time.Instant;

public record LocalTaskLog(
        String id,
        String projectId,
        String taskId,
        LocalTaskLogStream stream,
        String content,
        Instant createdAt
) {
    public LocalTaskLog {
        id = requireText(id, "日志编号不能为空");
        projectId = requireText(projectId, "项目编号不能为空");
        taskId = requireText(taskId, "任务编号不能为空");
        if (stream == null) {
            throw new IllegalArgumentException("日志流不能为空");
        }
        content = content == null ? "" : content;
        if (createdAt == null) {
            throw new IllegalArgumentException("日志时间不能为空");
        }
    }

    private static String requireText(String value, String message) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(message);
        }
        return value.trim();
    }
}
