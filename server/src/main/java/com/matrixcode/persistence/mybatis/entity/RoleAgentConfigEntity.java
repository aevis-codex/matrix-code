package com.matrixcode.persistence.mybatis.entity;

import com.baomidou.mybatisplus.annotation.FieldStrategy;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.matrixcode.modelgateway.domain.ModelRole;
import com.matrixcode.roleagent.domain.RoleAgentConfig;

import java.time.Instant;

@TableName("matrixcode_role_agent_configs")
public class RoleAgentConfigEntity {

    @TableId(value = "id", type = IdType.ASSIGN_ID)
    private String id;
    private String projectId;
    private String roleKey;
    private String displayName;
    private String agentKind;
    private String modelProvider;
    private String modelName;
    private String toolContractVersion;
    private String cachePolicyId;
    private String volatileSuffixStrategy;
    private String cacheScopeStrategy;
    private String systemPrompt;
    private String userPromptTemplate;
    private String themeColor;
    private String fontFamily;
    private Integer fontSize;
    private Integer sortOrder;
    private Boolean enabled;
    @TableField(value = "created_at", updateStrategy = FieldStrategy.NEVER)
    private Instant createdAt;
    private Instant updatedAt;

    /**
     * 将角色智能体配置领域对象转换为数据库实体。
     *
     * <p>实体 ID 使用 `项目::角色` 的稳定格式，保证同一项目同一角色天然 upsert，
     * 同时让仓储层不需要再维护额外唯一键查询。</p>
     */
    public static RoleAgentConfigEntity fromDomain(RoleAgentConfig config) {
        var entity = new RoleAgentConfigEntity();
        entity.setId(config.projectId().trim() + "::" + config.role().name());
        entity.setProjectId(config.projectId());
        entity.setRoleKey(config.role().name());
        entity.setDisplayName(config.displayName());
        entity.setAgentKind(config.agentKind());
        entity.setModelProvider(config.providerId());
        entity.setModelName(config.model());
        entity.setToolContractVersion(config.toolContractVersion());
        entity.setCachePolicyId(config.cachePolicyId());
        entity.setVolatileSuffixStrategy(config.volatileSuffixStrategy());
        entity.setCacheScopeStrategy(config.cacheScopeStrategy());
        entity.setSystemPrompt(config.systemPrompt());
        entity.setUserPromptTemplate(config.userPromptTemplate());
        entity.setThemeColor(config.themeColor());
        entity.setFontFamily(config.fontFamily());
        entity.setFontSize(config.fontSize());
        entity.setSortOrder(config.sortOrder());
        entity.setEnabled(config.enabled());
        entity.setCreatedAt(config.updatedAt());
        entity.setUpdatedAt(config.updatedAt());
        return entity;
    }

    /**
     * 将数据库实体恢复为角色智能体配置领域对象。
     *
     * <p>该方法集中处理角色枚举、可空数值和时间字段兜底，避免控制器或应用服务直接依赖
     * 数据库列的空值细节。</p>
     */
    public RoleAgentConfig toDomain() {
        return new RoleAgentConfig(
                projectId,
                ModelRole.valueOf(roleKey),
                displayName,
                agentKind,
                modelProvider,
                modelName,
                toolContractVersion,
                systemPrompt,
                userPromptTemplate,
                themeColor,
                fontFamily,
                fontSize == null ? 14 : fontSize,
                sortOrder == null ? 0 : sortOrder,
                Boolean.TRUE.equals(enabled),
                cachePolicyId,
                volatileSuffixStrategy,
                cacheScopeStrategy,
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

    public String getRoleKey() {
        return roleKey;
    }

    public void setRoleKey(String roleKey) {
        this.roleKey = roleKey;
    }

    public String getDisplayName() {
        return displayName;
    }

    public void setDisplayName(String displayName) {
        this.displayName = displayName;
    }

    public String getAgentKind() {
        return agentKind;
    }

    public void setAgentKind(String agentKind) {
        this.agentKind = agentKind;
    }

    public String getModelProvider() {
        return modelProvider;
    }

    public void setModelProvider(String modelProvider) {
        this.modelProvider = modelProvider;
    }

    public String getModelName() {
        return modelName;
    }

    public void setModelName(String modelName) {
        this.modelName = modelName;
    }

    public String getToolContractVersion() {
        return toolContractVersion;
    }

    public void setToolContractVersion(String toolContractVersion) {
        this.toolContractVersion = toolContractVersion;
    }

    public String getCachePolicyId() {
        return cachePolicyId;
    }

    public void setCachePolicyId(String cachePolicyId) {
        this.cachePolicyId = cachePolicyId;
    }

    public String getVolatileSuffixStrategy() {
        return volatileSuffixStrategy;
    }

    public void setVolatileSuffixStrategy(String volatileSuffixStrategy) {
        this.volatileSuffixStrategy = volatileSuffixStrategy;
    }

    public String getCacheScopeStrategy() {
        return cacheScopeStrategy;
    }

    public void setCacheScopeStrategy(String cacheScopeStrategy) {
        this.cacheScopeStrategy = cacheScopeStrategy;
    }

    public String getSystemPrompt() {
        return systemPrompt;
    }

    public void setSystemPrompt(String systemPrompt) {
        this.systemPrompt = systemPrompt;
    }

    public String getUserPromptTemplate() {
        return userPromptTemplate;
    }

    public void setUserPromptTemplate(String userPromptTemplate) {
        this.userPromptTemplate = userPromptTemplate;
    }

    public String getThemeColor() {
        return themeColor;
    }

    public void setThemeColor(String themeColor) {
        this.themeColor = themeColor;
    }

    public String getFontFamily() {
        return fontFamily;
    }

    public void setFontFamily(String fontFamily) {
        this.fontFamily = fontFamily;
    }

    public Integer getFontSize() {
        return fontSize;
    }

    public void setFontSize(Integer fontSize) {
        this.fontSize = fontSize;
    }

    public Integer getSortOrder() {
        return sortOrder;
    }

    public void setSortOrder(Integer sortOrder) {
        this.sortOrder = sortOrder;
    }

    public Boolean getEnabled() {
        return enabled;
    }

    public void setEnabled(Boolean enabled) {
        this.enabled = enabled;
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
