package com.matrixcode.modelgateway.domain;

/**
 * 项目级模型成本分组摘要。
 *
 * <p>分组只保留角色、供应商或模型等低敏维度和聚合 usage，不包含 prompt、响应正文、
 * 向量召回正文、工具输出或供应商密钥。</p>
 */
public record ModelCostBreakdown(
        String key,
        ModelGatewayMetrics metrics
) {
    public ModelCostBreakdown {
        key = requireText(key, "成本分组键不能为空");
        metrics = metrics == null ? ModelGatewayMetrics.empty() : metrics;
    }

    private static String requireText(String value, String message) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(message);
        }
        return value.trim();
    }
}
