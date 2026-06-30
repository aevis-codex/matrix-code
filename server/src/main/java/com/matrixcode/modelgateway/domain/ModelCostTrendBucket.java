package com.matrixcode.modelgateway.domain;

/**
 * 项目级模型成本日粒度趋势点。
 *
 * <p>日期使用 UTC 自然日，便于多节点部署时保持聚合边界一致。</p>
 */
public record ModelCostTrendBucket(
        String date,
        ModelGatewayMetrics metrics
) {
    public ModelCostTrendBucket {
        date = requireText(date, "趋势日期不能为空");
        metrics = metrics == null ? ModelGatewayMetrics.empty() : metrics;
    }

    private static String requireText(String value, String message) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(message);
        }
        return value.trim();
    }
}
