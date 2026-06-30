package com.matrixcode.workbench.domain;

public record WorkbenchMetrics(
        double cacheHitRate,
        long sessionTokens,
        int eventCount,
        int documentCount,
        int openBugCount
) {
}
