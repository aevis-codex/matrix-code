package com.matrixcode.persistence.mybatis.entity;

import com.baomidou.mybatisplus.annotation.FieldStrategy;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.matrixcode.deployment.domain.DeploymentTarget;
import com.matrixcode.deployment.domain.DeploymentTargetStatus;

import java.time.Instant;

@TableName("matrixcode_deployment_targets")
public class DeploymentTargetEntity {

    private static final String PROVIDER = "manual";

    @TableId(value = "id", type = IdType.ASSIGN_ID)
    private String id;
    private String projectId;
    private String name;
    private String environmentKey;
    @TableField(value = "provider", insertStrategy = FieldStrategy.ALWAYS, updateStrategy = FieldStrategy.ALWAYS)
    private String provider;
    private String status;
    @TableField(value = "endpoint_url", insertStrategy = FieldStrategy.ALWAYS, updateStrategy = FieldStrategy.ALWAYS)
    private String endpointUrl;
    @TableField(value = "ssh_address", insertStrategy = FieldStrategy.ALWAYS, updateStrategy = FieldStrategy.ALWAYS)
    private String sshAddress;
    @TableField(value = "deploy_note", insertStrategy = FieldStrategy.ALWAYS, updateStrategy = FieldStrategy.ALWAYS)
    private String deployNote;
    @TableField(value = "health_check_url", insertStrategy = FieldStrategy.ALWAYS, updateStrategy = FieldStrategy.ALWAYS)
    private String healthCheckUrl;
    @TableField(value = "rollback_note", insertStrategy = FieldStrategy.ALWAYS, updateStrategy = FieldStrategy.ALWAYS)
    private String rollbackNote;
    @TableField(value = "remote_executed", insertStrategy = FieldStrategy.ALWAYS, updateStrategy = FieldStrategy.ALWAYS)
    private Boolean remoteExecuted;
    @TableField(value = "created_at", updateStrategy = FieldStrategy.NEVER)
    private Instant createdAt;
    private Instant updatedAt;

    /**
     * 将部署目标领域对象转换为正式表实体。
     *
     * <p>`environment_key` 沿用旧 JDBC 仓储的稳定策略，使用部署目标 ID 承载唯一环境键；
     * `provider` 当前保持 `manual`，为后续云厂商或远程部署适配器预留扩展点。</p>
     */
    public static DeploymentTargetEntity fromDomain(DeploymentTarget target) {
        var entity = new DeploymentTargetEntity();
        entity.setId(target.id());
        entity.setProjectId(target.projectId());
        entity.setName(target.environmentName());
        entity.setEnvironmentKey(target.id());
        entity.setProvider(PROVIDER);
        entity.setStatus(target.status().name());
        entity.setEndpointUrl(target.environmentUrl());
        entity.setSshAddress(blankToNull(target.sshAddress()));
        entity.setDeployNote(blankToNull(target.deployNote()));
        entity.setHealthCheckUrl(blankToNull(target.healthCheckUrl()));
        entity.setRollbackNote(blankToNull(target.rollbackNote()));
        entity.setRemoteExecuted(target.remoteExecuted());
        entity.setCreatedAt(timestampOrEpoch(target.updatedAt()));
        entity.setUpdatedAt(timestampOrEpoch(target.updatedAt()));
        return entity;
    }

    /**
     * 将正式表实体恢复为部署目标领域对象。
     *
     * <p>历史数据可能缺少扩展字段；转换时集中兜底空文本、健康检查地址、远程执行标记和更新时间，
     * 避免应用服务直接处理数据库空值细节。</p>
     */
    public DeploymentTarget toDomain() {
        return new DeploymentTarget(
                id,
                projectId,
                name,
                nullToBlank(endpointUrl),
                nullToBlank(sshAddress),
                nullToBlank(deployNote),
                textOr(healthCheckUrl, endpointUrl),
                nullToBlank(rollbackNote),
                DeploymentTargetStatus.valueOf(status),
                Boolean.TRUE.equals(remoteExecuted),
                updatedAt == null ? Instant.EPOCH : updatedAt
        );
    }

    private static Instant timestampOrEpoch(Instant instant) {
        return instant == null ? Instant.EPOCH : instant;
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private String nullToBlank(String value) {
        return value == null ? "" : value;
    }

    private String textOr(String value, String fallback) {
        return value == null || value.isBlank() ? nullToBlank(fallback) : value;
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

    public String getEnvironmentKey() {
        return environmentKey;
    }

    public void setEnvironmentKey(String environmentKey) {
        this.environmentKey = environmentKey;
    }

    public String getProvider() {
        return provider;
    }

    public void setProvider(String provider) {
        this.provider = provider;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public String getEndpointUrl() {
        return endpointUrl;
    }

    public void setEndpointUrl(String endpointUrl) {
        this.endpointUrl = endpointUrl;
    }

    public String getSshAddress() {
        return sshAddress;
    }

    public void setSshAddress(String sshAddress) {
        this.sshAddress = sshAddress;
    }

    public String getDeployNote() {
        return deployNote;
    }

    public void setDeployNote(String deployNote) {
        this.deployNote = deployNote;
    }

    public String getHealthCheckUrl() {
        return healthCheckUrl;
    }

    public void setHealthCheckUrl(String healthCheckUrl) {
        this.healthCheckUrl = healthCheckUrl;
    }

    public String getRollbackNote() {
        return rollbackNote;
    }

    public void setRollbackNote(String rollbackNote) {
        this.rollbackNote = rollbackNote;
    }

    public Boolean getRemoteExecuted() {
        return remoteExecuted;
    }

    public void setRemoteExecuted(Boolean remoteExecuted) {
        this.remoteExecuted = remoteExecuted;
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
