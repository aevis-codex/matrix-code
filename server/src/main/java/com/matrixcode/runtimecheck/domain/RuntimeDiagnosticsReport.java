package com.matrixcode.runtimecheck.domain;

import java.time.Instant;
import java.util.List;

public record RuntimeDiagnosticsReport(
        RuntimeCheckStatus status,
        Instant generatedAt,
        List<RuntimeCheckItem> items,
        List<String> nextActions
) {
    public RuntimeDiagnosticsReport {
        status = status == null ? RuntimeCheckStatus.WARN : status;
        generatedAt = generatedAt == null ? Instant.EPOCH : generatedAt;
        items = items == null ? List.of() : List.copyOf(items);
        nextActions = nextActions == null ? List.of() : List.copyOf(nextActions);
    }
}
