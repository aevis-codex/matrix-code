package com.matrixcode.workbench.application;

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
import com.matrixcode.workflow.domain.WorkflowEvent;
import com.matrixcode.workflow.domain.WorkflowItem;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;
import java.util.Map;

@Service
@ConditionalOnProperty(prefix = "matrixcode.persistence", name = "mode", havingValue = "file", matchIfMissing = true)
public class FileWorkbenchStateStore implements WorkbenchStateStore {

    private final ObjectMapper objectMapper;
    private final WorkbenchStateStorageProperties properties;
    private WorkbenchStateSnapshot current;

    public FileWorkbenchStateStore(ObjectMapper objectMapper, WorkbenchStateStorageProperties properties) {
        this.objectMapper = objectMapper;
        this.properties = properties;
        this.current = readSnapshot();
    }

    @Override
    public synchronized WorkbenchStateSnapshot load() {
        return current;
    }

    @Override
    public synchronized void saveDocuments(List<DocumentVersion> documents) {
        current = snapshot(documents, current.bugs(), current.deploymentTargets(), current.deploymentOperations(),
                current.deploymentHealthChecks(), current.composeEnvironments(), current.composeOperations(),
                current.modelBindings(), current.modelRequests(), current.projectEvents(), current.fileOperations(),
                current.gitDiffSummaries(), current.workflowItems(), current.workflowEvents(), current.roleAgentConfigs(),
                current.acceptances());
        writeSnapshot();
    }

    @Override
    public synchronized void saveBugs(List<ProjectBug> bugs) {
        current = snapshot(current.documents(), bugs, current.deploymentTargets(), current.deploymentOperations(),
                current.deploymentHealthChecks(), current.composeEnvironments(), current.composeOperations(),
                current.modelBindings(), current.modelRequests(), current.projectEvents(), current.fileOperations(),
                current.gitDiffSummaries(), current.workflowItems(), current.workflowEvents(), current.roleAgentConfigs(),
                current.acceptances());
        writeSnapshot();
    }

    @Override
    public synchronized void saveDeploymentTargets(List<DeploymentTarget> targets) {
        current = snapshot(current.documents(), current.bugs(), targets, current.deploymentOperations(),
                current.deploymentHealthChecks(), current.composeEnvironments(), current.composeOperations(),
                current.modelBindings(), current.modelRequests(), current.projectEvents(), current.fileOperations(),
                current.gitDiffSummaries(), current.workflowItems(), current.workflowEvents(), current.roleAgentConfigs(),
                current.acceptances());
        writeSnapshot();
    }

    @Override
    public synchronized void saveDeploymentOperations(Map<String, List<DeploymentOperationRecord>> operations) {
        current = snapshot(current.documents(), current.bugs(), current.deploymentTargets(), operations,
                current.deploymentHealthChecks(), current.composeEnvironments(), current.composeOperations(),
                current.modelBindings(), current.modelRequests(), current.projectEvents(), current.fileOperations(),
                current.gitDiffSummaries(), current.workflowItems(), current.workflowEvents(), current.roleAgentConfigs(),
                current.acceptances());
        writeSnapshot();
    }

    @Override
    public synchronized void saveDeploymentHealthChecks(Map<String, List<DeploymentHealthCheck>> checks) {
        current = snapshot(current.documents(), current.bugs(), current.deploymentTargets(),
                current.deploymentOperations(), checks, current.composeEnvironments(), current.composeOperations(),
                current.modelBindings(), current.modelRequests(), current.projectEvents(), current.fileOperations(),
                current.gitDiffSummaries(), current.workflowItems(), current.workflowEvents(), current.roleAgentConfigs(),
                current.acceptances());
        writeSnapshot();
    }

    @Override
    public synchronized void saveComposeEnvironments(List<ComposeEnvironment> environments) {
        current = snapshot(current.documents(), current.bugs(), current.deploymentTargets(),
                current.deploymentOperations(), current.deploymentHealthChecks(), environments,
                current.composeOperations(), current.modelBindings(), current.modelRequests(), current.projectEvents(),
                current.fileOperations(), current.gitDiffSummaries(), current.workflowItems(), current.workflowEvents(),
                current.roleAgentConfigs(), current.acceptances());
        writeSnapshot();
    }

    @Override
    public synchronized void saveComposeOperations(Map<String, List<ComposeOperationRecord>> operations) {
        current = snapshot(current.documents(), current.bugs(), current.deploymentTargets(),
                current.deploymentOperations(), current.deploymentHealthChecks(), current.composeEnvironments(),
                operations, current.modelBindings(), current.modelRequests(), current.projectEvents(),
                current.fileOperations(), current.gitDiffSummaries(), current.workflowItems(), current.workflowEvents(),
                current.roleAgentConfigs(), current.acceptances());
        writeSnapshot();
    }

    @Override
    public synchronized void saveModelBindings(List<RoleModelBinding> bindings) {
        current = snapshot(current.documents(), current.bugs(), current.deploymentTargets(),
                current.deploymentOperations(), current.deploymentHealthChecks(), current.composeEnvironments(),
                current.composeOperations(), bindings, current.modelRequests(), current.projectEvents(),
                current.fileOperations(), current.gitDiffSummaries(), current.workflowItems(), current.workflowEvents(),
                current.roleAgentConfigs(), current.acceptances());
        writeSnapshot();
    }

    @Override
    public synchronized void saveModelRequests(Map<String, List<ModelRequestRecord>> requests) {
        current = snapshot(current.documents(), current.bugs(), current.deploymentTargets(),
                current.deploymentOperations(), current.deploymentHealthChecks(), current.composeEnvironments(),
                current.composeOperations(), current.modelBindings(), requests, current.projectEvents(),
                current.fileOperations(), current.gitDiffSummaries(), current.workflowItems(), current.workflowEvents(),
                current.roleAgentConfigs(), current.acceptances());
        writeSnapshot();
    }

    @Override
    public synchronized void saveProjectEvents(Map<String, List<ProjectEvent>> events) {
        current = snapshot(current.documents(), current.bugs(), current.deploymentTargets(),
                current.deploymentOperations(), current.deploymentHealthChecks(), current.composeEnvironments(),
                current.composeOperations(), current.modelBindings(), current.modelRequests(), events,
                current.fileOperations(), current.gitDiffSummaries(), current.workflowItems(), current.workflowEvents(),
                current.roleAgentConfigs(), current.acceptances());
        writeSnapshot();
    }

    @Override
    public synchronized void saveFileOperations(Map<String, List<FileOperationRecord>> operations) {
        current = snapshot(current.documents(), current.bugs(), current.deploymentTargets(),
                current.deploymentOperations(), current.deploymentHealthChecks(), current.composeEnvironments(),
                current.composeOperations(), current.modelBindings(), current.modelRequests(), current.projectEvents(),
                operations, current.gitDiffSummaries(), current.workflowItems(), current.workflowEvents(),
                current.roleAgentConfigs(), current.acceptances());
        writeSnapshot();
    }

    @Override
    public synchronized void saveGitDiffSummaries(Map<String, GitDiffSummary> summaries) {
        current = snapshot(current.documents(), current.bugs(), current.deploymentTargets(),
                current.deploymentOperations(), current.deploymentHealthChecks(), current.composeEnvironments(),
                current.composeOperations(), current.modelBindings(), current.modelRequests(), current.projectEvents(),
                current.fileOperations(), summaries, current.workflowItems(), current.workflowEvents(),
                current.roleAgentConfigs(), current.acceptances());
        writeSnapshot();
    }

    @Override
    public synchronized void saveWorkflowItems(List<WorkflowItem> items) {
        current = snapshot(current.documents(), current.bugs(), current.deploymentTargets(),
                current.deploymentOperations(), current.deploymentHealthChecks(), current.composeEnvironments(),
                current.composeOperations(), current.modelBindings(), current.modelRequests(), current.projectEvents(),
                current.fileOperations(), current.gitDiffSummaries(), items, current.workflowEvents(),
                current.roleAgentConfigs(), current.acceptances());
        writeSnapshot();
    }

    @Override
    public synchronized void saveWorkflowEvents(Map<String, List<WorkflowEvent>> events) {
        current = snapshot(current.documents(), current.bugs(), current.deploymentTargets(),
                current.deploymentOperations(), current.deploymentHealthChecks(), current.composeEnvironments(),
                current.composeOperations(), current.modelBindings(), current.modelRequests(), current.projectEvents(),
                current.fileOperations(), current.gitDiffSummaries(), current.workflowItems(), events,
                current.roleAgentConfigs(), current.acceptances());
        writeSnapshot();
    }

    @Override
    public synchronized void saveRoleAgentConfigs(List<RoleAgentConfig> configs) {
        current = snapshot(current.documents(), current.bugs(), current.deploymentTargets(),
                current.deploymentOperations(), current.deploymentHealthChecks(), current.composeEnvironments(),
                current.composeOperations(), current.modelBindings(), current.modelRequests(), current.projectEvents(),
                current.fileOperations(), current.gitDiffSummaries(), current.workflowItems(), current.workflowEvents(),
                configs, current.acceptances());
        writeSnapshot();
    }

    @Override
    public synchronized void saveAcceptances(Map<String, WorkbenchStateSnapshot.AcceptanceState> acceptances) {
        current = snapshot(current.documents(), current.bugs(), current.deploymentTargets(),
                current.deploymentOperations(), current.deploymentHealthChecks(), current.composeEnvironments(),
                current.composeOperations(), current.modelBindings(), current.modelRequests(), current.projectEvents(),
                current.fileOperations(), current.gitDiffSummaries(), current.workflowItems(), current.workflowEvents(),
                current.roleAgentConfigs(), acceptances);
        writeSnapshot();
    }

    private WorkbenchStateSnapshot readSnapshot() {
        var path = storagePath();
        if (!Files.exists(path)) {
            return WorkbenchStateSnapshot.empty();
        }
        try {
            var snapshot = objectMapper.readValue(path.toFile(), WorkbenchStateSnapshot.class);
            if (snapshot.version() != 1) {
                return WorkbenchStateSnapshot.empty();
            }
            return snapshot;
        } catch (IOException | RuntimeException ignored) {
            return WorkbenchStateSnapshot.empty();
        }
    }

    private void writeSnapshot() {
        var path = storagePath();
        try {
            var parent = path.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            var tempFile = parent == null
                    ? Files.createTempFile("workbench-state-", ".tmp")
                    : Files.createTempFile(parent, "workbench-state-", ".tmp");
            objectMapper.writeValue(tempFile.toFile(), current);
            try {
                Files.move(tempFile, path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (IOException atomicMoveFailure) {
                Files.move(tempFile, path, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException exception) {
            throw new IllegalStateException("工作台状态存储写入失败：" + exception.getMessage(), exception);
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

    private Path storagePath() {
        return properties.getStoragePath().toAbsolutePath().normalize();
    }
}
