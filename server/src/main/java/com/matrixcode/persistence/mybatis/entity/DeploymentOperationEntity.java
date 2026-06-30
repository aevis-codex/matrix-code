package com.matrixcode.persistence.mybatis.entity;

import com.baomidou.mybatisplus.annotation.FieldStrategy;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.matrixcode.deployment.domain.DeploymentOperationRecord;
import com.matrixcode.deployment.domain.DeploymentOperationStatus;
import com.matrixcode.deployment.domain.DeploymentOperationType;

import java.time.Instant;

@TableName("matrixcode_deployment_operations")
public class DeploymentOperationEntity {

    @TableId(value = "id", type = IdType.ASSIGN_ID)
    private String id;
    private String projectId;
    private String targetId;
    private String actorId;
    private String operationType;
    private String status;
    @TableField(value = "note", insertStrategy = FieldStrategy.ALWAYS, updateStrategy = FieldStrategy.ALWAYS)
    private String note;
    private Instant createdAt;

    public static DeploymentOperationEntity fromDomain(DeploymentOperationRecord record) {
        var entity = new DeploymentOperationEntity();
        entity.setId(record.id());
        entity.setProjectId(record.projectId());
        entity.setTargetId(record.targetId());
        entity.setActorId(record.actorId());
        entity.setOperationType(record.type().name());
        entity.setStatus(record.status().name());
        entity.setNote(record.note());
        entity.setCreatedAt(record.createdAt() == null ? Instant.EPOCH : record.createdAt());
        return entity;
    }

    public DeploymentOperationRecord toDomain() {
        return new DeploymentOperationRecord(
                id,
                projectId,
                targetId,
                actorId,
                DeploymentOperationType.valueOf(operationType),
                DeploymentOperationStatus.valueOf(status),
                note,
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

    public String getTargetId() {
        return targetId;
    }

    public void setTargetId(String targetId) {
        this.targetId = targetId;
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

    public String getNote() {
        return note;
    }

    public void setNote(String note) {
        this.note = note;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }
}
