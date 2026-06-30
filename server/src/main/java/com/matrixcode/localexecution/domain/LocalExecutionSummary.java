package com.matrixcode.localexecution.domain;

import com.matrixcode.approval.domain.AuditRecord;

import java.util.List;

public record LocalExecutionSummary(
        List<WorkspaceAuthorization> workspaces,
        List<FileOperationRecord> recentFileOperations,
        List<ExecutionTask> recentTasks,
        List<ExecutionTask> activeTasks,
        List<LocalTaskLog> recentTaskLogs,
        GitDiffSummary recentGitDiff,
        List<AuditRecord> recentAuditRecords
) {
    public LocalExecutionSummary(
            List<WorkspaceAuthorization> workspaces,
            List<FileOperationRecord> recentFileOperations,
            List<ExecutionTask> recentTasks,
            GitDiffSummary recentGitDiff,
            List<AuditRecord> recentAuditRecords
    ) {
        this(workspaces, recentFileOperations, recentTasks,
                activeTasksFrom(recentTasks), List.of(), recentGitDiff, recentAuditRecords);
    }

    public LocalExecutionSummary {
        workspaces = List.copyOf(workspaces == null ? List.of() : workspaces);
        recentFileOperations = List.copyOf(recentFileOperations == null ? List.of() : recentFileOperations);
        recentTasks = List.copyOf(recentTasks == null ? List.of() : recentTasks);
        activeTasks = List.copyOf(activeTasks == null ? List.of() : activeTasks);
        recentTaskLogs = List.copyOf(recentTaskLogs == null ? List.of() : recentTaskLogs);
        recentAuditRecords = List.copyOf(recentAuditRecords == null ? List.of() : recentAuditRecords);
    }

    private static List<ExecutionTask> activeTasksFrom(List<ExecutionTask> recentTasks) {
        return (recentTasks == null ? List.<ExecutionTask>of() : recentTasks).stream()
                .filter(task -> task.status() == ExecutionTaskStatus.QUEUED || task.status() == ExecutionTaskStatus.RUNNING)
                .toList();
    }
}
