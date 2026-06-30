package com.matrixcode.localexecution.application;

import com.matrixcode.approval.domain.AuditRecord;
import com.matrixcode.localexecution.domain.ExecutionTask;
import com.matrixcode.localexecution.domain.LocalTaskLog;
import com.matrixcode.localexecution.domain.WorkspaceAuthorization;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

public record LocalExecutionSnapshot(
        int version,
        List<WorkspaceAuthorization> workspaces,
        Map<String, List<ExecutionTask>> tasks,
        Map<String, Map<String, List<LocalTaskLog>>> taskLogs,
        List<AuditRecord> auditRecords
) {
    public LocalExecutionSnapshot {
        workspaces = List.copyOf(workspaces == null ? List.of() : workspaces);
        tasks = copyTasks(tasks);
        taskLogs = copyLogs(taskLogs);
        auditRecords = List.copyOf(auditRecords == null ? List.of() : auditRecords);
    }

    public static LocalExecutionSnapshot empty() {
        return new LocalExecutionSnapshot(1, List.of(), Map.of(), Map.of(), List.of());
    }

    private static Map<String, List<ExecutionTask>> copyTasks(Map<String, List<ExecutionTask>> tasks) {
        if (tasks == null) {
            return Map.of();
        }
        return tasks.entrySet().stream()
                .collect(Collectors.toUnmodifiableMap(Map.Entry::getKey, entry -> List.copyOf(entry.getValue())));
    }

    private static Map<String, Map<String, List<LocalTaskLog>>> copyLogs(
            Map<String, Map<String, List<LocalTaskLog>>> taskLogs
    ) {
        if (taskLogs == null) {
            return Map.of();
        }
        return taskLogs.entrySet().stream()
                .collect(Collectors.toUnmodifiableMap(
                        Map.Entry::getKey,
                        entry -> entry.getValue().entrySet().stream()
                                .collect(Collectors.toUnmodifiableMap(
                                        Map.Entry::getKey,
                                        nested -> List.copyOf(nested.getValue())
                                ))
                ));
    }
}
