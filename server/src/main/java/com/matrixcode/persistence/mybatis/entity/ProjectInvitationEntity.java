package com.matrixcode.persistence.mybatis.entity;

import com.baomidou.mybatisplus.annotation.FieldStrategy;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.matrixcode.identity.application.ProjectIdentityRepository.StoredProjectInvitation;
import com.matrixcode.identity.domain.ProjectInvitation;

import java.time.Instant;

@TableName("matrixcode_project_invitations")
public class ProjectInvitationEntity {

    @TableId(value = "id", type = IdType.INPUT)
    private String id;
    private String projectId;
    private String inviteeUserId;
    private String displayName;
    private String roleKey;
    private String status;
    private String tokenHash;
    private String createdByUserId;
    private Instant expiresAt;
    @TableField(value = "accepted_at", insertStrategy = FieldStrategy.ALWAYS, updateStrategy = FieldStrategy.ALWAYS)
    private Instant acceptedAt;
    @TableField(value = "created_at", updateStrategy = FieldStrategy.NEVER)
    private Instant createdAt;
    private Instant updatedAt;

    public static ProjectInvitationEntity fromStored(StoredProjectInvitation storedInvitation) {
        var entity = fromInvitation(storedInvitation.invitation());
        entity.setTokenHash(storedInvitation.tokenHash());
        return entity;
    }

    /**
     * 构造不带 token hash 的邀请实体。
     *
     * <p>用于撤销、接受和过期状态更新；`token_hash` 字段不设置，让 MyBatis-Plus 默认策略
     * 保留数据库中的既有哈希值。</p>
     */
    public static ProjectInvitationEntity fromInvitation(ProjectInvitation invitation) {
        var entity = new ProjectInvitationEntity();
        entity.setId(invitation.id());
        entity.setProjectId(invitation.projectId());
        entity.setInviteeUserId(invitation.inviteeUserId());
        entity.setDisplayName(textOr(invitation.displayName(), invitation.inviteeUserId()));
        entity.setRoleKey(invitation.roleKey());
        entity.setStatus(textOr(invitation.status(), "PENDING"));
        entity.setCreatedByUserId(invitation.createdByUserId());
        entity.setExpiresAt(timestampOrEpoch(invitation.expiresAt()));
        entity.setAcceptedAt(invitation.acceptedAt());
        entity.setCreatedAt(timestampOrEpoch(invitation.createdAt()));
        entity.setUpdatedAt(timestampOrEpoch(invitation.updatedAt()));
        return entity;
    }

    public ProjectInvitation toDomain() {
        return new ProjectInvitation(
                id,
                projectId,
                inviteeUserId,
                displayName,
                roleKey,
                status,
                createdByUserId,
                expiresAt == null ? Instant.EPOCH : expiresAt,
                acceptedAt,
                createdAt == null ? Instant.EPOCH : createdAt,
                updatedAt == null ? Instant.EPOCH : updatedAt
        );
    }

    private static Instant timestampOrEpoch(Instant instant) {
        return instant == null ? Instant.EPOCH : instant;
    }

    private static String textOr(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value.trim();
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

    public String getInviteeUserId() {
        return inviteeUserId;
    }

    public void setInviteeUserId(String inviteeUserId) {
        this.inviteeUserId = inviteeUserId;
    }

    public String getDisplayName() {
        return displayName;
    }

    public void setDisplayName(String displayName) {
        this.displayName = displayName;
    }

    public String getRoleKey() {
        return roleKey;
    }

    public void setRoleKey(String roleKey) {
        this.roleKey = roleKey;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public String getTokenHash() {
        return tokenHash;
    }

    public void setTokenHash(String tokenHash) {
        this.tokenHash = tokenHash;
    }

    public String getCreatedByUserId() {
        return createdByUserId;
    }

    public void setCreatedByUserId(String createdByUserId) {
        this.createdByUserId = createdByUserId;
    }

    public Instant getExpiresAt() {
        return expiresAt;
    }

    public void setExpiresAt(Instant expiresAt) {
        this.expiresAt = expiresAt;
    }

    public Instant getAcceptedAt() {
        return acceptedAt;
    }

    public void setAcceptedAt(Instant acceptedAt) {
        this.acceptedAt = acceptedAt;
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
