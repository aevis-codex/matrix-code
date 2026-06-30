package com.matrixcode.persistence.mybatis.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.matrixcode.identity.domain.ProjectMember;

import java.time.Instant;

@TableName("matrixcode_project_members")
public class ProjectMemberEntity {

    @TableId(value = "id", type = IdType.INPUT)
    private String id;
    private String projectId;
    private String userId;
    private String roleKey;
    private String status;
    private Instant joinedAt;
    private Instant createdAt;
    private Instant updatedAt;

    public static ProjectMemberEntity fromDomain(ProjectMember member) {
        var entity = new ProjectMemberEntity();
        entity.setId(member.id());
        entity.setProjectId(member.projectId());
        entity.setUserId(member.userId());
        entity.setRoleKey(member.roleKey());
        entity.setStatus(member.status());
        entity.setJoinedAt(timestampOrEpoch(member.joinedAt()));
        entity.setCreatedAt(timestampOrEpoch(member.createdAt()));
        entity.setUpdatedAt(timestampOrEpoch(member.updatedAt()));
        return entity;
    }

    public ProjectMember toDomain() {
        return new ProjectMember(
                id,
                projectId,
                userId,
                roleKey,
                status,
                joinedAt == null ? Instant.EPOCH : joinedAt,
                createdAt == null ? Instant.EPOCH : createdAt,
                updatedAt == null ? Instant.EPOCH : updatedAt
        );
    }

    private static Instant timestampOrEpoch(Instant instant) {
        return instant == null ? Instant.EPOCH : instant;
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

    public Instant getJoinedAt() {
        return joinedAt;
    }

    public void setJoinedAt(Instant joinedAt) {
        this.joinedAt = joinedAt;
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
