package com.matrixcode.persistence.mybatis.entity;

import com.baomidou.mybatisplus.annotation.FieldStrategy;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.matrixcode.runtime.domain.RuntimeNotification;
import com.matrixcode.runtime.domain.RuntimeNotificationLevel;
import com.matrixcode.runtime.domain.RuntimeNotificationSourceType;

import java.time.Instant;

@TableName("matrixcode_runtime_notifications")
public class RuntimeNotificationEntity {

    @TableId(value = "id", type = IdType.INPUT)
    private String id;
    private String projectId;
    @TableField(value = "user_id", insertStrategy = FieldStrategy.ALWAYS, updateStrategy = FieldStrategy.ALWAYS)
    private String userId;
    private String levelKey;
    private String sourceType;
    @TableField(value = "source_id", insertStrategy = FieldStrategy.ALWAYS, updateStrategy = FieldStrategy.ALWAYS)
    private String sourceId;
    private String title;
    @TableField(value = "message", insertStrategy = FieldStrategy.ALWAYS, updateStrategy = FieldStrategy.ALWAYS)
    private String message;
    @TableField(value = "read_at", insertStrategy = FieldStrategy.ALWAYS, updateStrategy = FieldStrategy.ALWAYS)
    private Instant readAt;
    private Instant createdAt;
    private Instant updatedAt;

    /**
     * 将运行态提醒领域对象转换为正式表实体。
     *
     * <p>提醒的 `readByUserId` 在正式表中映射为 `user_id`，空字符串会落库为 `null`，
     * 这样可以保留未读或无操作者的历史提醒语义。</p>
     */
    public static RuntimeNotificationEntity fromDomain(RuntimeNotification notification) {
        var entity = new RuntimeNotificationEntity();
        entity.setId(notification.id());
        entity.setProjectId(notification.projectId());
        entity.setUserId(blankToNull(notification.readByUserId()));
        entity.setLevelKey(notification.level().name());
        entity.setSourceType(notification.sourceType().name());
        entity.setSourceId(notification.sourceId());
        entity.setTitle(notification.title());
        entity.setMessage(notification.message());
        entity.setReadAt(notification.readAt());
        entity.setCreatedAt(notification.occurredAt());
        entity.setUpdatedAt(notification.readAt() == null ? notification.occurredAt() : notification.readAt());
        return entity;
    }

    /**
     * 将正式表实体恢复为运行态提醒领域对象。
     *
     * <p>旧数据中 `source_id` 或 `user_id` 可能为空，转换时分别兜底为提醒 ID 和空字符串，
     * 保持领域对象的非空约束。</p>
     */
    public RuntimeNotification toDomain() {
        return new RuntimeNotification(
                id,
                projectId,
                RuntimeNotificationLevel.valueOf(levelKey),
                title,
                message,
                RuntimeNotificationSourceType.valueOf(sourceType),
                sourceId == null || sourceId.isBlank() ? id : sourceId,
                createdAt == null ? Instant.EPOCH : createdAt,
                readAt,
                userId == null ? "" : userId
        );
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

    public String getUserId() {
        return userId;
    }

    public void setUserId(String userId) {
        this.userId = userId;
    }

    public String getLevelKey() {
        return levelKey;
    }

    public void setLevelKey(String levelKey) {
        this.levelKey = levelKey;
    }

    public String getSourceType() {
        return sourceType;
    }

    public void setSourceType(String sourceType) {
        this.sourceType = sourceType;
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

    public String getMessage() {
        return message;
    }

    public void setMessage(String message) {
        this.message = message;
    }

    public Instant getReadAt() {
        return readAt;
    }

    public void setReadAt(Instant readAt) {
        this.readAt = readAt;
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
