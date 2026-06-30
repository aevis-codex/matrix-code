package com.matrixcode.usage.application;

import com.matrixcode.usage.domain.ModelPrice;
import com.matrixcode.usage.domain.UsageRecord;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;

@Component
public class UsageCalculator {

    public UsageRecord calculate(
            String roleSessionId,
            ModelPrice price,
            long cacheHitTokens,
            long cacheMissInputTokens,
            long outputTokens
    ) {
        if (roleSessionId == null || roleSessionId.isBlank()) {
            throw new IllegalArgumentException("角色会话 ID 不能为空");
        }
        if (cacheHitTokens < 0 || cacheMissInputTokens < 0 || outputTokens < 0) {
            throw new IllegalArgumentException("词元数不能为负数");
        }

        long promptTokens = cacheHitTokens + cacheMissInputTokens;
        double cacheHitRate = promptTokens == 0 ? 0.0 : (double) cacheHitTokens / promptTokens;
        double estimatedCost = cacheHitTokens / 1_000_000.0 * price.cacheHitPerMillion()
                + cacheMissInputTokens / 1_000_000.0 * price.cacheMissInputPerMillion()
                + outputTokens / 1_000_000.0 * price.outputPerMillion();

        return new UsageRecord(
                roleSessionId,
                price.model(),
                cacheHitTokens,
                cacheMissInputTokens,
                outputTokens,
                round(cacheHitRate, 2),
                round(estimatedCost, 3),
                price.currency()
        );
    }

    private static double round(double value, int scale) {
        return BigDecimal.valueOf(value).setScale(scale, RoundingMode.HALF_UP).doubleValue();
    }
}
