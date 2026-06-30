package com.matrixcode.runtime.domain;

import java.time.Instant;

public record RuntimeNotification(
        String id,
        String projectId,
        RuntimeNotificationLevel level,
        String title,
        String message,
        RuntimeNotificationSourceType sourceType,
        String sourceId,
        Instant occurredAt,
        Instant readAt,
        String readByUserId
) {
    public RuntimeNotification(
            String id,
            String projectId,
            RuntimeNotificationLevel level,
            String title,
            String message,
            RuntimeNotificationSourceType sourceType,
            String sourceId,
            Instant occurredAt,
            Instant readAt
    ) {
        this(id, projectId, level, title, message, sourceType, sourceId, occurredAt, readAt, "");
    }

    public RuntimeNotification {
        id = requireText(id, "运行态提醒编号不能为空");
        projectId = requireText(projectId, "项目编号不能为空");
        if (level == null) {
            throw new IllegalArgumentException("运行态提醒级别不能为空");
        }
        title = requireText(title, "运行态提醒标题不能为空");
        message = message == null ? "" : message.trim();
        if (sourceType == null) {
            throw new IllegalArgumentException("运行态提醒来源类型不能为空");
        }
        sourceId = requireText(sourceId, "运行态提醒来源编号不能为空");
        if (occurredAt == null) {
            throw new IllegalArgumentException("运行态提醒时间不能为空");
        }
        readByUserId = readByUserId == null ? "" : readByUserId.trim();
    }

    public RuntimeNotification withReadAt(Instant readAt) {
        return withReadAt(readAt, "");
    }

    public RuntimeNotification withReadAt(Instant readAt, String readByUserId) {
        if (this.readAt != null) {
            return this;
        }
        if (readAt == null) {
            throw new IllegalArgumentException("已读时间不能为空");
        }
        return new RuntimeNotification(
                id,
                projectId,
                level,
                title,
                message,
                sourceType,
                sourceId,
                occurredAt,
                readAt,
                readByUserId
        );
    }

    private static String requireText(String value, String message) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(message);
        }
        return value.trim();
    }
}
