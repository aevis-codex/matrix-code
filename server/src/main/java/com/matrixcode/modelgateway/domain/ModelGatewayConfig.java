package com.matrixcode.modelgateway.domain;

import java.util.List;

public record ModelGatewayConfig(
        List<ModelProvider> providers,
        List<RoleModelBinding> bindings,
        ModelGatewayMetrics metrics,
        List<ModelRequestRecord> recentRequests
) {
    public ModelGatewayConfig {
        providers = providers == null ? List.of() : List.copyOf(providers);
        bindings = bindings == null ? List.of() : List.copyOf(bindings);
        metrics = metrics == null ? ModelGatewayMetrics.empty() : metrics;
        recentRequests = recentRequests == null ? List.of() : List.copyOf(recentRequests);
    }
}
