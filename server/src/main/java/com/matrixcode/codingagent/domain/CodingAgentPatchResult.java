package com.matrixcode.codingagent.domain;

import com.matrixcode.localexecution.domain.GitDiffSummary;
import com.matrixcode.modelgateway.domain.ModelRole;

public record CodingAgentPatchResult(
        String projectId,
        ModelRole role,
        String workspaceId,
        String actorId,
        String runId,
        String relativePath,
        String summary,
        long bytesWritten,
        GitDiffSummary gitDiffSummary
) {
    public CodingAgentPatchResult {
        projectId = requireText(projectId, "项目编号不能为空");
        if (role == null) {
            throw new IllegalArgumentException("角色不能为空");
        }
        workspaceId = requireText(workspaceId, "工作区编号不能为空");
        actorId = requireText(actorId, "操作者不能为空");
        runId = requireText(runId, "运行编号不能为空");
        relativePath = requireText(relativePath, "相对路径不能为空");
        summary = requireText(summary, "变更说明不能为空");
        if (bytesWritten < 0) {
            throw new IllegalArgumentException("写入字节数不能为负数");
        }
        if (gitDiffSummary == null) {
            throw new IllegalArgumentException("Git diff 摘要不能为空");
        }
    }

    private static String requireText(String value, String message) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(message);
        }
        return value.trim();
    }
}
