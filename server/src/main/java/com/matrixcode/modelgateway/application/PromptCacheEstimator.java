package com.matrixcode.modelgateway.application;

import com.matrixcode.modelgateway.domain.PromptContract;
import com.matrixcode.modelgateway.domain.PromptEstimate;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

@Component
public class PromptCacheEstimator {

    private final Set<String> seenStablePrefixes = ConcurrentHashMap.newKeySet();

    /**
     * 按稳定前缀哈希估算一次请求的缓存命中与未命中 token。
     *
     * <p>`cacheScopeId` 由项目、角色、供应商和模型组成；同一作用域内第二次出现相同稳定前缀时，
     * 视为可命中供应商 prefix cache。该估算只作为供应商未返回真实 usage 时的兜底。</p>
     */
    public PromptEstimate estimate(
            String cacheScopeId,
            PromptContract contract,
            List<String> contextTypes,
            String userInstruction,
            String outputText
    ) {
        if (cacheScopeId == null || cacheScopeId.isBlank()) {
            throw new IllegalArgumentException("缓存作用域 ID 不能为空");
        }
        if (contract == null) {
            throw new IllegalArgumentException("提示词契约不能为空");
        }
        var stableKey = cacheScopeId.trim() + ":" + contract.stablePrefixHash();
        var cacheHitTokens = seenStablePrefixes.add(stableKey) ? 0L : contract.estimatedStablePrefixTokens();
        var stableMissTokens = cacheHitTokens == 0L ? contract.estimatedStablePrefixTokens() : 0L;
        var dynamicInputTokens = estimateDynamicInputTokens(contextTypes, userInstruction);
        return new PromptEstimate(
                cacheHitTokens,
                stableMissTokens + dynamicInputTokens,
                estimateTokens(outputText)
        );
    }

    private static long estimateDynamicInputTokens(List<String> contextTypes, String userInstruction) {
        var contextSource = contextTypes == null ? "" : String.join("\n", contextTypes);
        return estimateTokens(contextSource) + estimateTokens(userInstruction);
    }

    private static long estimateTokens(String value) {
        if (value == null || value.isBlank()) {
            return 0L;
        }
        return Math.max(1L, value.trim().length() / 2L);
    }
}
