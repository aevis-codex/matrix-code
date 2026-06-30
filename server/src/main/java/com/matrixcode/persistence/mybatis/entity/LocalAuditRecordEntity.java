package com.matrixcode.persistence.mybatis.entity;

import com.baomidou.mybatisplus.annotation.FieldStrategy;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.matrixcode.approval.domain.ApprovalDecision;
import com.matrixcode.approval.domain.AuditRecord;

import java.time.Instant;

@TableName("matrixcode_audit_records")
public class LocalAuditRecordEntity {

    public static final String TARGET_TYPE = "LOCAL_EXECUTION_TASK";

    @TableId(value = "id", type = IdType.INPUT)
    private String id;
    private String projectId;
    private String actorUserId;
    @TableField(value = "actor_role", insertStrategy = FieldStrategy.ALWAYS)
    private String actorRole;
    private String actionKey;
    private String targetType;
    @TableField(value = "target_id", insertStrategy = FieldStrategy.ALWAYS)
    private String targetId;
    @TableField(value = "decision", insertStrategy = FieldStrategy.ALWAYS)
    private String decision;
    @TableField(value = "summary", insertStrategy = FieldStrategy.ALWAYS)
    private String summary;
    private Instant createdAt;
    private Instant updatedAt;
    @TableField(value = "task_id", insertStrategy = FieldStrategy.ALWAYS)
    private String taskId;
    @TableField(value = "tool_type", insertStrategy = FieldStrategy.ALWAYS)
    private String toolType;
    @TableField(value = "workspace_path", insertStrategy = FieldStrategy.ALWAYS)
    private String workspacePath;
    @TableField(value = "occurred_at", insertStrategy = FieldStrategy.ALWAYS)
    private Instant occurredAt;
    @TableField(value = "sort_order", insertStrategy = FieldStrategy.ALWAYS)
    private Integer sortOrder;

    /**
     * 将本地执行审批审计转换为共享审计表实体。
     *
     * <p>`target_type` 固定为 `LOCAL_EXECUTION_TASK`，这样本地执行仓储替换审计时
     * 只会影响本域数据，不会清理身份域、成员域等共享审计记录。</p>
     */
    public static LocalAuditRecordEntity fromDomain(AuditRecord record, String projectId, int sortOrder, Instant now) {
        var occurredAt = record.occurredAt() == null ? Instant.EPOCH : record.occurredAt();
        var entity = new LocalAuditRecordEntity();
        entity.setId(record.id());
        entity.setProjectId(projectId);
        entity.setActorUserId(blankToNull(record.actorId()));
        entity.setActorRole(textOr(record.actorId(), ""));
        entity.setActionKey(record.toolType());
        entity.setTargetType(TARGET_TYPE);
        entity.setTargetId(record.taskId());
        entity.setDecision(record.decision() == null ? ApprovalDecision.ASK.name() : record.decision().name());
        entity.setSummary(textOr(record.summary(), ""));
        entity.setCreatedAt(occurredAt);
        entity.setUpdatedAt(now);
        entity.setTaskId(record.taskId());
        entity.setToolType(record.toolType());
        entity.setWorkspacePath(record.workspacePath());
        entity.setOccurredAt(occurredAt);
        entity.setSortOrder(sortOrder);
        return entity;
    }

    /**
     * 将共享审计表实体恢复为本地执行审计领域对象。
     */
    public AuditRecord toDomain() {
        return new AuditRecord(
                id,
                textOr(taskId, targetId),
                textOr(actorRole, actorUserId),
                textOr(toolType, actionKey),
                textOr(workspacePath, ""),
                textOr(summary, ""),
                enumOr(decision, ApprovalDecision.ASK),
                occurredAt == null ? timestampOrEpoch(createdAt) : occurredAt
        );
    }

    private static Instant timestampOrEpoch(Instant instant) {
        return instant == null ? Instant.EPOCH : instant;
    }

    private static <T extends Enum<T>> T enumOr(String value, T fallback) {
        if (value == null || value.isBlank()) {
            return fallback;
        }
        return Enum.valueOf(fallback.getDeclaringClass(), value);
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
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

    public String getActorUserId() {
        return actorUserId;
    }

    public void setActorUserId(String actorUserId) {
        this.actorUserId = actorUserId;
    }

    public String getActorRole() {
        return actorRole;
    }

    public void setActorRole(String actorRole) {
        this.actorRole = actorRole;
    }

    public String getActionKey() {
        return actionKey;
    }

    public void setActionKey(String actionKey) {
        this.actionKey = actionKey;
    }

    public String getTargetType() {
        return targetType;
    }

    public void setTargetType(String targetType) {
        this.targetType = targetType;
    }

    public String getTargetId() {
        return targetId;
    }

    public void setTargetId(String targetId) {
        this.targetId = targetId;
    }

    public String getDecision() {
        return decision;
    }

    public void setDecision(String decision) {
        this.decision = decision;
    }

    public String getSummary() {
        return summary;
    }

    public void setSummary(String summary) {
        this.summary = summary;
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

    public String getTaskId() {
        return taskId;
    }

    public void setTaskId(String taskId) {
        this.taskId = taskId;
    }

    public String getToolType() {
        return toolType;
    }

    public void setToolType(String toolType) {
        this.toolType = toolType;
    }

    public String getWorkspacePath() {
        return workspacePath;
    }

    public void setWorkspacePath(String workspacePath) {
        this.workspacePath = workspacePath;
    }

    public Instant getOccurredAt() {
        return occurredAt;
    }

    public void setOccurredAt(Instant occurredAt) {
        this.occurredAt = occurredAt;
    }

    public Integer getSortOrder() {
        return sortOrder;
    }

    public void setSortOrder(Integer sortOrder) {
        this.sortOrder = sortOrder;
    }
}
