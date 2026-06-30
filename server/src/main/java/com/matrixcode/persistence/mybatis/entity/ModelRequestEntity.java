package com.matrixcode.persistence.mybatis.entity;

import com.baomidou.mybatisplus.annotation.FieldStrategy;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.matrixcode.modelgateway.domain.ModelRequestRecord;
import com.matrixcode.modelgateway.domain.ModelRole;
import com.matrixcode.usage.domain.UsageRecord;

import java.time.Instant;
import java.util.List;

@TableName("matrixcode_model_requests")
public class ModelRequestEntity {

    @TableId(value = "id", type = IdType.INPUT)
    private String id;
    private String projectId;
    private String roleKey;
    private String providerId;
    private String modelName;
    private String answerSummary;
    @TableField(value = "actor_user_id", insertStrategy = FieldStrategy.ALWAYS, updateStrategy = FieldStrategy.ALWAYS)
    private String actorUserId;
    @TableField(value = "agent_run_id", insertStrategy = FieldStrategy.ALWAYS, updateStrategy = FieldStrategy.ALWAYS)
    private String agentRunId;
    private String usageRoleSessionId;
    private String usageModel;
    private Long cacheHitTokens;
    private Long cacheMissInputTokens;
    private Long outputTokens;
    private Double cacheHitRate;
    private Double estimatedCost;
    private String currency;
    private String cacheSource;
    private String cacheScopeId;
    private String stablePrefixHash;
    private Boolean providerUsageAvailable;
    private String cachePolicyId;
    private String volatileSuffixStrategy;
    private String promptPartitionPolicyId;
    private String promptPartitionFingerprint;
    private Integer stablePartitionCount;
    private Integer volatilePartitionCount;
    @TableField(value = "context_types", insertStrategy = FieldStrategy.ALWAYS, updateStrategy = FieldStrategy.ALWAYS)
    private String contextTypes;
    private Instant createdAt;

    /**
     * 将模型请求领域记录转换为正式表实体。
     *
     * <p>上下文类型在正式表中以 JSON 文本保存，由仓储负责序列化后传入，避免实体依赖
     * `ObjectMapper` 并保持领域对象不感知存储格式。</p>
     */
    public static ModelRequestEntity fromDomain(ModelRequestRecord record, String contextTypesJson) {
        var entity = new ModelRequestEntity();
        entity.setId(record.requestId());
        entity.setProjectId(record.projectId());
        entity.setRoleKey(record.role().name());
        entity.setProviderId(record.providerId());
        entity.setModelName(record.model());
        entity.setAnswerSummary(record.answerSummary());
        entity.setActorUserId(blankToNull(record.actorUserId()));
        entity.setAgentRunId(blankToNull(record.agentRunId()));
        entity.setUsageRoleSessionId(record.usage().roleSessionId());
        entity.setUsageModel(record.usage().model());
        entity.setCacheHitTokens(record.usage().cacheHitTokens());
        entity.setCacheMissInputTokens(record.usage().cacheMissInputTokens());
        entity.setOutputTokens(record.usage().outputTokens());
        entity.setCacheHitRate(record.usage().cacheHitRate());
        entity.setEstimatedCost(record.usage().estimatedCost());
        entity.setCurrency(record.usage().currency());
        entity.setCacheSource(record.usage().cacheSource());
        entity.setCacheScopeId(record.usage().cacheScopeId());
        entity.setStablePrefixHash(record.usage().stablePrefixHash());
        entity.setProviderUsageAvailable(record.usage().providerUsageAvailable());
        entity.setCachePolicyId(record.usage().cachePolicyId());
        entity.setVolatileSuffixStrategy(record.usage().volatileSuffixStrategy());
        entity.setPromptPartitionPolicyId(record.usage().promptPartitionPolicyId());
        entity.setPromptPartitionFingerprint(record.usage().promptPartitionFingerprint());
        entity.setStablePartitionCount(record.usage().stablePartitionCount());
        entity.setVolatilePartitionCount(record.usage().volatilePartitionCount());
        entity.setContextTypes(contextTypesJson);
        entity.setCreatedAt(record.createdAt());
        return entity;
    }

    /**
     * 将正式表实体恢复为模型请求领域记录。
     *
     * <p>该方法只接收已经解析好的上下文类型列表；JSON 解析错误由仓储统一转化为存储异常。</p>
     */
    public ModelRequestRecord toDomain(List<String> parsedContextTypes) {
        return new ModelRequestRecord(
                id,
                projectId,
                ModelRole.valueOf(roleKey),
                providerId,
                modelName,
                answerSummary,
                actorUserId == null ? "" : actorUserId,
                agentRunId == null ? "" : agentRunId,
                new UsageRecord(
                        usageRoleSessionId,
                        usageModel,
                        cacheHitTokens == null ? 0L : cacheHitTokens,
                        cacheMissInputTokens == null ? 0L : cacheMissInputTokens,
                        outputTokens == null ? 0L : outputTokens,
                        cacheHitRate == null ? 0.0 : cacheHitRate,
                        estimatedCost == null ? 0.0 : estimatedCost,
                        currency,
                        cacheSource,
                        cacheScopeId,
                        stablePrefixHash,
                        providerUsageAvailable != null && providerUsageAvailable,
                        cachePolicyId,
                        volatileSuffixStrategy,
                        promptPartitionPolicyId,
                        promptPartitionFingerprint,
                        stablePartitionCount == null ? 0 : stablePartitionCount,
                        volatilePartitionCount == null ? 0 : volatilePartitionCount
                ),
                parsedContextTypes,
                createdAt == null ? Instant.EPOCH : createdAt
        );
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
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

    public String getAnswerSummary() {
        return answerSummary;
    }

    public void setAnswerSummary(String answerSummary) {
        this.answerSummary = answerSummary;
    }

    public String getActorUserId() {
        return actorUserId;
    }

    public void setActorUserId(String actorUserId) {
        this.actorUserId = actorUserId;
    }

    public String getAgentRunId() {
        return agentRunId;
    }

    public void setAgentRunId(String agentRunId) {
        this.agentRunId = agentRunId;
    }

    public String getUsageRoleSessionId() {
        return usageRoleSessionId;
    }

    public void setUsageRoleSessionId(String usageRoleSessionId) {
        this.usageRoleSessionId = usageRoleSessionId;
    }

    public String getUsageModel() {
        return usageModel;
    }

    public void setUsageModel(String usageModel) {
        this.usageModel = usageModel;
    }

    public Long getCacheHitTokens() {
        return cacheHitTokens;
    }

    public void setCacheHitTokens(Long cacheHitTokens) {
        this.cacheHitTokens = cacheHitTokens;
    }

    public Long getCacheMissInputTokens() {
        return cacheMissInputTokens;
    }

    public void setCacheMissInputTokens(Long cacheMissInputTokens) {
        this.cacheMissInputTokens = cacheMissInputTokens;
    }

    public Long getOutputTokens() {
        return outputTokens;
    }

    public void setOutputTokens(Long outputTokens) {
        this.outputTokens = outputTokens;
    }

    public Double getCacheHitRate() {
        return cacheHitRate;
    }

    public void setCacheHitRate(Double cacheHitRate) {
        this.cacheHitRate = cacheHitRate;
    }

    public Double getEstimatedCost() {
        return estimatedCost;
    }

    public void setEstimatedCost(Double estimatedCost) {
        this.estimatedCost = estimatedCost;
    }

    public String getCurrency() {
        return currency;
    }

    public void setCurrency(String currency) {
        this.currency = currency;
    }

    public String getCacheSource() {
        return cacheSource;
    }

    public void setCacheSource(String cacheSource) {
        this.cacheSource = cacheSource;
    }

    public String getCacheScopeId() {
        return cacheScopeId;
    }

    public void setCacheScopeId(String cacheScopeId) {
        this.cacheScopeId = cacheScopeId;
    }

    public String getStablePrefixHash() {
        return stablePrefixHash;
    }

    public void setStablePrefixHash(String stablePrefixHash) {
        this.stablePrefixHash = stablePrefixHash;
    }

    public Boolean getProviderUsageAvailable() {
        return providerUsageAvailable;
    }

    public void setProviderUsageAvailable(Boolean providerUsageAvailable) {
        this.providerUsageAvailable = providerUsageAvailable;
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

    public String getPromptPartitionPolicyId() {
        return promptPartitionPolicyId;
    }

    public void setPromptPartitionPolicyId(String promptPartitionPolicyId) {
        this.promptPartitionPolicyId = promptPartitionPolicyId;
    }

    public String getPromptPartitionFingerprint() {
        return promptPartitionFingerprint;
    }

    public void setPromptPartitionFingerprint(String promptPartitionFingerprint) {
        this.promptPartitionFingerprint = promptPartitionFingerprint;
    }

    public Integer getStablePartitionCount() {
        return stablePartitionCount;
    }

    public void setStablePartitionCount(Integer stablePartitionCount) {
        this.stablePartitionCount = stablePartitionCount;
    }

    public Integer getVolatilePartitionCount() {
        return volatilePartitionCount;
    }

    public void setVolatilePartitionCount(Integer volatilePartitionCount) {
        this.volatilePartitionCount = volatilePartitionCount;
    }

    public String getContextTypes() {
        return contextTypes;
    }

    public void setContextTypes(String contextTypes) {
        this.contextTypes = contextTypes;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }
}
