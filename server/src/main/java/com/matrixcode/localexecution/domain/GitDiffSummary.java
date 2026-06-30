package com.matrixcode.localexecution.domain;

import java.time.Instant;
import java.util.List;

public record GitDiffSummary(
        String projectId,
        String workspaceId,
        boolean repository,
        List<String> changedFiles,
        String stat,
        Instant capturedAt
) {
    public GitDiffSummary {
        projectId = requireText(projectId, "项目编号不能为空");
        workspaceId = requireText(workspaceId, "工作区编号不能为空");
        changedFiles = List.copyOf(changedFiles == null ? List.of() : changedFiles);
        stat = stat == null ? "" : stat;
        if (capturedAt == null) {
            throw new IllegalArgumentException("Git diff 采集时间不能为空");
        }
    }

    private static String requireText(String value, String message) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(message);
        }
        return value.trim();
    }
}
