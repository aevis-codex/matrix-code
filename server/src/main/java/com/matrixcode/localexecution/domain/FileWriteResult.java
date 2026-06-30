package com.matrixcode.localexecution.domain;

public record FileWriteResult(
        String workspaceId,
        String relativePath,
        long bytesWritten
) {
    public FileWriteResult {
        workspaceId = requireText(workspaceId, "工作区编号不能为空");
        relativePath = requireText(relativePath, "相对路径不能为空");
        if (bytesWritten < 0) {
            throw new IllegalArgumentException("写入字节数不能为负数");
        }
    }

    private static String requireText(String value, String message) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(message);
        }
        return value.trim();
    }
}
