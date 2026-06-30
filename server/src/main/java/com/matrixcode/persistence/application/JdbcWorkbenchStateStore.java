package com.matrixcode.persistence.application;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
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
import com.matrixcode.workbench.application.WorkbenchStateSnapshot;
import com.matrixcode.workbench.application.WorkbenchStateStore;
import com.matrixcode.workflow.domain.WorkflowEvent;
import com.matrixcode.workflow.domain.WorkflowItem;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;

@Service
@ConditionalOnProperty(prefix = "matrixcode.persistence", name = "mode", havingValue = "jdbc")
public class JdbcWorkbenchStateStore implements WorkbenchStateStore {

    static final String SLICE_KEY = "workbench-state";

    private final JdbcSnapshotRepository repository;
    private final ObjectMapper objectMapper;
    private final boolean legacySnapshotWritesEnabled;
    private WorkbenchStateSnapshot current;
    private boolean loaded;

    public JdbcWorkbenchStateStore(
            JdbcSnapshotRepository repository,
            ObjectMapper objectMapper,
            PersistenceModeProperties properties
    ) {
        this.repository = repository;
        this.objectMapper = objectMapper;
        this.legacySnapshotWritesEnabled = properties.getJdbc().isLegacySnapshotWritesEnabled();
    }

    @Override
    public synchronized WorkbenchStateSnapshot load() {
        ensureLoaded();
        return current;
    }

    @Override
    public synchronized void saveDocuments(List<DocumentVersion> documents) {
        ensureLoaded();
        current = snapshot(documents, current.bugs(), current.deploymentTargets(), current.deploymentOperations(),
                current.deploymentHealthChecks(), current.composeEnvironments(), current.composeOperations(),
                current.modelBindings(), current.modelRequests(), current.projectEvents(), current.fileOperations(),
                current.gitDiffSummaries(), current.workflowItems(), current.workflowEvents(), current.roleAgentConfigs(),
                current.acceptances());
        writeSnapshot();
    }

    @Override
    public synchronized void saveBugs(List<ProjectBug> bugs) {
        ensureLoaded();
        current = snapshot(current.documents(), bugs, current.deploymentTargets(), current.deploymentOperations(),
                current.deploymentHealthChecks(), current.composeEnvironments(), current.composeOperations(),
                current.modelBindings(), current.modelRequests(), current.projectEvents(), current.fileOperations(),
                current.gitDiffSummaries(), current.workflowItems(), current.workflowEvents(), current.roleAgentConfigs(),
                current.acceptances());
        writeSnapshot();
    }

    @Override
    public synchronized void saveDeploymentTargets(List<DeploymentTarget> targets) {
        ensureLoaded();
        current = snapshot(current.documents(), current.bugs(), targets, current.deploymentOperations(),
                current.deploymentHealthChecks(), current.composeEnvironments(), current.composeOperations(),
                current.modelBindings(), current.modelRequests(), current.projectEvents(), current.fileOperations(),
                current.gitDiffSummaries(), current.workflowItems(), current.workflowEvents(), current.roleAgentConfigs(),
                current.acceptances());
        writeSnapshot();
    }

    @Override
    public synchronized void saveDeploymentOperations(Map<String, List<DeploymentOperationRecord>> operations) {
        ensureLoaded();
        current = snapshot(current.documents(), current.bugs(), current.deploymentTargets(), operations,
                current.deploymentHealthChecks(), current.composeEnvironments(), current.composeOperations(),
                current.modelBindings(), current.modelRequests(), current.projectEvents(), current.fileOperations(),
                current.gitDiffSummaries(), current.workflowItems(), current.workflowEvents(), current.roleAgentConfigs(),
                current.acceptances());
        writeSnapshot();
    }

    @Override
    public synchronized void saveDeploymentHealthChecks(Map<String, List<DeploymentHealthCheck>> checks) {
        ensureLoaded();
        current = snapshot(current.documents(), current.bugs(), current.deploymentTargets(),
                current.deploymentOperations(), checks, current.composeEnvironments(), current.composeOperations(),
                current.modelBindings(), current.modelRequests(), current.projectEvents(), current.fileOperations(),
                current.gitDiffSummaries(), current.workflowItems(), current.workflowEvents(), current.roleAgentConfigs(),
                current.acceptances());
        writeSnapshot();
    }

    @Override
    public synchronized void saveComposeEnvironments(List<ComposeEnvironment> environments) {
        ensureLoaded();
        current = snapshot(current.documents(), current.bugs(), current.deploymentTargets(),
                current.deploymentOperations(), current.deploymentHealthChecks(), environments,
                current.composeOperations(), current.modelBindings(), current.modelRequests(), current.projectEvents(),
                current.fileOperations(), current.gitDiffSummaries(), current.workflowItems(), current.workflowEvents(),
                current.roleAgentConfigs(), current.acceptances());
        writeSnapshot();
    }

    @Override
    public synchronized void saveComposeOperations(Map<String, List<ComposeOperationRecord>> operations) {
        ensureLoaded();
        current = snapshot(current.documents(), current.bugs(), current.deploymentTargets(),
                current.deploymentOperations(), current.deploymentHealthChecks(), current.composeEnvironments(),
                operations, current.modelBindings(), current.modelRequests(), current.projectEvents(),
                current.fileOperations(), current.gitDiffSummaries(), current.workflowItems(), current.workflowEvents(),
                current.roleAgentConfigs(), current.acceptances());
        writeSnapshot();
    }

    @Override
    public synchronized void saveModelBindings(List<RoleModelBinding> bindings) {
        ensureLoaded();
        current = snapshot(current.documents(), current.bugs(), current.deploymentTargets(),
                current.deploymentOperations(), current.deploymentHealthChecks(), current.composeEnvironments(),
                current.composeOperations(), bindings, current.modelRequests(), current.projectEvents(),
                current.fileOperations(), current.gitDiffSummaries(), current.workflowItems(), current.workflowEvents(),
                current.roleAgentConfigs(), current.acceptances());
        writeSnapshot();
    }

    @Override
    public synchronized void saveModelRequests(Map<String, List<ModelRequestRecord>> requests) {
        ensureLoaded();
        current = snapshot(current.documents(), current.bugs(), current.deploymentTargets(),
                current.deploymentOperations(), current.deploymentHealthChecks(), current.composeEnvironments(),
                current.composeOperations(), current.modelBindings(), requests, current.projectEvents(),
                current.fileOperations(), current.gitDiffSummaries(), current.workflowItems(), current.workflowEvents(),
                current.roleAgentConfigs(), current.acceptances());
        writeSnapshot();
    }

    @Override
    public synchronized void saveProjectEvents(Map<String, List<ProjectEvent>> events) {
        ensureLoaded();
        current = snapshot(current.documents(), current.bugs(), current.deploymentTargets(),
                current.deploymentOperations(), current.deploymentHealthChecks(), current.composeEnvironments(),
                current.composeOperations(), current.modelBindings(), current.modelRequests(), events,
                current.fileOperations(), current.gitDiffSummaries(), current.workflowItems(), current.workflowEvents(),
                current.roleAgentConfigs(), current.acceptances());
        writeSnapshot();
    }

    @Override
    public synchronized void saveFileOperations(Map<String, List<FileOperationRecord>> operations) {
        ensureLoaded();
        current = snapshot(current.documents(), current.bugs(), current.deploymentTargets(),
                current.deploymentOperations(), current.deploymentHealthChecks(), current.composeEnvironments(),
                current.composeOperations(), current.modelBindings(), current.modelRequests(), current.projectEvents(),
                operations, current.gitDiffSummaries(), current.workflowItems(), current.workflowEvents(),
                current.roleAgentConfigs(), current.acceptances());
        writeSnapshot();
    }

    @Override
    public synchronized void saveGitDiffSummaries(Map<String, GitDiffSummary> summaries) {
        ensureLoaded();
        current = snapshot(current.documents(), current.bugs(), current.deploymentTargets(),
                current.deploymentOperations(), current.deploymentHealthChecks(), current.composeEnvironments(),
                current.composeOperations(), current.modelBindings(), current.modelRequests(), current.projectEvents(),
                current.fileOperations(), summaries, current.workflowItems(), current.workflowEvents(),
                current.roleAgentConfigs(), current.acceptances());
        writeSnapshot();
    }

    @Override
    public synchronized void saveWorkflowItems(List<WorkflowItem> items) {
        ensureLoaded();
        current = snapshot(current.documents(), current.bugs(), current.deploymentTargets(),
                current.deploymentOperations(), current.deploymentHealthChecks(), current.composeEnvironments(),
                current.composeOperations(), current.modelBindings(), current.modelRequests(), current.projectEvents(),
                current.fileOperations(), current.gitDiffSummaries(), items, current.workflowEvents(),
                current.roleAgentConfigs(), current.acceptances());
        writeSnapshot();
    }

    @Override
    public synchronized void saveWorkflowEvents(Map<String, List<WorkflowEvent>> events) {
        ensureLoaded();
        current = snapshot(current.documents(), current.bugs(), current.deploymentTargets(),
                current.deploymentOperations(), current.deploymentHealthChecks(), current.composeEnvironments(),
                current.composeOperations(), current.modelBindings(), current.modelRequests(), current.projectEvents(),
                current.fileOperations(), current.gitDiffSummaries(), current.workflowItems(), events,
                current.roleAgentConfigs(), current.acceptances());
        writeSnapshot();
    }

    @Override
    public synchronized void saveRoleAgentConfigs(List<RoleAgentConfig> configs) {
        ensureLoaded();
        current = snapshot(current.documents(), current.bugs(), current.deploymentTargets(),
                current.deploymentOperations(), current.deploymentHealthChecks(), current.composeEnvironments(),
                current.composeOperations(), current.modelBindings(), current.modelRequests(), current.projectEvents(),
                current.fileOperations(), current.gitDiffSummaries(), current.workflowItems(), current.workflowEvents(),
                configs, current.acceptances());
        writeSnapshot();
    }

    @Override
    public synchronized void saveAcceptances(Map<String, WorkbenchStateSnapshot.AcceptanceState> acceptances) {
        ensureLoaded();
        current = snapshot(current.documents(), current.bugs(), current.deploymentTargets(),
                current.deploymentOperations(), current.deploymentHealthChecks(), current.composeEnvironments(),
                current.composeOperations(), current.modelBindings(), current.modelRequests(), current.projectEvents(),
                current.fileOperations(), current.gitDiffSummaries(), current.workflowItems(), current.workflowEvents(),
                current.roleAgentConfigs(), acceptances);
        writeSnapshot();
    }

    private void ensureLoaded() {
        if (loaded) {
            return;
        }
        current = readSnapshot();
        loaded = true;
    }

    private WorkbenchStateSnapshot readSnapshot() {
        return repository.load(SLICE_KEY)
                .filter(snapshot -> snapshot.version() == 1)
                .map(snapshot -> read(snapshot.payload()))
                .orElseGet(WorkbenchStateSnapshot::empty);
    }

    private WorkbenchStateSnapshot read(String payload) {
        try {
            var snapshot = objectMapper.readValue(payload, WorkbenchStateSnapshot.class);
            if (snapshot.version() != 1) {
                return WorkbenchStateSnapshot.empty();
            }
            return snapshot;
        } catch (JsonProcessingException | RuntimeException ignored) {
            return WorkbenchStateSnapshot.empty();
        }
    }

    private void writeSnapshot() {
        if (!legacySnapshotWritesEnabled) {
            // Typed repositories are the production write path.
            // This store only keeps old snapshots readable.
            return;
        }
        try {
            repository.save(SLICE_KEY, current.version(), objectMapper.writeValueAsString(current));
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("工作台 JDBC 快照序列化失败：" + exception.getMessage(), exception);
        }
    }

    private WorkbenchStateSnapshot snapshot(
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
