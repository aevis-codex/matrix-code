package com.matrixcode.modelgateway.domain;

import java.time.Instant;
import java.util.List;

/**
 * 项目级长期模型成本趋势报告。
 *
 * <p>该报告面向管理者查看项目级 token 成本、缓存命中和供应商分布，所有字段均来自
 * 已脱敏的模型请求 usage 摘要，不包含 prompt、响应正文、向量召回正文、工具输出或密钥。</p>
 */
public record ModelCostTrendReport(
        String projectId,
        int days,
        String timeZone,
        Instant from,
        Instant to,
        ModelGatewayMetrics metrics,
        List<ModelCostTrendBucket> dailyTrend,
        List<ModelCostBreakdown> roleBreakdown,
        List<ModelCostBreakdown> providerBreakdown,
        List<ModelCostBreakdown> modelBreakdown
) {
    public ModelCostTrendReport {
        projectId = requireText(projectId, "项目编号不能为空");
        days = Math.max(1, days);
        timeZone = timeZone == null || timeZone.isBlank() ? "UTC" : timeZone.trim();
        from = from == null ? Instant.EPOCH : from;
        to = to == null ? Instant.EPOCH : to;
        metrics = metrics == null ? ModelGatewayMetrics.empty() : metrics;
        dailyTrend = dailyTrend == null ? List.of() : List.copyOf(dailyTrend);
        roleBreakdown = roleBreakdown == null ? List.of() : List.copyOf(roleBreakdown);
        providerBreakdown = providerBreakdown == null ? List.of() : List.copyOf(providerBreakdown);
        modelBreakdown = modelBreakdown == null ? List.of() : List.copyOf(modelBreakdown);
    }

    private static String requireText(String value, String message) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(message);
        }
        return value.trim();
    }
}
