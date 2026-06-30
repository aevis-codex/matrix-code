package com.matrixcode.localexecution.domain;

import java.time.Instant;

public record FileOperationRecord(
        String id,
        String projectId,
        String workspaceId,
        FileOperationType type,
        String relativePath,
        String status,
        String summary,
        Instant createdAt
) {
    public FileOperationRecord {
        id = requireText(id, "文件操作编号不能为空");
        projectId = requireText(projectId, "项目编号不能为空");
        workspaceId = requireText(workspaceId, "工作区编号不能为空");
        if (type == null) {
            throw new IllegalArgumentException("文件操作类型不能为空");
        }
        relativePath = requireText(relativePath, "相对路径不能为空");
        status = requireText(status, "文件操作状态不能为空");
        summary = requireText(summary, "文件操作摘要不能为空");
        if (createdAt == null) {
            throw new IllegalArgumentException("文件操作时间不能为空");
        }
    }

    private static String requireText(String value, String message) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(message);
        }
        return value.trim();
    }
}
