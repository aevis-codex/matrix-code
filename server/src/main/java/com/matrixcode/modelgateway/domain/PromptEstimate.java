package com.matrixcode.modelgateway.domain;

public record PromptEstimate(
        long cacheHitTokens,
        long cacheMissInputTokens,
        long outputTokens
) {
    public PromptEstimate {
        if (cacheHitTokens < 0 || cacheMissInputTokens < 0 || outputTokens < 0) {
            throw new IllegalArgumentException("词元数不能为负数");
        }
    }
}
