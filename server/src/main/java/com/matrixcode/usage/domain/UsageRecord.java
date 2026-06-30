package com.matrixcode.usage.domain;

public record UsageRecord(
        String roleSessionId,
        String model,
        long cacheHitTokens,
        long cacheMissInputTokens,
        long outputTokens,
        double cacheHitRate,
        double estimatedCost,
        String currency,
        String cacheSource,
        String cacheScopeId,
        String stablePrefixHash,
        boolean providerUsageAvailable,
        String cachePolicyId,
        String volatileSuffixStrategy,
        String promptPartitionPolicyId,
        String promptPartitionFingerprint,
        int stablePartitionCount,
        int volatilePartitionCount
) {
    public UsageRecord(
            String roleSessionId,
            String model,
            long cacheHitTokens,
            long cacheMissInputTokens,
            long outputTokens,
            double cacheHitRate,
            double estimatedCost,
            String currency
    ) {
        this(
                roleSessionId,
                model,
                cacheHitTokens,
                cacheMissInputTokens,
                outputTokens,
                cacheHitRate,
                estimatedCost,
                currency,
                "ESTIMATED",
                "",
                "",
                false,
                "",
                "",
                "",
                "",
                0,
                0
        );
    }

    public UsageRecord(
            String roleSessionId,
            String model,
            long cacheHitTokens,
            long cacheMissInputTokens,
            long outputTokens,
            double cacheHitRate,
            double estimatedCost,
            String currency,
            String cacheSource,
            String cacheScopeId,
            String stablePrefixHash,
            boolean providerUsageAvailable
    ) {
        this(
                roleSessionId,
                model,
                cacheHitTokens,
                cacheMissInputTokens,
                outputTokens,
                cacheHitRate,
                estimatedCost,
                currency,
                cacheSource,
                cacheScopeId,
                stablePrefixHash,
                providerUsageAvailable,
                "",
                "",
                "",
                "",
                0,
                0
        );
    }

    public UsageRecord(
            String roleSessionId,
            String model,
            long cacheHitTokens,
            long cacheMissInputTokens,
            long outputTokens,
            double cacheHitRate,
            double estimatedCost,
            String currency,
            String cacheSource,
            String cacheScopeId,
            String stablePrefixHash,
            boolean providerUsageAvailable,
            String cachePolicyId,
            String volatileSuffixStrategy
    ) {
        this(
                roleSessionId,
                model,
                cacheHitTokens,
                cacheMissInputTokens,
                outputTokens,
                cacheHitRate,
                estimatedCost,
                currency,
                cacheSource,
                cacheScopeId,
                stablePrefixHash,
                providerUsageAvailable,
                cachePolicyId,
                volatileSuffixStrategy,
                "",
                "",
                0,
                0
        );
    }

    public UsageRecord {
        roleSessionId = requiredText(roleSessionId, "角色会话 ID 不能为空");
        model = requiredText(model, "模型名称不能为空");
        if (cacheHitTokens < 0 || cacheMissInputTokens < 0 || outputTokens < 0) {
            throw new IllegalArgumentException("token 用量不能为负数");
        }
        if (cacheHitRate < 0.0 || cacheHitRate > 1.0) {
            throw new IllegalArgumentException("缓存命中率必须在 0 到 1 之间");
        }
        if (estimatedCost < 0.0) {
            throw new IllegalArgumentException("预估费用不能为负数");
        }
        currency = requiredText(currency, "币种不能为空");
        cacheSource = cacheSource == null || cacheSource.isBlank() ? "ESTIMATED" : cacheSource.trim().toUpperCase();
        if (!"PROVIDER".equals(cacheSource) && !"ESTIMATED".equals(cacheSource)) {
            throw new IllegalArgumentException("缓存来源只允许 PROVIDER 或 ESTIMATED");
        }
        cacheScopeId = optionalText(cacheScopeId);
        stablePrefixHash = optionalText(stablePrefixHash);
        cachePolicyId = optionalText(cachePolicyId);
        volatileSuffixStrategy = optionalText(volatileSuffixStrategy);
        promptPartitionPolicyId = optionalText(promptPartitionPolicyId);
        promptPartitionFingerprint = optionalText(promptPartitionFingerprint);
        if (stablePartitionCount < 0 || volatilePartitionCount < 0) {
            throw new IllegalArgumentException("Prompt 分区数量不能为负数");
        }
    }

    /**
     * 返回带缓存 trace 元数据的新用量记录。
     *
     * <p>用量数值仍由 `UsageCalculator` 统一计算；模型网关在确认供应商 usage 来源后，通过该方法
     * 补充可观测字段，避免费用计算和 trace 元数据在多个地方重复拼装。</p>
     */
    public UsageRecord withCacheTrace(
            String cacheSource,
            String cacheScopeId,
            String stablePrefixHash,
            boolean providerUsageAvailable
    ) {
        return new UsageRecord(
                roleSessionId,
                model,
                cacheHitTokens,
                cacheMissInputTokens,
                outputTokens,
                cacheHitRate,
                estimatedCost,
                currency,
                cacheSource,
                cacheScopeId,
                stablePrefixHash,
                providerUsageAvailable,
                cachePolicyId,
                volatileSuffixStrategy,
                promptPartitionPolicyId,
                promptPartitionFingerprint,
                stablePartitionCount,
                volatilePartitionCount
        );
    }

    /**
     * 返回带缓存 trace 和缓存策略元数据的新用量记录。
     *
     * <p>策略字段只记录低敏标识，用于前端展示和排障；该方法不接收也不保存完整 prompt、
     * 用户输入、向量召回正文、工具输出或任何供应商密钥，避免可观测性字段扩大数据暴露面。</p>
     */
    public UsageRecord withCacheTrace(
            String cacheSource,
            String cacheScopeId,
            String stablePrefixHash,
            boolean providerUsageAvailable,
            String cachePolicyId,
            String volatileSuffixStrategy
    ) {
        return new UsageRecord(
                roleSessionId,
                model,
                cacheHitTokens,
                cacheMissInputTokens,
                outputTokens,
                cacheHitRate,
                estimatedCost,
                currency,
                cacheSource,
                cacheScopeId,
                stablePrefixHash,
                providerUsageAvailable,
                cachePolicyId,
                volatileSuffixStrategy,
                promptPartitionPolicyId,
                promptPartitionFingerprint,
                stablePartitionCount,
                volatilePartitionCount
        );
    }

    /**
     * 返回带 Prompt 分区 trace 元数据的新用量记录。
     *
     * <p>分区 trace 只保存策略 ID、结构 fingerprint 和分区数量，用于验证稳定前缀是否保持工程边界；
     * 该方法不接收 prompt 正文、用户指令、向量召回正文、工具输出或密钥。</p>
     */
    public UsageRecord withPromptPartitionTrace(
            String promptPartitionPolicyId,
            String promptPartitionFingerprint,
            int stablePartitionCount,
            int volatilePartitionCount
    ) {
        return new UsageRecord(
                roleSessionId,
                model,
                cacheHitTokens,
                cacheMissInputTokens,
                outputTokens,
                cacheHitRate,
                estimatedCost,
                currency,
                cacheSource,
                cacheScopeId,
                stablePrefixHash,
                providerUsageAvailable,
                cachePolicyId,
                volatileSuffixStrategy,
                promptPartitionPolicyId,
                promptPartitionFingerprint,
                stablePartitionCount,
                volatilePartitionCount
        );
    }

    private static String requiredText(String value, String message) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(message);
        }
        return value.trim();
    }

    private static String optionalText(String value) {
        return value == null ? "" : value.trim();
    }
}
