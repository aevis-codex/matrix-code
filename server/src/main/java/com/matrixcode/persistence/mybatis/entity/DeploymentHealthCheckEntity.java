package com.matrixcode.persistence.mybatis.entity;

import com.baomidou.mybatisplus.annotation.FieldStrategy;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.matrixcode.deployment.domain.DeploymentHealthCheck;
import com.matrixcode.deployment.domain.DeploymentHealthStatus;

import java.time.Instant;

@TableName("matrixcode_deployment_health_checks")
public class DeploymentHealthCheckEntity {

    @TableId(value = "id", type = IdType.ASSIGN_ID)
    private String id;
    private String projectId;
    private String targetId;
    private String actorId;
    private String status;
    @TableField(value = "http_status", insertStrategy = FieldStrategy.ALWAYS, updateStrategy = FieldStrategy.ALWAYS)
    private Integer httpStatus;
    private Long durationMillis;
    @TableField(value = "summary", insertStrategy = FieldStrategy.ALWAYS, updateStrategy = FieldStrategy.ALWAYS)
    private String summary;
    private Instant checkedAt;

    public static DeploymentHealthCheckEntity fromDomain(DeploymentHealthCheck check) {
        var entity = new DeploymentHealthCheckEntity();
        entity.setId(check.id());
        entity.setProjectId(check.projectId());
        entity.setTargetId(check.targetId());
        entity.setActorId(check.actorId());
        entity.setStatus(check.status().name());
        entity.setHttpStatus(check.httpStatus());
        entity.setDurationMillis(check.durationMillis());
        entity.setSummary(check.summary());
        entity.setCheckedAt(check.checkedAt() == null ? Instant.EPOCH : check.checkedAt());
        return entity;
    }

    public DeploymentHealthCheck toDomain() {
        return new DeploymentHealthCheck(
                id,
                projectId,
                targetId,
                actorId,
                DeploymentHealthStatus.valueOf(status),
                httpStatus,
                durationMillis == null ? 0 : durationMillis,
                summary,
                checkedAt == null ? Instant.EPOCH : checkedAt
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

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public Integer getHttpStatus() {
        return httpStatus;
    }

    public void setHttpStatus(Integer httpStatus) {
        this.httpStatus = httpStatus;
    }

    public Long getDurationMillis() {
        return durationMillis;
    }

    public void setDurationMillis(Long durationMillis) {
        this.durationMillis = durationMillis;
    }

    public String getSummary() {
        return summary;
    }

    public void setSummary(String summary) {
        this.summary = summary;
    }

    public Instant getCheckedAt() {
        return checkedAt;
    }

    public void setCheckedAt(Instant checkedAt) {
        this.checkedAt = checkedAt;
    }
}
