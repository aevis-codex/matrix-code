package com.matrixcode.modelgateway.domain;

public record ProviderTokenUsage(
        long cacheHitTokens,
        long cacheMissInputTokens,
        long outputTokens
) {
    public ProviderTokenUsage {
        if (cacheHitTokens < 0 || cacheMissInputTokens < 0 || outputTokens < 0) {
            throw new IllegalArgumentException("供应商 token 用量不能为负数");
        }
    }

    /**
     * 标识该记录来自供应商真实 prompt cache 字段，而不是本地估算。
     *
     * <p>`ProviderTokenUsage` 只会在响应中出现缓存命中或未命中字段时创建，因此返回
     * `true`。服务层通过该方法表达“真实字段优先”的业务意图。</p>
     */
    public boolean hasPromptCacheTokens() {
        return true;
    }
}
