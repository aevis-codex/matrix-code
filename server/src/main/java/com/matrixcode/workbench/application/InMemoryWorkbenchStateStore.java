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

import java.util.List;
import java.util.Map;

public class InMemoryWorkbenchStateStore implements WorkbenchStateStore {

    private WorkbenchStateSnapshot snapshot = WorkbenchStateSnapshot.empty();

    @Override
    public synchronized WorkbenchStateSnapshot load() {
        return snapshot;
    }

    @Override
    public synchronized void saveDocuments(List<DocumentVersion> documents) {
        snapshot = newSnapshot(documents, snapshot.bugs(), snapshot.deploymentTargets(), snapshot.deploymentOperations(),
                snapshot.deploymentHealthChecks(), snapshot.composeEnvironments(), snapshot.composeOperations(),
                snapshot.modelBindings(), snapshot.modelRequests(), snapshot.projectEvents(), snapshot.fileOperations(),
                snapshot.gitDiffSummaries(), snapshot.workflowItems(), snapshot.workflowEvents(),
                snapshot.roleAgentConfigs(), snapshot.acceptances());
    }

    @Override
    public synchronized void saveBugs(List<ProjectBug> bugs) {
        snapshot = newSnapshot(snapshot.documents(), bugs, snapshot.deploymentTargets(), snapshot.deploymentOperations(),
                snapshot.deploymentHealthChecks(), snapshot.composeEnvironments(), snapshot.composeOperations(),
                snapshot.modelBindings(), snapshot.modelRequests(), snapshot.projectEvents(), snapshot.fileOperations(),
                snapshot.gitDiffSummaries(), snapshot.workflowItems(), snapshot.workflowEvents(),
                snapshot.roleAgentConfigs(), snapshot.acceptances());
    }

    @Override
    public synchronized void saveDeploymentTargets(List<DeploymentTarget> targets) {
        snapshot = newSnapshot(snapshot.documents(), snapshot.bugs(), targets, snapshot.deploymentOperations(),
                snapshot.deploymentHealthChecks(), snapshot.composeEnvironments(), snapshot.composeOperations(),
                snapshot.modelBindings(), snapshot.modelRequests(), snapshot.projectEvents(), snapshot.fileOperations(),
                snapshot.gitDiffSummaries(), snapshot.workflowItems(), snapshot.workflowEvents(),
                snapshot.roleAgentConfigs(), snapshot.acceptances());
    }

    @Override
    public synchronized void saveDeploymentOperations(Map<String, List<DeploymentOperationRecord>> operations) {
        snapshot = newSnapshot(snapshot.documents(), snapshot.bugs(), snapshot.deploymentTargets(), operations,
                snapshot.deploymentHealthChecks(), snapshot.composeEnvironments(), snapshot.composeOperations(),
                snapshot.modelBindings(), snapshot.modelRequests(), snapshot.projectEvents(), snapshot.fileOperations(),
                snapshot.gitDiffSummaries(), snapshot.workflowItems(), snapshot.workflowEvents(),
                snapshot.roleAgentConfigs(), snapshot.acceptances());
    }

    @Override
    public synchronized void saveDeploymentHealthChecks(Map<String, List<DeploymentHealthCheck>> checks) {
        snapshot = newSnapshot(snapshot.documents(), snapshot.bugs(), snapshot.deploymentTargets(),
                snapshot.deploymentOperations(), checks, snapshot.composeEnvironments(), snapshot.composeOperations(),
                snapshot.modelBindings(), snapshot.modelRequests(), snapshot.projectEvents(), snapshot.fileOperations(),
                snapshot.gitDiffSummaries(), snapshot.workflowItems(), snapshot.workflowEvents(),
                snapshot.roleAgentConfigs(), snapshot.acceptances());
    }

    @Override
    public synchronized void saveComposeEnvironments(List<ComposeEnvironment> environments) {
        snapshot = newSnapshot(snapshot.documents(), snapshot.bugs(), snapshot.deploymentTargets(),
                snapshot.deploymentOperations(), snapshot.deploymentHealthChecks(), environments,
                snapshot.composeOperations(), snapshot.modelBindings(), snapshot.modelRequests(), snapshot.projectEvents(),
                snapshot.fileOperations(), snapshot.gitDiffSummaries(), snapshot.workflowItems(), snapshot.workflowEvents(),
                snapshot.roleAgentConfigs(), snapshot.acceptances());
    }

    @Override
    public synchronized void saveComposeOperations(Map<String, List<ComposeOperationRecord>> operations) {
        snapshot = newSnapshot(snapshot.documents(), snapshot.bugs(), snapshot.deploymentTargets(),
                snapshot.deploymentOperations(), snapshot.deploymentHealthChecks(), snapshot.composeEnvironments(),
                operations, snapshot.modelBindings(), snapshot.modelRequests(), snapshot.projectEvents(),
                snapshot.fileOperations(), snapshot.gitDiffSummaries(), snapshot.workflowItems(), snapshot.workflowEvents(),
                snapshot.roleAgentConfigs(), snapshot.acceptances());
    }

    @Override
    public synchronized void saveModelBindings(List<RoleModelBinding> bindings) {
        snapshot = newSnapshot(snapshot.documents(), snapshot.bugs(), snapshot.deploymentTargets(),
                snapshot.deploymentOperations(), snapshot.deploymentHealthChecks(), snapshot.composeEnvironments(),
                snapshot.composeOperations(), bindings, snapshot.modelRequests(), snapshot.projectEvents(),
                snapshot.fileOperations(), snapshot.gitDiffSummaries(), snapshot.workflowItems(), snapshot.workflowEvents(),
                snapshot.roleAgentConfigs(), snapshot.acceptances());
    }

    @Override
    public synchronized void saveModelRequests(Map<String, List<ModelRequestRecord>> requests) {
        snapshot = newSnapshot(snapshot.documents(), snapshot.bugs(), snapshot.deploymentTargets(),
                snapshot.deploymentOperations(), snapshot.deploymentHealthChecks(), snapshot.composeEnvironments(),
                snapshot.composeOperations(), snapshot.modelBindings(), requests, snapshot.projectEvents(),
                snapshot.fileOperations(), snapshot.gitDiffSummaries(), snapshot.workflowItems(), snapshot.workflowEvents(),
                snapshot.roleAgentConfigs(), snapshot.acceptances());
    }

    @Override
    public synchronized void saveProjectEvents(Map<String, List<ProjectEvent>> events) {
        snapshot = newSnapshot(snapshot.documents(), snapshot.bugs(), snapshot.deploymentTargets(),
                snapshot.deploymentOperations(), snapshot.deploymentHealthChecks(), snapshot.composeEnvironments(),
                snapshot.composeOperations(), snapshot.modelBindings(), snapshot.modelRequests(), events,
                snapshot.fileOperations(), snapshot.gitDiffSummaries(), snapshot.workflowItems(), snapshot.workflowEvents(),
                snapshot.roleAgentConfigs(), snapshot.acceptances());
    }

    @Override
    public synchronized void saveFileOperations(Map<String, List<FileOperationRecord>> operations) {
        snapshot = newSnapshot(snapshot.documents(), snapshot.bugs(), snapshot.deploymentTargets(),
                snapshot.deploymentOperations(), snapshot.deploymentHealthChecks(), snapshot.composeEnvironments(),
                snapshot.composeOperations(), snapshot.modelBindings(), snapshot.modelRequests(), snapshot.projectEvents(),
                operations, snapshot.gitDiffSummaries(), snapshot.workflowItems(), snapshot.workflowEvents(),
                snapshot.roleAgentConfigs(), snapshot.acceptances());
    }

    @Override
    public synchronized void saveGitDiffSummaries(Map<String, GitDiffSummary> summaries) {
        snapshot = newSnapshot(snapshot.documents(), snapshot.bugs(), snapshot.deploymentTargets(),
                snapshot.deploymentOperations(), snapshot.deploymentHealthChecks(), snapshot.composeEnvironments(),
                snapshot.composeOperations(), snapshot.modelBindings(), snapshot.modelRequests(), snapshot.projectEvents(),
                snapshot.fileOperations(), summaries, snapshot.workflowItems(), snapshot.workflowEvents(),
                snapshot.roleAgentConfigs(), snapshot.acceptances());
    }

    @Override
    public synchronized void saveWorkflowItems(List<WorkflowItem> items) {
        snapshot = newSnapshot(snapshot.documents(), snapshot.bugs(), snapshot.deploymentTargets(),
                snapshot.deploymentOperations(), snapshot.deploymentHealthChecks(), snapshot.composeEnvironments(),
                snapshot.composeOperations(), snapshot.modelBindings(), snapshot.modelRequests(), snapshot.projectEvents(),
                snapshot.fileOperations(), snapshot.gitDiffSummaries(), items, snapshot.workflowEvents(),
                snapshot.roleAgentConfigs(), snapshot.acceptances());
    }

    @Override
    public synchronized void saveWorkflowEvents(Map<String, List<WorkflowEvent>> events) {
        snapshot = newSnapshot(snapshot.documents(), snapshot.bugs(), snapshot.deploymentTargets(),
                snapshot.deploymentOperations(), snapshot.deploymentHealthChecks(), snapshot.composeEnvironments(),
                snapshot.composeOperations(), snapshot.modelBindings(), snapshot.modelRequests(), snapshot.projectEvents(),
                snapshot.fileOperations(), snapshot.gitDiffSummaries(), snapshot.workflowItems(), events,
                snapshot.roleAgentConfigs(), snapshot.acceptances());
    }

    @Override
    public synchronized void saveRoleAgentConfigs(List<RoleAgentConfig> configs) {
        snapshot = newSnapshot(snapshot.documents(), snapshot.bugs(), snapshot.deploymentTargets(),
                snapshot.deploymentOperations(), snapshot.deploymentHealthChecks(), snapshot.composeEnvironments(),
                snapshot.composeOperations(), snapshot.modelBindings(), snapshot.modelRequests(), snapshot.projectEvents(),
                snapshot.fileOperations(), snapshot.gitDiffSummaries(), snapshot.workflowItems(), snapshot.workflowEvents(),
                configs, snapshot.acceptances());
    }

    @Override
    public synchronized void saveAcceptances(Map<String, WorkbenchStateSnapshot.AcceptanceState> acceptances) {
        snapshot = newSnapshot(snapshot.documents(), snapshot.bugs(), snapshot.deploymentTargets(),
                snapshot.deploymentOperations(), snapshot.deploymentHealthChecks(), snapshot.composeEnvironments(),
                snapshot.composeOperations(), snapshot.modelBindings(), snapshot.modelRequests(), snapshot.projectEvents(),
                snapshot.fileOperations(), snapshot.gitDiffSummaries(), snapshot.workflowItems(), snapshot.workflowEvents(),
                snapshot.roleAgentConfigs(), acceptances);
    }

    private WorkbenchStateSnapshot newSnapshot(
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
            Map<String, WorkbenchStateSnapshot.AcceptanceState> acceptances
    ) {
        return new WorkbenchStateSnapshot(1, documents, bugs, deploymentTargets, deploymentOperations,
                deploymentHealthChecks, composeEnvironments, composeOperations, modelBindings, modelRequests,
                projectEvents, fileOperations, gitDiffSummaries, workflowItems, workflowEvents, roleAgentConfigs,
                acceptances);
    }
}
