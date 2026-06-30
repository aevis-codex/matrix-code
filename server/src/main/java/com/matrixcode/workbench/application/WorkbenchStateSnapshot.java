package com.matrixcode.workbench.application;

import com.matrixcode.bug.domain.ProjectBug;
import com.matrixcode.deployment.domain.ComposeEnvironment;
import com.matrixcode.deployment.domain.ComposeOperationRecord;
import com.matrixcode.deployment.domain.DeploymentHealthCheck;
import com.matrixcode.deployment.domain.DeploymentOperationRecord;
import com.matrixcode.deployment.domain.DeploymentTarget;
import com.matrixcode.document.domain.DocumentVersion;
import com.matrixcode.localexecution.domain.FileOperationRecord;
import com.matrixcode.localexecution.domain.GitDiffSummary;
import com.matrixcode.modelgateway.domain.ModelRequestRecord;
import com.matrixcode.modelgateway.domain.RoleModelBinding;
import com.matrixcode.realtime.domain.ProjectEvent;
import com.matrixcode.roleagent.domain.RoleAgentConfig;
import com.matrixcode.workflow.domain.WorkflowEvent;
import com.matrixcode.workflow.domain.WorkflowItem;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

public record WorkbenchStateSnapshot(
        int version,
        List<DocumentVersion> documents,
        List<ProjectBug> bugs,
        List<DeploymentTarget> deploymentTargets,
        Map<String, List<DeploymentOperationRecord>> deploymentOperations,
        Map<String, List<DeploymentHealthCheck>> deploymentHealthChecks,
        List<ComposeEnvironment> composeEnvironments,
        Map<String, List<ComposeOperationRecord>> composeOperations,
        List<RoleModelBinding> modelBindings,
        Map<String, List<ModelRequestRecord>> modelRequests,
        Map<String, List<ProjectEvent>> projectEvents,
        Map<String, List<FileOperationRecord>> fileOperations,
        Map<String, GitDiffSummary> gitDiffSummaries,
        List<WorkflowItem> workflowItems,
        Map<String, List<WorkflowEvent>> workflowEvents,
        List<RoleAgentConfig> roleAgentConfigs,
        Map<String, AcceptanceState> acceptances
) {
    public WorkbenchStateSnapshot {
        documents = copyList(documents);
        bugs = copyList(bugs);
        deploymentTargets = copyList(deploymentTargets);
        deploymentOperations = copyListMap(deploymentOperations);
        deploymentHealthChecks = copyListMap(deploymentHealthChecks);
        composeEnvironments = copyList(composeEnvironments);
        composeOperations = copyListMap(composeOperations);
        modelBindings = copyList(modelBindings);
        modelRequests = copyListMap(modelRequests);
        projectEvents = copyListMap(projectEvents);
        fileOperations = copyListMap(fileOperations);
        gitDiffSummaries = Map.copyOf(gitDiffSummaries == null ? Map.of() : gitDiffSummaries);
        workflowItems = copyList(workflowItems);
        workflowEvents = copyListMap(workflowEvents);
        roleAgentConfigs = copyList(roleAgentConfigs);
        acceptances = Map.copyOf(acceptances == null ? Map.of() : acceptances);
    }

    public static WorkbenchStateSnapshot empty() {
        return new WorkbenchStateSnapshot(
                1,
                List.of(),
                List.of(),
                List.of(),
                Map.of(),
                Map.of(),
                List.of(),
                Map.of(),
                List.of(),
                Map.of(),
                Map.of(),
                Map.of(),
                Map.of(),
                List.of(),
                Map.of(),
                List.of(),
                Map.of()
        );
    }

    private static <T> List<T> copyList(List<T> values) {
        return List.copyOf(values == null ? List.of() : values);
    }

    private static <T> Map<String, List<T>> copyListMap(Map<String, List<T>> values) {
        if (values == null || values.isEmpty()) {
            return Map.of();
        }
        var copy = new HashMap<String, List<T>>();
        values.forEach((key, value) -> copy.put(key, copyList(value)));
        return Map.copyOf(copy);
    }

    public record AcceptanceState(String documentId, boolean accepted, String returnToRole) {
        public AcceptanceState {
            documentId = documentId == null ? "" : documentId.trim();
            returnToRole = returnToRole == null || returnToRole.isBlank() ? "开发" : returnToRole.trim();
        }
    }
}
