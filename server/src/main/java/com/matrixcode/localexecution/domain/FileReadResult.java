package com.matrixcode.localexecution.domain;

public record FileReadResult(
        String workspaceId,
        String relativePath,
        String content,
        long sizeBytes
) {
    public FileReadResult {
        workspaceId = requireText(workspaceId, "工作区编号不能为空");
        relativePath = requireText(relativePath, "相对路径不能为空");
        if (content == null) {
            throw new IllegalArgumentException("文件内容不能为空");
        }
        if (sizeBytes < 0) {
            throw new IllegalArgumentException("文件大小不能为负数");
        }
    }

    private static String requireText(String value, String message) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(message);
        }
        return value.trim();
    }
}
