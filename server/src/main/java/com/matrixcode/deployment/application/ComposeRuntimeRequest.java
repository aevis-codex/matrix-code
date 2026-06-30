package com.matrixcode.deployment.application;

import java.nio.file.Path;

public record ComposeRuntimeRequest(
        Path composeFile,
        String projectName,
        String serviceName
) {
    public ComposeRuntimeRequest {
        if (composeFile == null) {
            throw new IllegalArgumentException("Compose 文件不能为空");
        }
        projectName = requireText(projectName, "Compose 项目名不能为空");
        serviceName = requireText(serviceName, "Compose 服务名不能为空");
    }

    private static String requireText(String value, String message) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(message);
        }
        return value.trim();
    }
}
