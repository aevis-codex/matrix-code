package com.matrixcode.approval.domain;

public record ToolAction(
        String taskId,
        String actorId,
        String toolType,
        String command,
        String workspacePath,
        boolean dangerous
) {
}
