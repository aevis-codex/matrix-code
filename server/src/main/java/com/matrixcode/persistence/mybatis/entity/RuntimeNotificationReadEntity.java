package com.matrixcode.persistence.mybatis.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.UUID;

@TableName("matrixcode_runtime_notification_reads")
public class RuntimeNotificationReadEntity {

    @TableId(value = "id", type = IdType.INPUT)
    private String id;
    private String projectId;
    private String notificationId;
    private String userId;
    private Instant readAt;
    private Instant createdAt;
    private Instant updatedAt;

    /**
     * 创建用户级运行态提醒已读回执实体。
     *
     * <p>回执 ID 基于项目、提醒和用户稳定生成，保证重复保存同一快照时不会产生语义不同的 ID。</p>
     */
    public static RuntimeNotificationReadEntity fromReceipt(
            String projectId,
            String notificationId,
            String userId,
            Instant readAt
    ) {
        var entity = new RuntimeNotificationReadEntity();
        entity.setId(stableId(projectId, notificationId, userId));
        entity.setProjectId(projectId);
        entity.setNotificationId(notificationId);
        entity.setUserId(userId);
        entity.setReadAt(readAt);
        entity.setCreatedAt(readAt);
        entity.setUpdatedAt(readAt);
        return entity;
    }

    private static String stableId(String projectId, String notificationId, String userId) {
        return UUID.nameUUIDFromBytes((projectId + ":" + notificationId + ":" + userId)
                .getBytes(StandardCharsets.UTF_8)).toString();
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

    public String getNotificationId() {
        return notificationId;
    }

    public void setNotificationId(String notificationId) {
        this.notificationId = notificationId;
    }

    public String getUserId() {
        return userId;
    }

    public void setUserId(String userId) {
        this.userId = userId;
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
