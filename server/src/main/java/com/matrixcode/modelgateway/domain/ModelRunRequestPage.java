package com.matrixcode.modelgateway.domain;

import java.util.List;

/**
 * 某次 Agent 运行关联模型请求的分页查询结果。
 *
 * <p>该对象用于运行中心按运行复盘模型成本、缓存命中和请求明细。返回内容均为低敏摘要，
 * 不包含 prompt 正文、模型响应正文、向量召回正文、工具输出或密钥。</p>
 */
public record ModelRunRequestPage(
        String projectId,
        String agentRunId,
        int page,
        int size,
        long total,
        ModelGatewayMetrics metrics,
        List<ModelCostTrendPoint> trend,
        List<ModelRequestRecord> requests
) {
    public ModelRunRequestPage {
        projectId = requireText(projectId, "项目编号不能为空");
        agentRunId = requireText(agentRunId, "Agent 运行编号不能为空");
        page = Math.max(0, page);
        size = Math.max(1, size);
        total = Math.max(0L, total);
        metrics = metrics == null ? ModelGatewayMetrics.empty() : metrics;
        trend = trend == null ? List.of() : List.copyOf(trend);
        requests = requests == null ? List.of() : List.copyOf(requests);
    }

    private static String requireText(String value, String message) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(message);
        }
        return value.trim();
    }
}
