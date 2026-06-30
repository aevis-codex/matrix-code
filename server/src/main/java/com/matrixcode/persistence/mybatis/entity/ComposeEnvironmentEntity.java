package com.matrixcode.persistence.mybatis.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.matrixcode.deployment.domain.ComposeEnvironment;
import com.matrixcode.deployment.domain.ComposeEnvironmentStatus;

import java.time.Instant;

@TableName("matrixcode_compose_environments")
public class ComposeEnvironmentEntity {

    @TableId(value = "id", type = IdType.INPUT)
    private String id;
    private String projectId;
    private String targetId;
    private String workspaceId;
    private String composeFilePath;
    private String projectName;
    private String serviceName;
    private String status;
    private Instant createdAt;
    private Instant updatedAt;

    public static ComposeEnvironmentEntity fromDomain(ComposeEnvironment environment) {
        var entity = new ComposeEnvironmentEntity();
        entity.setId(environment.id());
        entity.setProjectId(environment.projectId());
        entity.setTargetId(environment.targetId());
        entity.setWorkspaceId(environment.workspaceId());
        entity.setComposeFilePath(environment.composeFilePath());
        entity.setProjectName(environment.projectName());
        entity.setServiceName(environment.serviceName());
        entity.setStatus(environment.status().name());
        entity.setCreatedAt(environment.createdAt() == null ? Instant.EPOCH : environment.createdAt());
        entity.setUpdatedAt(environment.updatedAt() == null ? Instant.EPOCH : environment.updatedAt());
        return entity;
    }

    public ComposeEnvironment toDomain() {
        return new ComposeEnvironment(
                id,
                projectId,
                targetId,
                workspaceId,
                composeFilePath,
                projectName,
                serviceName,
                ComposeEnvironmentStatus.valueOf(status),
                createdAt == null ? Instant.EPOCH : createdAt,
                updatedAt == null ? Instant.EPOCH : updatedAt
        );
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

    public String getTargetId() {
        return targetId;
    }

    public void setTargetId(String targetId) {
        this.targetId = targetId;
    }

    public String getWorkspaceId() {
        return workspaceId;
    }

    public void setWorkspaceId(String workspaceId) {
        this.workspaceId = workspaceId;
    }

    public String getComposeFilePath() {
        return composeFilePath;
    }

    public void setComposeFilePath(String composeFilePath) {
        this.composeFilePath = composeFilePath;
    }

    public String getProjectName() {
        return projectName;
    }

    public void setProjectName(String projectName) {
        this.projectName = projectName;
    }

    public String getServiceName() {
        return serviceName;
    }

    public void setServiceName(String serviceName) {
        this.serviceName = serviceName;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
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
