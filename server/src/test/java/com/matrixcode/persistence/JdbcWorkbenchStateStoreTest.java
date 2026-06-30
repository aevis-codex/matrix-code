package com.matrixcode.persistence;

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
import com.matrixcode.persistence.application.JdbcSnapshotRepository;
import com.matrixcode.persistence.application.JdbcWorkbenchStateStore;
import com.matrixcode.persistence.application.PersistenceModeProperties;
import com.matrixcode.realtime.domain.ProjectEvent;
import com.matrixcode.usage.domain.UsageRecord;
import com.matrixcode.workbench.application.WorkbenchStateSnapshot;
import com.matrixcode.workflow.domain.WorkflowEvent;
import com.matrixcode.workflow.domain.WorkflowEventType;
import com.matrixcode.workflow.domain.WorkflowItem;
import com.matrixcode.workflow.domain.WorkflowState;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class JdbcWorkbenchStateStoreTest {

    @Test
    void 分区保存后重建存储可以恢复工作台状态() {
        var databaseName = "workbench_state_" + System.nanoTime();
        var writeProperties = properties(databaseName, true);
        var readProperties = properties(databaseName, false);
        var repository = new JdbcSnapshotRepository(writeProperties);
        var store = new JdbcWorkbenchStateStore(repository, JsonTestSupport.objectMapper(), writeProperties);

        store.saveDocuments(List.of(document()));
        store.saveBugs(List.of(bug()));
        store.saveDeploymentTargets(List.of(target()));
        store.saveDeploymentOperations(Map.of("demo", List.of(deploymentOperation())));
        store.saveDeploymentHealthChecks(Map.of("demo", List.of(healthCheck())));
        store.saveComposeEnvironments(List.of(composeEnvironment()));
        store.saveComposeOperations(Map.of("demo", List.of(composeOperation())));
        store.saveModelBindings(List.of(binding()));
        store.saveModelRequests(Map.of("demo", List.of(modelRequest())));
        store.saveProjectEvents(Map.of("demo", List.of(event())));
        store.saveFileOperations(Map.of("demo", List.of(fileOperation())));
        store.saveGitDiffSummaries(Map.of("demo", gitDiff()));
        store.saveWorkflowItems(List.of(workflowItem()));
        store.saveWorkflowEvents(Map.of("demo", List.of(workflowEvent())));
        store.saveAcceptances(Map.of("demo", new WorkbenchStateSnapshot.AcceptanceState("doc-1", false, "测试")));

        var restored = new JdbcWorkbenchStateStore(
                new JdbcSnapshotRepository(readProperties),
                JsonTestSupport.objectMapper(),
                readProperties
        ).load();

        assertThat(restored.documents()).extracting(DocumentVersion::id).containsExactly("doc-1");
        assertThat(restored.bugs()).extracting(ProjectBug::id).containsExactly("bug-1");
        assertThat(restored.deploymentTargets()).extracting(DeploymentTarget::id).containsExactly("target-1");
        assertThat(restored.deploymentOperations().get("demo")).extracting(DeploymentOperationRecord::id).containsExactly("deploy-1");
        assertThat(restored.deploymentHealthChecks().get("demo")).extracting(DeploymentHealthCheck::id).containsExactly("health-1");
        assertThat(restored.composeEnvironments()).extracting(ComposeEnvironment::id).containsExactly("compose-1");
        assertThat(restored.composeOperations().get("demo")).extracting(ComposeOperationRecord::id).containsExactly("compose-op-1");
        assertThat(restored.modelBindings()).extracting(RoleModelBinding::model).containsExactly("gpt-5");
        assertThat(restored.modelRequests().get("demo")).extracting(ModelRequestRecord::requestId).containsExactly("request-1");
        assertThat(restored.projectEvents().get("demo")).extracting(ProjectEvent::id).containsExactly("event-1");
        assertThat(restored.fileOperations().get("demo")).extracting(FileOperationRecord::id).containsExactly("file-1");
        assertThat(restored.gitDiffSummaries().get("demo").changedFiles()).containsExactly("README.md");
        assertThat(restored.workflowItems()).extracting(WorkflowItem::id).containsExactly("workflow-1");
        assertThat(restored.workflowEvents().get("demo")).extracting(WorkflowEvent::id).containsExactly("workflow-event-1");
        assertThat(restored.acceptances().get("demo").returnToRole()).isEqualTo("测试");
    }

    @Test
    void 默认关闭历史Workbench快照写入() {
        var properties = properties("workbench_state_read_only_" + System.nanoTime(), false);
        var repository = new JdbcSnapshotRepository(properties);
        var store = new JdbcWorkbenchStateStore(repository, JsonTestSupport.objectMapper(), properties);

        store.saveDocuments(List.of(document()));

        assertThat(repository.load("workbench-state")).isEmpty();
        assertThat(new JdbcWorkbenchStateStore(repository, JsonTestSupport.objectMapper(), properties).load().documents())
                .isEmpty();
    }

    @Test
    void 损坏Payload返回空快照() {
        var properties = properties("workbench_state_broken_" + System.nanoTime(), false);
        var repository = new JdbcSnapshotRepository(properties);
        repository.save("workbench-state", 1, "{broken");
        var store = new JdbcWorkbenchStateStore(repository, JsonTestSupport.objectMapper(), properties);

        var restored = store.load();

        assertThat(restored.documents()).isEmpty();
        assertThat(restored.bugs()).isEmpty();
        assertThat(restored.deploymentTargets()).isEmpty();
        assertThat(restored.modelRequests()).isEmpty();
    }

    private PersistenceModeProperties properties(String databaseName, boolean legacySnapshotWritesEnabled) {
        var properties = new PersistenceModeProperties();
        properties.setMode("jdbc");
        properties.getJdbc().setUrl("jdbc:h2:mem:" + databaseName
                + ";MODE=MySQL;DATABASE_TO_LOWER=TRUE;CASE_INSENSITIVE_IDENTIFIERS=TRUE;DB_CLOSE_DELAY=-1");
        properties.getJdbc().setUsername("sa");
        properties.getJdbc().setPassword("");
        properties.getJdbc().setLegacySnapshotWritesEnabled(legacySnapshotWritesEnabled);
        return properties;
    }

    private DocumentVersion document() {
        return new DocumentVersion(
                "doc-1",
                "demo",
                DocumentType.PRD,
                "PRD",
                "需求正文",
                1,
                DocumentState.FROZEN,
                "",
                Instant.parse("2026-06-25T00:00:00Z"),
                "user-product",
                Instant.parse("2026-06-25T00:00:01Z")
        );
    }

    private ProjectBug bug() {
        return new ProjectBug(
                "bug-1",
                "demo",
                "失败原因为空",
                BugSeverity.HIGH,
                BugStatus.CLOSED,
                "复现步骤",
                "显示错误原因",
                "为空",
                "测试",
                "开发",
                "已修复",
                Instant.parse("2026-06-25T00:00:02Z")
        );
    }

    private DeploymentTarget target() {
        return new DeploymentTarget(
                "target-1",
                "demo",
                "浏览器测试环境",
                "http://127.0.0.1:8080",
                "ssh://deploy@example",
                "记录部署",
                "http://127.0.0.1:8080/actuator/health",
                "回滚说明",
                DeploymentTargetStatus.RECORDED,
                false,
                Instant.parse("2026-06-25T00:00:03Z")
        );
    }

    private DeploymentOperationRecord deploymentOperation() {
        return new DeploymentOperationRecord(
                "deploy-1",
                "demo",
                "target-1",
                "user-ops",
                DeploymentOperationType.DEPLOYMENT,
                DeploymentOperationStatus.SUCCEEDED,
                "部署成功",
                Instant.parse("2026-06-25T00:00:04Z")
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
                18,
                "HTTP 200",
                Instant.parse("2026-06-25T00:00:05Z")
        );
    }

    private ComposeEnvironment composeEnvironment() {
        return new ComposeEnvironment(
                "compose-1",
                "demo",
                "target-1",
                "workspace-1",
                "compose.yml",
                "matrixcode-demo",
                "web",
                ComposeEnvironmentStatus.CONFIGURED,
                Instant.parse("2026-06-25T00:00:06Z"),
                Instant.parse("2026-06-25T00:00:07Z")
        );
    }

    private ComposeOperationRecord composeOperation() {
        return new ComposeOperationRecord(
                "compose-op-1",
                "demo",
                "compose-1",
                "user-ops",
                ComposeOperationType.VALIDATE,
                ComposeOperationStatus.SUCCEEDED,
                "配置有效",
                "services: web",
                Instant.parse("2026-06-25T00:00:08Z")
        );
    }

    private RoleModelBinding binding() {
        return new RoleModelBinding("demo", ModelRole.PRODUCT, "local", "gpt-5", "USD", 0.1, 1.0, 2.0, 16000, "v1");
    }

    private ModelRequestRecord modelRequest() {
        return new ModelRequestRecord(
                "request-1",
                "demo",
                ModelRole.PRODUCT,
                "local",
                "gpt-5",
                "已生成产品草稿",
                new UsageRecord("product:demo", "gpt-5", 10, 20, 30, 0.33, 0.01, "USD"),
                List.of("PROJECT_RULE"),
                Instant.parse("2026-06-25T00:00:09Z")
        );
    }

    private ProjectEvent event() {
        return new ProjectEvent("event-1", "demo", "DOCUMENT_FROZEN", "PRD 已冻结", Instant.parse("2026-06-25T00:00:10Z"));
    }

    private FileOperationRecord fileOperation() {
        return new FileOperationRecord(
                "file-1",
                "demo",
                "workspace-1",
                FileOperationType.READ,
                "README.md",
                "SUCCESS",
                "读取 README.md",
                Instant.parse("2026-06-25T00:00:11Z")
        );
    }

    private GitDiffSummary gitDiff() {
        return new GitDiffSummary(
                "demo",
                "workspace-1",
                true,
                List.of("README.md"),
                "1 file changed",
                Instant.parse("2026-06-25T00:00:12Z")
        );
    }

    private WorkflowItem workflowItem() {
        return new WorkflowItem("workflow-1", "demo", "冻结 PRD", WorkflowState.FROZEN);
    }

    private WorkflowEvent workflowEvent() {
        return new WorkflowEvent(
                "workflow-event-1",
                "workflow-1",
                WorkflowEventType.FREEZE,
                WorkflowState.REVIEW_PENDING,
                WorkflowState.FROZEN,
                "user-product",
                Instant.parse("2026-06-25T00:00:13Z")
        );
    }
}
