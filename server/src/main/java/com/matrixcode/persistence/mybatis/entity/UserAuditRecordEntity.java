package com.matrixcode.persistence.mybatis.entity;

import com.baomidou.mybatisplus.annotation.FieldStrategy;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.matrixcode.identity.domain.UserAuditRecord;

import java.time.Instant;

@TableName("matrixcode_audit_records")
public class UserAuditRecordEntity {

    @TableId(value = "id", type = IdType.ASSIGN_ID)
    private String id;
    private String projectId;
    private String actorUserId;
    @TableField(value = "actor_role", insertStrategy = FieldStrategy.ALWAYS, updateStrategy = FieldStrategy.ALWAYS)
    private String actorRole;
    private String actionKey;
    private String targetType;
    @TableField(value = "target_id", insertStrategy = FieldStrategy.ALWAYS, updateStrategy = FieldStrategy.ALWAYS)
    private String targetId;
    @TableField(value = "decision", insertStrategy = FieldStrategy.ALWAYS, updateStrategy = FieldStrategy.ALWAYS)
    private String decision;
    @TableField(value = "summary", insertStrategy = FieldStrategy.ALWAYS, updateStrategy = FieldStrategy.ALWAYS)
    private String summary;
    private Instant createdAt;
    private Instant updatedAt;
    @TableField(value = "task_id", insertStrategy = FieldStrategy.ALWAYS, updateStrategy = FieldStrategy.ALWAYS)
    private String taskId;
    @TableField(value = "tool_type", insertStrategy = FieldStrategy.ALWAYS, updateStrategy = FieldStrategy.ALWAYS)
    private String toolType;
    @TableField(value = "workspace_path", insertStrategy = FieldStrategy.ALWAYS, updateStrategy = FieldStrategy.ALWAYS)
    private String workspacePath;
    @TableField(value = "occurred_at", insertStrategy = FieldStrategy.ALWAYS, updateStrategy = FieldStrategy.ALWAYS)
    private Instant occurredAt;
    @TableField(value = "sort_order", insertStrategy = FieldStrategy.ALWAYS, updateStrategy = FieldStrategy.ALWAYS)
    private Integer sortOrder;

    /**
     * 构造身份域审计实体。
     *
     * <p>身份审计只写入低敏摘要字段；本地执行专属字段保持为空，避免把命令、路径等上下文
     * 混入登录、会话和成员治理审计。</p>
     */
    public static UserAuditRecordEntity fromDomain(UserAuditRecord record) {
        var occurredAt = timestampOrEpoch(record.occurredAt());
        var entity = new UserAuditRecordEntity();
        entity.setId(record.id());
        entity.setProjectId(record.projectId());
        entity.setActorUserId(record.actorUserId());
        entity.setActorRole(textOr(record.actorRole(), ""));
        entity.setActionKey(record.actionKey());
        entity.setTargetType(textOr(record.targetType(), "IDENTITY_SESSION"));
        entity.setTargetId(textOr(record.targetId(), record.actorUserId()));
        entity.setDecision(textOr(record.decision(), "ALLOW"));
        entity.setSummary(textOr(record.summary(), ""));
        entity.setCreatedAt(occurredAt);
        entity.setUpdatedAt(occurredAt);
        entity.setTaskId(null);
        entity.setToolType(null);
        entity.setWorkspacePath(null);
        entity.setOccurredAt(occurredAt);
        entity.setSortOrder(0);
        return entity;
    }

    public UserAuditRecord toDomain() {
        var occurred = occurredAt == null ? createdAt : occurredAt;
        return new UserAuditRecord(
                id,
                projectId,
                actorUserId,
                actorRole == null ? "" : actorRole,
                actionKey,
                targetType,
                targetId == null ? "" : targetId,
                decision == null ? "" : decision,
                summary == null ? "" : summary,
                occurred == null ? Instant.EPOCH : occurred
        );
    }

    private static Instant timestampOrEpoch(Instant instant) {
        return instant == null ? Instant.EPOCH : instant;
    }

    private static String textOr(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value.trim();
    }

    public Instant timelineAt() {
        if (occurredAt != null) {
            return occurredAt;
        }
        if (createdAt != null) {
            return createdAt;
        }
        return Instant.EPOCH;
    }

    public Integer sortOrderOrZero() {
        return sortOrder == null ? 0 : sortOrder;
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
