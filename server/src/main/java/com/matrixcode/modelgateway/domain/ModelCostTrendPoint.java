package com.matrixcode.modelgateway.domain;

import java.time.Instant;

/**
 * Agent 运行内单次模型请求的成本趋势点。
 *
 * <p>趋势点只包含 requestId、时间、缓存命中率、估算费用、币种和缓存来源，供运行中心绘制低敏成本走势。
 * 它不包含 prompt 正文、模型响应正文、向量召回正文、工具输出或任何密钥。</p>
 */
public record ModelCostTrendPoint(
        String requestId,
        Instant createdAt,
        double cacheHitRate,
        double estimatedCost,
        String currency,
        String cacheSource
) {
    public ModelCostTrendPoint {
        requestId = requireText(requestId, "模型请求编号不能为空");
        createdAt = createdAt == null ? Instant.EPOCH : createdAt;
        currency = currency == null || currency.isBlank() ? "CNY" : currency.trim();
        cacheSource = cacheSource == null || cacheSource.isBlank() ? "UNKNOWN" : cacheSource.trim();
        cacheHitRate = Math.max(0.0, Math.min(1.0, cacheHitRate));
        estimatedCost = Math.max(0.0, estimatedCost);
    }

    /**
     * 从模型请求记录生成低敏趋势点。
     *
     * <p>该转换只读取 usage 中的 token 成本摘要，不触碰模型回答正文或上下文正文。</p>
     */
    public static ModelCostTrendPoint from(ModelRequestRecord record) {
        return new ModelCostTrendPoint(
                record.requestId(),
                record.createdAt(),
                record.usage().cacheHitRate(),
                record.usage().estimatedCost(),
                record.usage().currency(),
                record.usage().cacheSource()
        );
    }

    private static String requireText(String value, String message) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(message);
        }
        return value.trim();
    }
}
