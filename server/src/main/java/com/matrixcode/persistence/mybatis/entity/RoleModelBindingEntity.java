package com.matrixcode.persistence.mybatis.entity;

import com.baomidou.mybatisplus.annotation.FieldStrategy;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.matrixcode.modelgateway.domain.ModelRole;
import com.matrixcode.modelgateway.domain.RoleModelBinding;

import java.time.Instant;

@TableName("matrixcode_role_model_bindings")
public class RoleModelBindingEntity {

    @TableId(value = "id", type = IdType.ASSIGN_ID)
    private String id;
    private String projectId;
    private String roleKey;
    private String providerId;
    private String modelName;
    private String currency;
    private Double cacheHitPerMillion;
    private Double cacheMissInputPerMillion;
    private Double outputPerMillion;
    private Integer contextBudgetTokens;
    private String toolContractVersion;
    @TableField(value = "created_at", updateStrategy = FieldStrategy.NEVER)
    private Instant createdAt;
    private Instant updatedAt;

    /**
     * 将领域绑定转换为正式表实体。
     *
     * <p>ID 使用 `项目::角色` 的稳定格式，确保同一项目同一角色的模型选择可以通过
     * `updateById` 做幂等覆盖。</p>
     */
    public static RoleModelBindingEntity fromDomain(RoleModelBinding binding, Instant now) {
        var timestamp = now == null ? Instant.now() : now;
        var entity = new RoleModelBindingEntity();
        entity.setId(stableId(binding.projectId(), binding.role()));
        entity.setProjectId(binding.projectId());
        entity.setRoleKey(binding.role().name());
        entity.setProviderId(binding.providerId());
        entity.setModelName(binding.model());
        entity.setCurrency(binding.currency());
        entity.setCacheHitPerMillion(binding.cacheHitPerMillion());
        entity.setCacheMissInputPerMillion(binding.cacheMissInputPerMillion());
        entity.setOutputPerMillion(binding.outputPerMillion());
        entity.setContextBudgetTokens(binding.contextBudgetTokens());
        entity.setToolContractVersion(binding.toolContractVersion());
        entity.setCreatedAt(timestamp);
        entity.setUpdatedAt(timestamp);
        return entity;
    }

    /**
     * 将正式表实体恢复为角色模型绑定领域对象。
     *
     * <p>数值列做保守兜底，避免历史脏数据导致服务启动失败；领域构造器仍会校验必须字段。</p>
     */
    public RoleModelBinding toDomain() {
        return new RoleModelBinding(
                projectId,
                ModelRole.valueOf(roleKey),
                providerId,
                modelName,
                currency,
                cacheHitPerMillion == null ? 0.0 : cacheHitPerMillion,
                cacheMissInputPerMillion == null ? 0.0 : cacheMissInputPerMillion,
                outputPerMillion == null ? 0.0 : outputPerMillion,
                contextBudgetTokens == null ? 32_000 : contextBudgetTokens,
                toolContractVersion
        );
    }

    private static String stableId(String projectId, ModelRole role) {
        return projectId.trim() + "::" + role.name();
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

    public String getRoleKey() {
        return roleKey;
    }

    public void setRoleKey(String roleKey) {
        this.roleKey = roleKey;
    }

    public String getProviderId() {
        return providerId;
    }

    public void setProviderId(String providerId) {
        this.providerId = providerId;
    }

    public String getModelName() {
        return modelName;
    }

    public void setModelName(String modelName) {
        this.modelName = modelName;
    }

    public String getCurrency() {
        return currency;
    }

    public void setCurrency(String currency) {
        this.currency = currency;
    }

    public Double getCacheHitPerMillion() {
        return cacheHitPerMillion;
    }

    public void setCacheHitPerMillion(Double cacheHitPerMillion) {
        this.cacheHitPerMillion = cacheHitPerMillion;
    }

    public Double getCacheMissInputPerMillion() {
        return cacheMissInputPerMillion;
    }

    public void setCacheMissInputPerMillion(Double cacheMissInputPerMillion) {
        this.cacheMissInputPerMillion = cacheMissInputPerMillion;
    }

    public Double getOutputPerMillion() {
        return outputPerMillion;
    }

    public void setOutputPerMillion(Double outputPerMillion) {
        this.outputPerMillion = outputPerMillion;
    }

    public Integer getContextBudgetTokens() {
        return contextBudgetTokens;
    }

    public void setContextBudgetTokens(Integer contextBudgetTokens) {
        this.contextBudgetTokens = contextBudgetTokens;
    }

    public String getToolContractVersion() {
        return toolContractVersion;
    }

    public void setToolContractVersion(String toolContractVersion) {
        this.toolContractVersion = toolContractVersion;
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
