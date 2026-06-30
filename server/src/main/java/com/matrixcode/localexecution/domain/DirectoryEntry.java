package com.matrixcode.localexecution.domain;

public record DirectoryEntry(
        String name,
        String relativePath,
        boolean directory,
        long sizeBytes
) {
    public DirectoryEntry {
        name = requireText(name, "目录项名称不能为空");
        relativePath = requireText(relativePath, "目录项相对路径不能为空");
        if (sizeBytes < 0) {
            throw new IllegalArgumentException("目录项大小不能为负数");
        }
    }

    private static String requireText(String value, String message) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(message);
        }
        return value.trim();
    }
}
