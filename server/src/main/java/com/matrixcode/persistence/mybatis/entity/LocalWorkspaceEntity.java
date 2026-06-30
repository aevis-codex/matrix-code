package com.matrixcode.persistence.mybatis.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.matrixcode.localexecution.domain.WorkspaceAuthorization;
import com.matrixcode.localexecution.domain.WorkspaceStatus;

import java.time.Instant;

@TableName("matrixcode_local_workspaces")
public class LocalWorkspaceEntity {

    @TableId(value = "id", type = IdType.ASSIGN_ID)
    private String id;
    private String projectId;
    private String name;
    private String rootPath;
    private String status;
    private Instant createdAt;
    private Instant lastAccessedAt;
    private Instant updatedAt;

    /**
     * 将工作区授权记录转换为正式表实体。
     *
     * <p>本地执行状态采用整体替换写入，`updated_at` 使用本次保存时间，`created_at`
     * 与 `last_accessed_at` 保留领域对象中的业务时间。</p>
     */
    public static LocalWorkspaceEntity fromDomain(WorkspaceAuthorization workspace, Instant now) {
        var entity = new LocalWorkspaceEntity();
        entity.setId(workspace.id());
        entity.setProjectId(workspace.projectId());
        entity.setName(workspace.name());
        entity.setRootPath(workspace.rootPath());
        entity.setStatus(workspace.status().name());
        entity.setCreatedAt(workspace.createdAt());
        entity.setLastAccessedAt(workspace.lastAccessedAt());
        entity.setUpdatedAt(now);
        return entity;
    }

    /**
     * 将正式表实体恢复为工作区授权领域对象。
     */
    public WorkspaceAuthorization toDomain() {
        return new WorkspaceAuthorization(
                id,
                projectId,
                name,
                rootPath,
                WorkspaceStatus.valueOf(status),
                createdAt == null ? Instant.EPOCH : createdAt,
                lastAccessedAt == null ? Instant.EPOCH : lastAccessedAt
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

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getRootPath() {
        return rootPath;
    }

    public void setRootPath(String rootPath) {
        this.rootPath = rootPath;
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

    public Instant getLastAccessedAt() {
        return lastAccessedAt;
    }

    public void setLastAccessedAt(Instant lastAccessedAt) {
        this.lastAccessedAt = lastAccessedAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(Instant updatedAt) {
        this.updatedAt = updatedAt;
    }
}
