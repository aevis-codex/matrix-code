package com.matrixcode.modelgateway.domain;

import java.util.List;

public record ModelGatewaySummary(
        List<RoleModelBinding> bindings,
        ModelGatewayMetrics metrics,
        List<ModelRequestRecord> recentRequests
) {
    public ModelGatewaySummary {
        bindings = bindings == null ? List.of() : List.copyOf(bindings);
        metrics = metrics == null ? ModelGatewayMetrics.empty() : metrics;
        recentRequests = recentRequests == null ? List.of() : List.copyOf(recentRequests);
    }
}
