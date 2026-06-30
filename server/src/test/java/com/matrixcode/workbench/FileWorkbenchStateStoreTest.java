package com.matrixcode.workbench;

import com.fasterxml.jackson.databind.json.JsonMapper;
import com.matrixcode.bug.domain.BugSeverity;
import com.matrixcode.bug.domain.BugStatus;
import com.matrixcode.bug.domain.ProjectBug;
import com.matrixcode.deployment.domain.ComposeEnvironment;
import com.matrixcode.deployment.domain.ComposeEnvironmentStatus;
import com.matrixcode.deployment.domain.ComposeOperationRecord;
import com.matrixcode.deployment.domain.ComposeOperationStatus;
import com.matrixcode.deployment.domain.ComposeOperationType;
import com.matrixcode.deployment.domain.DeploymentHealthCheck;
import com.matrixcode.deployment.domain.DeploymentHealthStatus;
import com.matrixcode.deployment.domain.DeploymentOperationRecord;
import com.matrixcode.deployment.domain.DeploymentOperationStatus;
import com.matrixcode.deployment.domain.DeploymentOperationType;
import com.matrixcode.deployment.domain.DeploymentTarget;
import com.matrixcode.deployment.domain.DeploymentTargetStatus;
import com.matrixcode.document.domain.DocumentState;
import com.matrixcode.document.domain.DocumentType;
import com.matrixcode.document.domain.DocumentVersion;
import com.matrixcode.localexecution.domain.FileOperationRecord;
import com.matrixcode.localexecution.domain.FileOperationType;
import com.matrixcode.localexecution.domain.GitDiffSummary;
import com.matrixcode.modelgateway.domain.ModelRequestRecord;
import com.matrixcode.modelgateway.domain.ModelRole;
import com.matrixcode.modelgateway.domain.RoleModelBinding;
import com.matrixcode.realtime.domain.ProjectEvent;
import com.matrixcode.usage.domain.UsageRecord;
import com.matrixcode.workbench.application.FileWorkbenchStateStore;
import com.matrixcode.workbench.application.WorkbenchStateSnapshot.AcceptanceState;
import com.matrixcode.workbench.application.WorkbenchStateStorageProperties;
import com.matrixcode.workflow.domain.WorkflowEvent;
import com.matrixcode.workflow.domain.WorkflowEventType;
import com.matrixcode.workflow.domain.WorkflowItem;
import com.matrixcode.workflow.domain.WorkflowState;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class FileWorkbenchStateStoreTest {

    @TempDir
    Path tempDir;

    @Test
    void 文件不存在时加载空快照() {
        var store = store(tempDir.resolve("missing/workbench-state.json"));

        var snapshot = store.load();

        assertThat(snapshot.version()).isEqualTo(1);
        assertThat(snapshot.documents()).isEmpty();
        assertThat(snapshot.bugs()).isEmpty();
        assertThat(snapshot.deploymentTargets()).isEmpty();
        assertThat(snapshot.deploymentOperations()).isEmpty();
        assertThat(snapshot.deploymentHealthChecks()).isEmpty();
        assertThat(snapshot.composeEnvironments()).isEmpty();
        assertThat(snapshot.composeOperations()).isEmpty();
        assertThat(snapshot.modelBindings()).isEmpty();
        assertThat(snapshot.modelRequests()).isEmpty();
        assertThat(snapshot.projectEvents()).isEmpty();
        assertThat(snapshot.fileOperations()).isEmpty();
        assertThat(snapshot.gitDiffSummaries()).isEmpty();
        assertThat(snapshot.workflowItems()).isEmpty();
        assertThat(snapshot.workflowEvents()).isEmpty();
        assertThat(snapshot.acceptances()).isEmpty();
    }

    @Test
    void 分区更新会保留其他分区并可重新加载() {
        var path = tempDir.resolve("state/workbench-state.json");
        var store = store(path);

        store.saveDocuments(List.of(document()));
        store.saveBugs(List.of(bug()));
        store.saveDeploymentTargets(List.of(target()));
        store.saveDeploymentOperations(Map.of("demo", List.of(deploymentOperation())));
        store.saveDeploymentHealthChecks(Map.of("demo", List.of(healthCheck())));
        store.saveComposeEnvironments(List.of(composeEnvironment()));
        store.saveComposeOperations(Map.of("demo", List.of(composeOperation())));
        store.saveModelBindings(List.of(binding()));
        store.saveModelRequests(Map.of("demo", List.of(modelRequest())));
        store.saveProjectEvents(Map.of("demo", List.of(projectEvent())));
        store.saveFileOperations(Map.of("demo", List.of(fileOperation())));
        store.saveGitDiffSummaries(Map.of("demo", gitDiff()));
        store.saveWorkflowItems(List.of(workflowItem()));
        store.saveWorkflowEvents(Map.of("item-1", List.of(workflowEvent())));
        store.saveAcceptances(Map.of("demo", acceptance()));

        var loaded = store(path).load();
        assertThat(Files.exists(path)).isTrue();
        assertThat(loaded.documents()).extracting(DocumentVersion::id).containsExactly("doc-1");
        assertThat(loaded.bugs()).extracting(ProjectBug::id).containsExactly("bug-1");
        assertThat(loaded.deploymentTargets()).extracting(DeploymentTarget::id).containsExactly("target-1");
        assertThat(loaded.deploymentOperations().get("demo")).extracting(DeploymentOperationRecord::id)
                .containsExactly("deploy-op-1");
        assertThat(loaded.deploymentHealthChecks().get("demo")).extracting(DeploymentHealthCheck::id)
                .containsExactly("health-1");
        assertThat(loaded.composeEnvironments()).extracting(ComposeEnvironment::id).containsExactly("compose-1");
        assertThat(loaded.composeOperations().get("demo")).extracting(ComposeOperationRecord::id)
                .containsExactly("compose-op-1");
        assertThat(loaded.modelBindings()).extracting(RoleModelBinding::model)
                .containsExactly("matrixcode-local-product");
        assertThat(loaded.modelRequests().get("demo")).extracting(ModelRequestRecord::requestId)
                .containsExactly("request-1");
        assertThat(loaded.projectEvents().get("demo")).extracting(ProjectEvent::id).containsExactly("event-1");
        assertThat(loaded.fileOperations().get("demo")).extracting(FileOperationRecord::id).containsExactly("file-op-1");
        assertThat(loaded.gitDiffSummaries().get("demo").changedFiles()).containsExactly("server/App.java");
        assertThat(loaded.workflowItems()).extracting(WorkflowItem::id).containsExactly("item-1");
        assertThat(loaded.workflowEvents().get("item-1")).extracting(WorkflowEvent::id).containsExactly("workflow-event-1");
        assertThat(loaded.acceptances().get("demo").documentId()).isEqualTo("doc-acceptance");
    }

    @Test
    void 文件损坏时加载空快照且保留原文件() throws Exception {
        var path = tempDir.resolve("state/workbench-state.json");
        Files.createDirectories(path.getParent());
        Files.writeString(path, "{broken");

        var loaded = store(path).load();

        assertThat(loaded.documents()).isEmpty();
        assertThat(Files.readString(path)).isEqualTo("{broken");
    }

    private FileWorkbenchStateStore store(Path path) {
        var properties = new WorkbenchStateStorageProperties();
        properties.setStoragePath(path);
        var mapper = JsonMapper.builder().findAndAddModules().build();
        return new FileWorkbenchStateStore(mapper, properties);
    }

    private DocumentVersion document() {
        return new DocumentVersion(
                "doc-1",
                "demo",
                DocumentType.PRD,
                "需求文档",
                "支付失败后允许重新支付",
                1,
                DocumentState.FROZEN,
                null,
                instant("2026-06-25T08:00:00Z"),
                "user-product",
                instant("2026-06-25T08:01:00Z")
        );
    }

    private ProjectBug bug() {
        return new ProjectBug(
                "bug-1",
                "demo",
                "支付按钮无响应",
                BugSeverity.HIGH,
                BugStatus.FIXING,
                "打开支付页并点击按钮",
                "进入支付确认页",
                "无响应",
                "测试",
                "开发",
                "已确认并修复中",
                instant("2026-06-25T08:02:00Z")
        );
    }

    private DeploymentTarget target() {
        return new DeploymentTarget(
                "target-1",
                "demo",
                "预发环境",
                "https://staging.example.com",
                "deploy@staging",
                "发布 main 分支",
                "https://staging.example.com/health",
                "回滚到上一版本",
                DeploymentTargetStatus.RECORDED,
                false,
                instant("2026-06-25T08:03:00Z")
        );
    }

    private DeploymentOperationRecord deploymentOperation() {
        return new DeploymentOperationRecord(
                "deploy-op-1",
                "demo",
                "target-1",
                "user-ops",
                DeploymentOperationType.DEPLOYMENT,
                DeploymentOperationStatus.SUCCEEDED,
                "发布成功",
                instant("2026-06-25T08:04:00Z")
        );
    }

    private DeploymentHealthCheck healthCheck() {
        return new DeploymentHealthCheck(
                "health-1",
                "demo",
                "target-1",
                "user-ops",
                DeploymentHealthStatus.HEALTHY,
                200,
                42,
                "健康",
                instant("2026-06-25T08:05:00Z")
        );
    }

    private ComposeEnvironment composeEnvironment() {
        return new ComposeEnvironment(
                "compose-1",
                "demo",
                "target-1",
                "workspace-1",
                "compose.yml",
                "matrixcode",
                "app",
                ComposeEnvironmentStatus.RUNNING,
                instant("2026-06-25T08:06:00Z"),
                instant("2026-06-25T08:07:00Z")
        );
    }

    private ComposeOperationRecord composeOperation() {
        return new ComposeOperationRecord(
                "compose-op-1",
                "demo",
                "compose-1",
                "user-ops",
                ComposeOperationType.START,
                ComposeOperationStatus.SUCCEEDED,
                "启动成功",
                "Container app started",
                instant("2026-06-25T08:08:00Z")
        );
    }

    private RoleModelBinding binding() {
        return new RoleModelBinding(
                "demo",
                ModelRole.PRODUCT,
                "local-deterministic",
                "matrixcode-local-product",
                "CNY",
                0.0,
                0.0,
                0.0,
                32_000,
                "tools-v1"
        );
    }

    private ModelRequestRecord modelRequest() {
        return new ModelRequestRecord(
                "request-1",
                "demo",
                ModelRole.PRODUCT,
                "local-deterministic",
                "matrixcode-local-product",
                "已生成需求草稿",
                new UsageRecord("demo:PRODUCT", "matrixcode-local-product", 10, 20, 30, 0.33, 0.0, "CNY"),
                List.of("PROJECT_RULE"),
                instant("2026-06-25T08:09:00Z")
        );
    }

    private ProjectEvent projectEvent() {
        return new ProjectEvent(
                "event-1",
                "demo",
                "PRODUCT_DRAFT_CREATED",
                "产品生成了需求草稿",
                instant("2026-06-25T08:10:00Z")
        );
    }

    private FileOperationRecord fileOperation() {
        return new FileOperationRecord(
                "file-op-1",
                "demo",
                "workspace-1",
                FileOperationType.READ,
                "README.md",
                "SUCCESS",
                "读取 README",
                instant("2026-06-25T08:11:00Z")
        );
    }

    private GitDiffSummary gitDiff() {
        return new GitDiffSummary(
                "demo",
                "workspace-1",
                true,
                List.of("server/App.java"),
                "1 file changed",
                instant("2026-06-25T08:12:00Z")
        );
    }

    private WorkflowItem workflowItem() {
        return new WorkflowItem("item-1", "demo", "冻结需求", WorkflowState.REVIEW_PENDING);
    }

    private WorkflowEvent workflowEvent() {
        return new WorkflowEvent(
                "workflow-event-1",
                "item-1",
                WorkflowEventType.SUBMIT_REVIEW,
                WorkflowState.DRAFT,
                WorkflowState.REVIEW_PENDING,
                "user-product",
                instant("2026-06-25T08:13:00Z")
        );
    }

    private AcceptanceState acceptance() {
        return new AcceptanceState("doc-acceptance", false, "测试");
    }

    private Instant instant(String value) {
        return Instant.parse(value);
    }
}
