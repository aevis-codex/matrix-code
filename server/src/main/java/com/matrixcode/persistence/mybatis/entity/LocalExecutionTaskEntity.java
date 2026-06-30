package com.matrixcode.persistence.mybatis.entity;

import com.baomidou.mybatisplus.annotation.FieldStrategy;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.matrixcode.approval.domain.ApprovalDecision;
import com.matrixcode.localexecution.domain.ExecutionTask;
import com.matrixcode.localexecution.domain.ExecutionTaskStatus;

import java.time.Instant;

@TableName("matrixcode_local_execution_tasks")
public class LocalExecutionTaskEntity {

    @TableId(value = "id", type = IdType.ASSIGN_ID)
    private String id;
    private String projectId;
    private String workspaceId;
    private String requestedByRole;
    @TableField(value = "approval_record_id", insertStrategy = FieldStrategy.ALWAYS)
    private String approvalRecordId;
    private String commandText;
    private String status;
    @TableField(value = "exit_code", insertStrategy = FieldStrategy.ALWAYS)
    private Integer exitCode;
    @TableField(value = "started_at", insertStrategy = FieldStrategy.ALWAYS)
    private Instant startedAt;
    @TableField(value = "finished_at", insertStrategy = FieldStrategy.ALWAYS)
    private Instant finishedAt;
    private Instant createdAt;
    private Instant updatedAt;
    private String toolType;
    private String approvalDecision;
    @TableField(value = "stdout_summary", insertStrategy = FieldStrategy.ALWAYS)
    private String stdoutSummary;
    @TableField(value = "stderr_summary", insertStrategy = FieldStrategy.ALWAYS)
    private String stderrSummary;
    @TableField(value = "duration_millis", insertStrategy = FieldStrategy.ALWAYS)
    private Long durationMillis;
    @TableField(value = "approver_id", insertStrategy = FieldStrategy.ALWAYS)
    private String approverId;
    @TableField(value = "approval_note", insertStrategy = FieldStrategy.ALWAYS)
    private String approvalNote;
    @TableField(value = "decided_at", insertStrategy = FieldStrategy.ALWAYS)
    private Instant decidedAt;
    @TableField(value = "safety_rejection_reason", insertStrategy = FieldStrategy.ALWAYS)
    private String safetyRejectionReason;
    @TableField(value = "canceled_by", insertStrategy = FieldStrategy.ALWAYS)
    private String canceledBy;
    @TableField(value = "cancel_note", insertStrategy = FieldStrategy.ALWAYS)
    private String cancelNote;
    @TableField(value = "canceled_at", insertStrategy = FieldStrategy.ALWAYS)
    private Instant canceledAt;
    private Integer sortOrder;

    /**
     * 将本地执行任务转换为正式表实体。
     *
     * <p>当前领域模型只记录任务创建时间，旧 JDBC 仓储对终态任务把 `finished_at`
     * 写为创建时间。这里延续该兼容语义，避免历史视图排序和耗时展示发生变化。</p>
     */
    public static LocalExecutionTaskEntity fromDomain(ExecutionTask task, int sortOrder, Instant now) {
        var entity = new LocalExecutionTaskEntity();
        entity.setId(task.taskId());
        entity.setProjectId(task.projectId());
        entity.setWorkspaceId(task.workspaceId());
        entity.setRequestedByRole(task.actorId());
        entity.setApprovalRecordId(null);
        entity.setCommandText(task.command());
        entity.setStatus(task.status().name());
        entity.setExitCode(task.exitCode());
        entity.setStartedAt(null);
        entity.setFinishedAt(terminal(task.status()) ? task.createdAt() : null);
        entity.setCreatedAt(task.createdAt());
        entity.setUpdatedAt(now);
        entity.setToolType(task.toolType());
        entity.setApprovalDecision(task.approvalDecision().name());
        entity.setStdoutSummary(task.stdoutSummary());
        entity.setStderrSummary(task.stderrSummary());
        entity.setDurationMillis(task.durationMillis());
        entity.setApproverId(task.approverId());
        entity.setApprovalNote(task.approvalNote());
        entity.setDecidedAt(task.decidedAt());
        entity.setSafetyRejectionReason(task.safetyRejectionReason());
        entity.setCanceledBy(task.canceledBy());
        entity.setCancelNote(task.cancelNote());
        entity.setCanceledAt(task.canceledAt());
        entity.setSortOrder(sortOrder);
        return entity;
    }

    /**
     * 将正式表实体恢复为本地执行任务领域对象。
     */
    public ExecutionTask toDomain() {
        return new ExecutionTask(
                id,
                projectId,
                workspaceId,
                requestedByRole,
                textOr(toolType, "SHELL"),
                commandText,
                enumOr(approvalDecision, ApprovalDecision.ASK),
                ExecutionTaskStatus.valueOf(status),
                exitCode,
                textOr(stdoutSummary, ""),
                textOr(stderrSummary, ""),
                durationMillis == null ? 0 : durationMillis,
                createdAt == null ? Instant.EPOCH : createdAt,
                textOr(approverId, ""),
                textOr(approvalNote, ""),
                decidedAt,
                textOr(safetyRejectionReason, ""),
                textOr(canceledBy, ""),
                textOr(cancelNote, ""),
                canceledAt
        );
    }

    private static boolean terminal(ExecutionTaskStatus status) {
        return status == ExecutionTaskStatus.SUCCESS
                || status == ExecutionTaskStatus.FAILED
                || status == ExecutionTaskStatus.CANCELED
                || status == ExecutionTaskStatus.DENIED;
    }

    private static <T extends Enum<T>> T enumOr(String value, T fallback) {
        if (value == null || value.isBlank()) {
            return fallback;
        }
        return Enum.valueOf(fallback.getDeclaringClass(), value);
    }

    private static String textOr(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getProjectId() {
        return projectId;
    }

    public void setProjectId(String projectId) {
        this.projectId = projectId;
    }

    public String getWorkspaceId() {
        return workspaceId;
    }

    public void setWorkspaceId(String workspaceId) {
        this.workspaceId = workspaceId;
    }

    public String getRequestedByRole() {
        return requestedByRole;
    }

    public void setRequestedByRole(String requestedByRole) {
        this.requestedByRole = requestedByRole;
    }

    public String getApprovalRecordId() {
        return approvalRecordId;
    }

    public void setApprovalRecordId(String approvalRecordId) {
        this.approvalRecordId = approvalRecordId;
    }

    public String getCommandText() {
        return commandText;
    }

    public void setCommandText(String commandText) {
        this.commandText = commandText;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public Integer getExitCode() {
        return exitCode;
    }

    public void setExitCode(Integer exitCode) {
        this.exitCode = exitCode;
    }

    public Instant getStartedAt() {
        return startedAt;
    }

    public void setStartedAt(Instant startedAt) {
        this.startedAt = startedAt;
    }

    public Instant getFinishedAt() {
        return finishedAt;
    }

    public void setFinishedAt(Instant finishedAt) {
        this.finishedAt = finishedAt;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(Instant updatedAt) {
        this.updatedAt = updatedAt;
    }

    public String getToolType() {
        return toolType;
    }

    public void setToolType(String toolType) {
        this.toolType = toolType;
    }

    public String getApprovalDecision() {
        return approvalDecision;
    }

    public void setApprovalDecision(String approvalDecision) {
        this.approvalDecision = approvalDecision;
    }

    public String getStdoutSummary() {
        return stdoutSummary;
    }

    public void setStdoutSummary(String stdoutSummary) {
        this.stdoutSummary = stdoutSummary;
    }

    public String getStderrSummary() {
        return stderrSummary;
    }

    public void setStderrSummary(String stderrSummary) {
        this.stderrSummary = stderrSummary;
    }

    public Long getDurationMillis() {
        return durationMillis;
    }

    public void setDurationMillis(Long durationMillis) {
        this.durationMillis = durationMillis;
    }

    public String getApproverId() {
        return approverId;
    }

    public void setApproverId(String approverId) {
        this.approverId = approverId;
    }

    public String getApprovalNote() {
        return approvalNote;
    }

    public void setApprovalNote(String approvalNote) {
        this.approvalNote = approvalNote;
    }

    public Instant getDecidedAt() {
        return decidedAt;
    }

    public void setDecidedAt(Instant decidedAt) {
        this.decidedAt = decidedAt;
    }

    public String getSafetyRejectionReason() {
        return safetyRejectionReason;
    }

    public void setSafetyRejectionReason(String safetyRejectionReason) {
        this.safetyRejectionReason = safetyRejectionReason;
    }

    public String getCanceledBy() {
        return canceledBy;
    }

    public void setCanceledBy(String canceledBy) {
        this.canceledBy = canceledBy;
    }

    public String getCancelNote() {
        return cancelNote;
    }

    public void setCancelNote(String cancelNote) {
        this.cancelNote = cancelNote;
    }

    public Instant getCanceledAt() {
        return canceledAt;
    }

    public void setCanceledAt(Instant canceledAt) {
        this.canceledAt = canceledAt;
    }

    public Integer getSortOrder() {
        return sortOrder;
    }

    public void setSortOrder(Integer sortOrder) {
        this.sortOrder = sortOrder;
    }
}
