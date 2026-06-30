package com.matrixcode.workbench.domain;

import com.matrixcode.bug.domain.ProjectBug;
import com.matrixcode.deployment.domain.ComposeEnvironment;
import com.matrixcode.deployment.domain.DeploymentTarget;
import com.matrixcode.localexecution.domain.LocalExecutionSummary;
import com.matrixcode.modelgateway.domain.ModelGatewaySummary;
import com.matrixcode.realtime.domain.ProjectEvent;
import com.matrixcode.runtime.domain.RuntimeNotification;

import java.util.List;

public record ProjectWorkbench(
        String projectId,
        String projectName,
        String currentStage,
        List<RoleSummary> roles,
        List<DocumentSummary> documents,
        List<ProjectBug> bugs,
        List<DeploymentTarget> deploymentTargets,
        List<DeploymentRuntimeSummary> deploymentRuntimeSummaries,
        List<ComposeEnvironment> composeEnvironments,
        List<ComposeRuntimeView> composeRuntimeViews,
        WorkbenchMetrics metrics,
        ModelGatewaySummary modelGateway,
        LocalExecutionSummary localExecution,
        List<ProjectEvent> events,
        List<RuntimeNotification> runtimeNotifications
) {
    public ProjectWorkbench {
        roles = List.copyOf(roles);
        documents = List.copyOf(documents);
        bugs = List.copyOf(bugs);
        deploymentTargets = List.copyOf(deploymentTargets);
        deploymentRuntimeSummaries = List.copyOf(deploymentRuntimeSummaries);
        composeEnvironments = List.copyOf(composeEnvironments);
        composeRuntimeViews = List.copyOf(composeRuntimeViews);
        if (modelGateway == null) {
            throw new IllegalArgumentException("模型网关摘要不能为空");
        }
        if (localExecution == null) {
            throw new IllegalArgumentException("本地执行代理摘要不能为空");
        }
        events = List.copyOf(events);
        runtimeNotifications = List.copyOf(runtimeNotifications == null ? List.of() : runtimeNotifications);
    }
}
