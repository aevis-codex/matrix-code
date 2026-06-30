package com.matrixcode.deployment.application;

import com.matrixcode.deployment.domain.ComposeOperationStatus;

public record ComposeRuntimeResult(
        ComposeOperationStatus status,
        String summary,
        String logExcerpt
) {
    public ComposeRuntimeResult {
        if (status == null) {
            throw new IllegalArgumentException("Compose 运行结果状态不能为空");
        }
        summary = summary == null ? "" : summary.trim();
        logExcerpt = logExcerpt == null ? "" : logExcerpt.trim();
    }

    public static ComposeRuntimeResult succeeded(String summary, String logExcerpt) {
        return new ComposeRuntimeResult(ComposeOperationStatus.SUCCEEDED, summary, logExcerpt);
    }

    public static ComposeRuntimeResult failed(String summary, String logExcerpt) {
        return new ComposeRuntimeResult(ComposeOperationStatus.FAILED, summary, logExcerpt);
    }
}
