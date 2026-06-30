package com.matrixcode.modelgateway.domain;

import java.util.Optional;

public record ModelCompletionResult(
        String answer,
        Optional<ProviderTokenUsage> usage
) {
    public ModelCompletionResult {
        if (answer == null || answer.isBlank()) {
            throw new IllegalArgumentException("模型回答不能为空");
        }
        answer = answer.strip();
        usage = usage == null ? Optional.empty() : usage;
    }

    /**
     * 构建没有供应商真实用量的模型结果。
     *
     * <p>本地确定性模型和未返回 prompt cache 字段的兼容供应商使用该工厂方法，服务层会继续
     * 走稳定前缀估算兜底。</p>
     */
    public static ModelCompletionResult withoutProviderUsage(String answer) {
        return new ModelCompletionResult(answer, Optional.empty());
    }

    /**
     * 构建带供应商真实 token 用量的模型结果。
     *
     * <p>DeepSeek 等供应商返回 `prompt_cache_hit_tokens` 和
     * `prompt_cache_miss_tokens` 时使用该工厂方法，避免再用本地估算覆盖真实账单口径。</p>
     */
    public static ModelCompletionResult withProviderUsage(String answer, ProviderTokenUsage usage) {
        return new ModelCompletionResult(answer, Optional.of(usage));
    }
}
