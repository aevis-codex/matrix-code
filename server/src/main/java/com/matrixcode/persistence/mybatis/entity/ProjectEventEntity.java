package com.matrixcode.persistence.mybatis.entity;

import com.baomidou.mybatisplus.annotation.FieldStrategy;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.matrixcode.realtime.domain.ProjectEvent;

import java.time.Instant;

@TableName("matrixcode_project_events")
public class ProjectEventEntity {

    @TableId(value = "id", type = IdType.ASSIGN_ID)
    private String id;
    private String projectId;
    private String eventType;
    @TableField(value = "source_role", insertStrategy = FieldStrategy.ALWAYS, updateStrategy = FieldStrategy.ALWAYS)
    private String sourceRole;
    @TableField(value = "source_id", insertStrategy = FieldStrategy.ALWAYS, updateStrategy = FieldStrategy.ALWAYS)
    private String sourceId;
    private String title;
    @TableField(value = "payload", insertStrategy = FieldStrategy.ALWAYS, updateStrategy = FieldStrategy.ALWAYS)
    private String payload;
    private Instant createdAt;
    private Instant updatedAt;

    /**
     * 将项目事件领域对象转换为正式表实体。
     *
     * <p>来源角色和来源 ID 用于记录触发事件的角色与用户或对象编号；`title` 和 `payload`
     * 都写入消息，兼容旧 JDBC 仓储的读取兜底策略。</p>
     */
    public static ProjectEventEntity fromDomain(ProjectEvent event) {
        var entity = new ProjectEventEntity();
        entity.setId(event.id());
        entity.setProjectId(event.projectId());
        entity.setEventType(event.type());
        entity.setSourceRole(blankToNull(event.sourceRole()));
        entity.setSourceId(blankToNull(event.sourceId()));
        entity.setTitle(event.message());
        entity.setPayload(event.message());
        entity.setCreatedAt(event.occurredAt());
        entity.setUpdatedAt(event.occurredAt());
        return entity;
    }

    /**
     * 将正式表实体恢复为领域事件。
     *
     * <p>历史记录可能只有 `payload` 没有 `title`，因此优先取 `title`，否则使用 `payload`。</p>
     */
    public ProjectEvent toDomain() {
        return new ProjectEvent(
                id,
                projectId,
                eventType,
                textOr(title, payload),
                createdAt == null ? Instant.EPOCH : createdAt,
                sourceRole,
                sourceId
        );
    }

    private String textOr(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
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

    public String getEventType() {
        return eventType;
    }

    public void setEventType(String eventType) {
        this.eventType = eventType;
    }

    public String getSourceRole() {
        return sourceRole;
    }

    public void setSourceRole(String sourceRole) {
        this.sourceRole = sourceRole;
    }

    public String getSourceId() {
        return sourceId;
    }

    public void setSourceId(String sourceId) {
        this.sourceId = sourceId;
    }

    public String getTitle() {
        return title;
    }

    public void setTitle(String title) {
        this.title = title;
    }

    public String getPayload() {
        return payload;
    }

    public void setPayload(String payload) {
        this.payload = payload;
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
}
