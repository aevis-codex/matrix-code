package com.matrixcode.modelgateway.domain;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;

public record ModelGatewayMetrics(
        int requestCount,
        long cacheHitTokens,
        long cacheMissInputTokens,
        long outputTokens,
        double cacheHitRate,
        double estimatedCost,
        String currency,
        List<String> recentContextTypes
) {
    public ModelGatewayMetrics {
        if (requestCount < 0 || cacheHitTokens < 0 || cacheMissInputTokens < 0 || outputTokens < 0) {
            throw new IllegalArgumentException("模型指标不能为负数");
        }
        currency = currency == null || currency.isBlank() ? "CNY" : currency.trim();
        recentContextTypes = recentContextTypes == null ? List.of() : List.copyOf(recentContextTypes);
    }

    public static ModelGatewayMetrics empty() {
        return new ModelGatewayMetrics(0, 0, 0, 0, 0.0, 0.0, "CNY", List.of());
    }

    public static ModelGatewayMetrics from(List<ModelRequestRecord> records) {
        if (records == null || records.isEmpty()) {
            return empty();
        }
        long cacheHitTokens = 0;
        long cacheMissInputTokens = 0;
        long outputTokens = 0;
        double estimatedCost = 0.0;
        String currency = "CNY";
        for (var record : records) {
            cacheHitTokens += record.usage().cacheHitTokens();
            cacheMissInputTokens += record.usage().cacheMissInputTokens();
            outputTokens += record.usage().outputTokens();
            estimatedCost += record.usage().estimatedCost();
            currency = record.usage().currency();
        }
        var promptTokens = cacheHitTokens + cacheMissInputTokens;
        var cacheHitRate = promptTokens == 0 ? 0.0 : (double) cacheHitTokens / promptTokens;
        var recentContextTypes = records.getLast().contextTypes();
        return new ModelGatewayMetrics(
                records.size(),
                cacheHitTokens,
                cacheMissInputTokens,
                outputTokens,
                round(cacheHitRate, 2),
                round(estimatedCost, 3),
                currency,
                recentContextTypes
        );
    }

    private static double round(double value, int scale) {
        return BigDecimal.valueOf(value).setScale(scale, RoundingMode.HALF_UP).doubleValue();
    }
}
