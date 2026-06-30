package com.matrixcode.localexecution.application;

import com.matrixcode.approval.domain.AuditRecord;
import com.matrixcode.localexecution.domain.ExecutionTask;
import com.matrixcode.localexecution.domain.LocalTaskLog;
import com.matrixcode.localexecution.domain.WorkspaceAuthorization;

import java.util.List;
import java.util.Map;

public class InMemoryLocalExecutionStateStore implements LocalExecutionStateStore {

    private LocalExecutionSnapshot snapshot = LocalExecutionSnapshot.empty();

    @Override
    public synchronized LocalExecutionSnapshot load() {
        return snapshot;
    }

    @Override
    public synchronized void saveWorkspaces(List<WorkspaceAuthorization> workspaces) {
        snapshot = new LocalExecutionSnapshot(1, workspaces, snapshot.tasks(), snapshot.taskLogs(), snapshot.auditRecords());
    }

    @Override
    public synchronized void saveTasks(
            Map<String, List<ExecutionTask>> tasks,
            Map<String, Map<String, List<LocalTaskLog>>> taskLogs
    ) {
        snapshot = new LocalExecutionSnapshot(1, snapshot.workspaces(), tasks, taskLogs, snapshot.auditRecords());
    }

    @Override
    public synchronized void saveAuditRecords(List<AuditRecord> auditRecords) {
        snapshot = new LocalExecutionSnapshot(1, snapshot.workspaces(), snapshot.tasks(), snapshot.taskLogs(), auditRecords);
    }
}
