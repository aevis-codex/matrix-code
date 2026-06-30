package com.matrixcode.api;

import java.util.List;

public record ProjectOverview(
        String projectId,
        String projectName,
        List<String> roles,
        List<String> stages,
        double cacheHitRate,
        long sessionTokens,
        String currentStage
) {

    public ProjectOverview {
        roles = List.copyOf(roles);
        stages = List.copyOf(stages);
    }
}
