package com.matrixcode.workbench;

import com.matrixcode.agent.application.LocalProductDraftAgent;
import com.matrixcode.approval.application.ApprovalPolicy;
import com.matrixcode.approval.application.AuditService;
import com.matrixcode.bug.application.BugService;
import com.matrixcode.context.application.ContextEngine;
import com.matrixcode.deployment.application.ComposeEnvironmentService;
import com.matrixcode.deployment.application.ComposeRuntimeClient;
import com.matrixcode.deployment.application.ComposeRuntimeRequest;
import com.matrixcode.deployment.application.ComposeRuntimeResult;
import com.matrixcode.deployment.application.DeploymentHealthProbe;
import com.matrixcode.deployment.application.DeploymentHealthService;
import com.matrixcode.deployment.application.DeploymentOperationService;
import com.matrixcode.deployment.application.DeploymentTargetService;
import com.matrixcode.deployment.domain.DeploymentHealthStatus;
import com.matrixcode.document.application.DocumentService;
import com.matrixcode.localexecution.application.LocalCommandService;
import com.matrixcode.localexecution.application.LocalExecutionSummaryService;
import com.matrixcode.localexecution.application.LocalFileService;
import com.matrixcode.localexecution.application.LocalGitDiffService;
import com.matrixcode.localexecution.application.LocalTaskQueueService;
import com.matrixcode.localexecution.application.LocalTaskStore;
import com.matrixcode.localexecution.application.PathGuard;
import com.matrixcode.localexecution.application.WorkspaceRegistry;
import com.matrixcode.modelgateway.application.DeterministicModelAdapter;
import com.matrixcode.modelgateway.application.ModelGatewayService;
import com.matrixcode.modelgateway.application.ModelProviderRegistry;
import com.matrixcode.modelgateway.application.PromptCacheEstimator;
import com.matrixcode.modelgateway.application.PromptContractBuilder;
import com.matrixcode.modelgateway.application.RoleModelBindingService;
import com.matrixcode.realtime.application.ProjectEventBus;
import com.matrixcode.runtime.application.RuntimeNotificationService;
import com.matrixcode.usage.application.UsageCalculator;
import com.matrixcode.workbench.application.InMemoryWorkbenchStateStore;
import com.matrixcode.workbench.application.WorkbenchProgressRepository;
import com.matrixcode.workbench.application.WorkbenchService;
import com.matrixcode.workbench.application.WorkbenchStateSnapshot;
import com.matrixcode.workflow.application.WorkflowService;
import com.matrixcode.workflow.domain.WorkflowEvent;
import com.matrixcode.workflow.domain.WorkflowEventType;
import com.matrixcode.workflow.domain.WorkflowItem;
import com.matrixcode.workflow.domain.WorkflowState;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class WorkbenchProgressRepositoryServiceTest {

    @Test
    void 工作流服务优先读取正式仓储并写回正式仓储() {
        var store = new InMemoryWorkbenchStateStore();
        var legacyItem = new WorkflowItem("legacy-item", "demo", "旧快照", WorkflowState.DRAFT);
        store.saveWorkflowItems(List.of(legacyItem));
        var repository = new RecordingWorkbenchProgressRepository();
        repository.workflowItems = List.of(new WorkflowItem("repo-item", "demo", "正式仓储", WorkflowState.REVIEW_PENDING));
        repository.workflowEvents = Map.of("repo-item", List.of(workflowEvent("repo-event", "repo-item")));

        var service = new WorkflowService(store, repository);

        var frozen = service.apply("repo-item", WorkflowEventType.FREEZE, "user-product");

        assertThat(frozen.state()).isEqualTo(WorkflowState.FROZEN);
        assertThat(repository.workflowItems)
                .extracting(WorkflowItem::id, WorkflowItem::state)
                .containsExactly(org.assertj.core.groups.Tuple.tuple("repo-item", WorkflowState.FROZEN));
        assertThat(repository.workflowEvents.get("repo-item")).hasSize(2);
        assertThat(store.load().workflowItems()).containsExactly(legacyItem);
    }

    @Test
    void 工作流服务在正式仓储为空时从旧快照回填() {
        var store = new InMemoryWorkbenchStateStore();
        var legacyItem = new WorkflowItem("legacy-item", "demo", "旧快照", WorkflowState.REVIEW_PENDING);
        var legacyEvent = workflowEvent("legacy-event", "legacy-item");
        store.saveWorkflowItems(List.of(legacyItem));
        store.saveWorkflowEvents(Map.of("legacy-item", List.of(legacyEvent)));
        var repository = new RecordingWorkbenchProgressRepository();

        var service = new WorkflowService(store, repository);

        assertThat(service.eventsOf("legacy-item")).containsExactly(legacyEvent);
        assertThat(repository.workflowItems).containsExactly(legacyItem);
        assertThat(repository.workflowEvents.get("legacy-item")).containsExactly(legacyEvent);
    }

    @Test
    void 工作台验收投影优先读取正式仓储并写回正式仓储() {
        var store = new InMemoryWorkbenchStateStore();
        store.saveAcceptances(Map.of("demo", new WorkbenchStateSnapshot.AcceptanceState("legacy-doc", true, "开发")));
        var repository = new RecordingWorkbenchProgressRepository();
        repository.acceptances = Map.of("demo", new WorkbenchStateSnapshot.AcceptanceState("repo-doc", false, "测试"));
        var harness = harness(store, repository);

        try {
            准备可验收项目(harness.service(), "demo");

            assertThat(harness.service().get("demo").currentStage()).isEqualTo("验收退回测试");

            harness.service().submitAcceptance("demo", false, "实现仍需调整", "开发");

            assertThat(repository.acceptances.get("demo").returnToRole()).isEqualTo("开发");
            assertThat(store.load().acceptances().get("demo").documentId()).isEqualTo("legacy-doc");
        } finally {
            harness.shutdown();
        }
    }

    @Test
    void 工作台验收投影在正式仓储为空时从旧快照回填() {
        var store = new InMemoryWorkbenchStateStore();
        var legacyAcceptance = new WorkbenchStateSnapshot.AcceptanceState("legacy-doc", false, "测试");
        store.saveAcceptances(Map.of("demo", legacyAcceptance));
        var repository = new RecordingWorkbenchProgressRepository();
        var harness = harness(store, repository);

        try {
            准备可验收项目(harness.service(), "demo");

            assertThat(harness.service().get("demo").currentStage()).isEqualTo("验收退回测试");
            assertThat(repository.acceptances.get("demo")).isEqualTo(legacyAcceptance);
        } finally {
            harness.shutdown();
        }
    }

    private Harness harness(InMemoryWorkbenchStateStore store, WorkbenchProgressRepository repository) {
        var events = new ProjectEventBus();
        var productDraftAgent = new LocalProductDraftAgent();
        var deploymentTargets = new DeploymentTargetService();
        var deploymentHealth = new DeploymentHealthService(
                deploymentTargets,
                uri -> new DeploymentHealthProbe(DeploymentHealthStatus.HEALTHY, 200, 12, "HTTP 200")
        );
        var deploymentOperations = new DeploymentOperationService(deploymentTargets);
        var providers = new ModelProviderRegistry();
        var bindings = new RoleModelBindingService(providers);
        var workspaceRegistry = new WorkspaceRegistry();
        var auditService = new AuditService();
        var pathGuard = new PathGuard();
        var localFileService = new LocalFileService(workspaceRegistry, pathGuard);
        var localTaskStore = new LocalTaskStore();
        var taskQueue = new LocalTaskQueueService(workspaceRegistry, localTaskStore, events);
        var localCommandService = new LocalCommandService(
                workspaceRegistry,
                new ApprovalPolicy(),
                auditService,
                localTaskStore,
                taskQueue
        );
        var gitDiff = new LocalGitDiffService(workspaceRegistry);
        var localExecutionSummary = new LocalExecutionSummaryService(
                workspaceRegistry,
                localFileService,
                localCommandService,
                gitDiff,
                auditService
        );
        var modelGateway = new ModelGatewayService(
                providers,
                bindings,
                new PromptContractBuilder(),
                new PromptCacheEstimator(),
                new UsageCalculator(),
                new ContextEngine(),
                new DeterministicModelAdapter(productDraftAgent),
                events
        );
        var composeService = new ComposeEnvironmentService(
                deploymentTargets,
                workspaceRegistry,
                pathGuard,
                new SuccessfulComposeRuntimeClient()
        );
        var service = new WorkbenchService(
                new DocumentService(store),
                productDraftAgent,
                new BugService(store),
                deploymentTargets,
                deploymentHealth,
                deploymentOperations,
                composeService,
                events,
                modelGateway,
                localExecutionSummary,
                new RuntimeNotificationService(),
                store,
                repository
        );
        return new Harness(service, taskQueue);
    }

    private void 准备可验收项目(WorkbenchService service, String projectId) {
        var drafts = service.createProductDrafts(projectId, "支付失败后允许用户重新发起支付。");
        service.freezeDocument(projectId, drafts.getFirst().id(), "user-product");
        service.submitDeveloperDelivery(projectId, "/repo/payment", "完成失败态处理", "测试通过",
                "GET /payments/{id}", "alter table payment add fail_reason varchar(255);", "docker compose up -d");
        var bug = service.createBug(projectId, "失败原因为空", com.matrixcode.bug.domain.BugSeverity.HIGH,
                "支付失败", "显示失败原因", "为空白", "测试", "开发");
        service.transitionBug(projectId, bug.id(), "REGRESSION_PENDING", "开发已修复");
        service.transitionBug(projectId, bug.id(), "CLOSED", "回归通过");
        service.submitTestReport(projectId, "核心链路通过，等待产品验收");
        service.configureDeploymentTarget(projectId, "测试环境", "https://test.example.com",
                "deploy@example.com", "按部署文档执行", "https://test.example.com/health", "回滚上一版本");
    }

    private WorkflowEvent workflowEvent(String id, String itemId) {
        return new WorkflowEvent(
                id,
                itemId,
                WorkflowEventType.SUBMIT_REVIEW,
                WorkflowState.DRAFT,
                WorkflowState.REVIEW_PENDING,
                "user-product",
                Instant.parse("2026-06-25T12:00:00Z")
        );
    }

    private record Harness(WorkbenchService service, LocalTaskQueueService localTaskQueueService) {
        private void shutdown() {
            localTaskQueueService.shutdown();
        }
    }

    private static final class RecordingWorkbenchProgressRepository implements WorkbenchProgressRepository {
        private List<WorkflowItem> workflowItems = List.of();
        private Map<String, List<WorkflowEvent>> workflowEvents = Map.of();
        private Map<String, WorkbenchStateSnapshot.AcceptanceState> acceptances = Map.of();

        @Override
        public List<WorkflowItem> loadWorkflowItems() {
            return workflowItems;
        }

        @Override
        public void saveWorkflowItems(List<WorkflowItem> items) {
            workflowItems = List.copyOf(items);
        }

        @Override
        public Map<String, List<WorkflowEvent>> loadWorkflowEvents() {
            return workflowEvents;
        }

        @Override
        public void saveWorkflowEvents(Map<String, List<WorkflowEvent>> events) {
            workflowEvents = copyGrouped(events);
        }

        @Override
        public Map<String, WorkbenchStateSnapshot.AcceptanceState> loadAcceptances() {
            return acceptances;
        }

        @Override
        public void saveAcceptances(Map<String, WorkbenchStateSnapshot.AcceptanceState> values) {
            acceptances = Map.copyOf(values);
        }

        private static <T> Map<String, List<T>> copyGrouped(Map<String, List<T>> values) {
            var result = new HashMap<String, List<T>>();
            values.forEach((key, list) -> result.put(key, List.copyOf(list)));
            return Map.copyOf(result);
        }
    }

    private static final class SuccessfulComposeRuntimeClient implements ComposeRuntimeClient {
        @Override
        public ComposeRuntimeResult validate(ComposeRuntimeRequest request) {
            return ComposeRuntimeResult.succeeded("Compose 配置有效", "");
        }

        @Override
        public ComposeRuntimeResult start(ComposeRuntimeRequest request) {
            return ComposeRuntimeResult.succeeded("Compose 已启动", "");
        }

        @Override
        public ComposeRuntimeResult stop(ComposeRuntimeRequest request) {
            return ComposeRuntimeResult.succeeded("Compose 已停止", "");
        }

        @Override
        public ComposeRuntimeResult logs(ComposeRuntimeRequest request) {
            return ComposeRuntimeResult.succeeded("Compose 日志已采集", "");
        }
    }
}
