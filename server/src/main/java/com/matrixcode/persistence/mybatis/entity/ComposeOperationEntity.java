package com.matrixcode.persistence.mybatis.entity;

import com.baomidou.mybatisplus.annotation.FieldStrategy;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.matrixcode.deployment.domain.ComposeOperationRecord;
import com.matrixcode.deployment.domain.ComposeOperationStatus;
import com.matrixcode.deployment.domain.ComposeOperationType;

import java.time.Instant;

@TableName("matrixcode_compose_operations")
public class ComposeOperationEntity {

    @TableId(value = "id", type = IdType.INPUT)
    private String id;
    private String projectId;
    private String environmentId;
    private String actorId;
    private String operationType;
    private String status;
    @TableField(value = "summary", insertStrategy = FieldStrategy.ALWAYS, updateStrategy = FieldStrategy.ALWAYS)
    private String summary;
    @TableField(value = "log_excerpt", insertStrategy = FieldStrategy.ALWAYS, updateStrategy = FieldStrategy.ALWAYS)
    private String logExcerpt;
    private Instant createdAt;

    public static ComposeOperationEntity fromDomain(ComposeOperationRecord record) {
        var entity = new ComposeOperationEntity();
        entity.setId(record.id());
        entity.setProjectId(record.projectId());
        entity.setEnvironmentId(record.environmentId());
        entity.setActorId(record.actorId());
        entity.setOperationType(record.type().name());
        entity.setStatus(record.status().name());
        entity.setSummary(record.summary());
        entity.setLogExcerpt(record.logExcerpt());
        entity.setCreatedAt(record.createdAt() == null ? Instant.EPOCH : record.createdAt());
        return entity;
    }

    public ComposeOperationRecord toDomain() {
        return new ComposeOperationRecord(
                id,
                projectId,
                environmentId,
                actorId,
                ComposeOperationType.valueOf(operationType),
                ComposeOperationStatus.valueOf(status),
                summary,
                logExcerpt,
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

    public String getEnvironmentId() {
        return environmentId;
    }

    public void setEnvironmentId(String environmentId) {
        this.environmentId = environmentId;
    }

    public String getActorId() {
        return actorId;
    }

    public void setActorId(String actorId) {
        this.actorId = actorId;
    }

    public String getOperationType() {
        return operationType;
    }

    public void setOperationType(String operationType) {
        this.operationType = operationType;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public String getSummary() {
        return summary;
    }

    public void setSummary(String summary) {
        this.summary = summary;
    }

    public String getLogExcerpt() {
        return logExcerpt;
    }

    public void setLogExcerpt(String logExcerpt) {
        this.logExcerpt = logExcerpt;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }
}
