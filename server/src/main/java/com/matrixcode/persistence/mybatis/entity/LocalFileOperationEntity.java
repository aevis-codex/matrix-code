package com.matrixcode.persistence.mybatis.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.matrixcode.localexecution.domain.FileOperationRecord;
import com.matrixcode.localexecution.domain.FileOperationType;

import java.time.Instant;

@TableName("matrixcode_local_file_operations")
public class LocalFileOperationEntity {

    @TableId(value = "id", type = IdType.ASSIGN_ID)
    private String id;
    private String projectId;
    private String workspaceId;
    private String operationType;
    private String relativePath;
    private String operationStatus;
    private String summary;
    private Integer sortOrder;
    private Instant createdAt;
    private Instant persistedAt;

    /**
     * 将文件操作领域记录转换为正式表实体。
     *
     * <p>`sort_order` 由仓储按当前内存窗口顺序传入，确保服务重启后仍按最新在前恢复。</p>
     */
    public static LocalFileOperationEntity fromDomain(FileOperationRecord record, int sortOrder, Instant now) {
        var entity = new LocalFileOperationEntity();
        entity.setId(record.id());
        entity.setProjectId(record.projectId());
        entity.setWorkspaceId(record.workspaceId());
        entity.setOperationType(record.type().name());
        entity.setRelativePath(record.relativePath());
        entity.setOperationStatus(record.status());
        entity.setSummary(record.summary());
        entity.setSortOrder(sortOrder);
        entity.setCreatedAt(record.createdAt());
        entity.setPersistedAt(now == null ? Instant.now() : now);
        return entity;
    }

    /**
     * 将正式表实体恢复为文件操作领域记录。
     */
    public FileOperationRecord toDomain() {
        return new FileOperationRecord(
                id,
                projectId,
                workspaceId,
                FileOperationType.valueOf(operationType),
                relativePath,
                operationStatus,
                summary,
                createdAt == null ? Instant.EPOCH : createdAt
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

    public String getWorkspaceId() {
        return workspaceId;
    }

    public void setWorkspaceId(String workspaceId) {
        this.workspaceId = workspaceId;
    }

    public String getOperationType() {
        return operationType;
    }

    public void setOperationType(String operationType) {
        this.operationType = operationType;
    }

    public String getRelativePath() {
        return relativePath;
    }

    public void setRelativePath(String relativePath) {
        this.relativePath = relativePath;
    }

    public String getOperationStatus() {
        return operationStatus;
    }

    public void setOperationStatus(String operationStatus) {
        this.operationStatus = operationStatus;
    }

    public String getSummary() {
        return summary;
    }

    public void setSummary(String summary) {
        this.summary = summary;
    }

    public Integer getSortOrder() {
        return sortOrder;
    }

    public void setSortOrder(Integer sortOrder) {
        this.sortOrder = sortOrder;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }

    public Instant getPersistedAt() {
        return persistedAt;
    }

    public void setPersistedAt(Instant persistedAt) {
        this.persistedAt = persistedAt;
    }
}
