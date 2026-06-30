package com.matrixcode.localexecution.application;

import com.matrixcode.approval.domain.AuditRecord;
import com.matrixcode.localexecution.domain.ExecutionTask;
import com.matrixcode.localexecution.domain.LocalTaskLog;
import com.matrixcode.localexecution.domain.WorkspaceAuthorization;

import java.util.List;
import java.util.Map;

/**
 * 本地执行状态存储接口。
 *
 * <p>作用域：本地工作区、命令任务和审批审计；场景：重启恢复命令队列、日志和授权工作区。</p>
 */
public interface LocalExecutionStateStore {

    /**
     * 读取本地执行完整快照。
     */
    LocalExecutionSnapshot load();

    /**
     * 保存授权工作区列表。
     */
    void saveWorkspaces(List<WorkspaceAuthorization> workspaces);

    /**
     * 保存任务和任务日志。
     */
    void saveTasks(Map<String, List<ExecutionTask>> tasks, Map<String, Map<String, List<LocalTaskLog>>> taskLogs);

    /**
     * 保存审批审计记录。
     */
    void saveAuditRecords(List<AuditRecord> auditRecords);
}
