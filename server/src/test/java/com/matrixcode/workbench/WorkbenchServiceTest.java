package com.matrixcode.workbench;

import com.matrixcode.agent.application.LocalProductDraftAgent;
import com.matrixcode.bug.application.BugService;
import com.matrixcode.bug.domain.BugSeverity;
import com.matrixcode.bug.domain.BugStatus;
import com.matrixcode.deployment.application.ComposeEnvironmentService;
import com.matrixcode.deployment.application.ComposeRuntimeClient;
import com.matrixcode.deployment.application.ComposeRuntimeRequest;
import com.matrixcode.deployment.application.ComposeRuntimeResult;
import com.matrixcode.deployment.application.DeploymentHealthProbe;
import com.matrixcode.deployment.application.DeploymentHealthService;
import com.matrixcode.deployment.application.DeploymentOperationService;
import com.matrixcode.deployment.application.DeploymentTargetService;
import com.matrixcode.deployment.domain.DeploymentHealthStatus;
import com.matrixcode.deployment.domain.DeploymentOperationStatus;
import com.matrixcode.deployment.domain.DeploymentOperationType;
import com.matrixcode.document.application.DocumentService;
import com.matrixcode.document.domain.DocumentState;
import com.matrixcode.document.domain.DocumentType;
import com.matrixcode.context.application.ContextEngine;
import com.matrixcode.approval.application.ApprovalPolicy;
import com.matrixcode.approval.application.AuditService;
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
import com.matrixcode.workbench.application.InMemoryWorkbenchStateStore;
import com.matrixcode.usage.application.UsageCalculator;
import com.matrixcode.workbench.application.WorkbenchService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class WorkbenchServiceTest {

    @TempDir
    Path workspace;

    private final ProjectEventBus events = new ProjectEventBus();
    private final LocalProductDraftAgent productDraftAgent = new LocalProductDraftAgent();
    private final DeploymentTargetService deploymentTargetService = new DeploymentTargetService();
    private final DeploymentHealthService deploymentHealthService = new DeploymentHealthService(
            deploymentTargetService,
            uri -> new DeploymentHealthProbe(DeploymentHealthStatus.HEALTHY, 200, 12, "HTTP 200")
    );
    private final DeploymentOperationService deploymentOperationService = new DeploymentOperationService(deploymentTargetService);
    private final ModelProviderRegistry providers = new ModelProviderRegistry();
    private final RoleModelBindingService bindings = new RoleModelBindingService(providers);
    private final WorkspaceRegistry workspaceRegistry = new WorkspaceRegistry();
    private final AuditService auditService = new AuditService();
    private final PathGuard pathGuard = new PathGuard();
    private final LocalFileService localFileService = new LocalFileService(workspaceRegistry, pathGuard);
    private final LocalTaskStore localTaskStore = new LocalTaskStore();
    private final LocalTaskQueueService localTaskQueueService = new LocalTaskQueueService(
            workspaceRegistry,
            localTaskStore,
            events
    );
    private final LocalCommandService localCommandService = new LocalCommandService(
            workspaceRegistry,
            new ApprovalPolicy(),
            auditService,
            localTaskStore,
            localTaskQueueService
    );
    private final LocalGitDiffService localGitDiffService = new LocalGitDiffService(workspaceRegistry);
    private final LocalExecutionSummaryService localExecutionSummaryService = new LocalExecutionSummaryService(
            workspaceRegistry,
            localFileService,
            localCommandService,
            localGitDiffService,
            auditService
    );
    private final RuntimeNotificationService runtimeNotificationService = new RuntimeNotificationService();
    private final ModelGatewayService modelGateway = new ModelGatewayService(
            providers,
            bindings,
            new PromptContractBuilder(),
            new PromptCacheEstimator(),
            new UsageCalculator(),
            new ContextEngine(),
            new DeterministicModelAdapter(productDraftAgent),
            events
    );
    private final ComposeEnvironmentService composeEnvironmentService = new ComposeEnvironmentService(
            deploymentTargetService,
            workspaceRegistry,
            pathGuard,
            new SuccessfulComposeRuntimeClient()
    );
    private final WorkbenchService service = new WorkbenchService(
            new DocumentService(),
            productDraftAgent,
            new BugService(),
            deploymentTargetService,
            deploymentHealthService,
            deploymentOperationService,
            composeEnvironmentService,
            events,
            modelGateway,
            localExecutionSummaryService,
            runtimeNotificationService
    );

    @AfterEach
    void shutdownQueue() {
        localTaskQueueService.shutdown();
    }

    @Test
    void 产品生成草稿后工作台出现三份文档和事件() {
        var drafts = service.createProductDrafts("demo", "支付失败后允许用户重新发起支付。");

        var workbench = service.get("demo");

        assertThat(drafts).hasSize(3);
        assertThat(workbench.currentStage()).isEqualTo("需求草稿");
        assertThat(workbench.documents()).hasSize(3);
        assertThat(workbench.metrics().documentCount()).isEqualTo(3);
        assertThat(workbench.metrics().cacheHitRate()).isEqualTo(0.0);
        assertThat(workbench.metrics().sessionTokens()).isGreaterThan(0);
        assertThat(workbench.modelGateway().bindings()).hasSize(4);
        assertThat(workbench.modelGateway().metrics().requestCount()).isEqualTo(1);
        assertThat(workbench.events()).extracting("type").contains("PRODUCT_DRAFT_CREATED");
    }

    @Test
    void 冻结需求后开发角色可以看到冻结文档且阶段进入开发中() {
        var drafts = service.createProductDrafts("demo", "支付失败后允许用户重新发起支付。");

        service.freezeDocument("demo", drafts.getFirst().id(), "user-product");

        var workbench = service.get("demo");
        var prd = workbench.documents().stream()
                .filter(document -> document.type() == DocumentType.PRD)
                .findFirst()
                .orElseThrow();

        assertThat(workbench.currentStage()).isEqualTo("开发中");
        assertThat(prd.state()).isEqualTo(DocumentState.FROZEN);
        assertThat(workbench.roles()).anySatisfy(role -> {
            assertThat(role.name()).isEqualTo("开发");
            assertThat(role.state()).isEqualTo("可开始实现");
        });
    }

    @Test
    void 开发交付测试Bug部署目标会共同更新工作台投影() {
        var drafts = service.createProductDrafts("demo", "支付失败后允许用户重新发起支付。");
        service.freezeDocument("demo", drafts.getFirst().id(), "user-product");
        service.submitDeveloperDelivery("demo", "/repo/payment", "完成失败态处理", "测试通过",
                "GET /payments/{id}", "alter table payment add fail_reason varchar(255);", "docker compose up -d");
        var bug = service.createBug("demo", "失败原因为空", BugSeverity.HIGH,
                "支付失败", "显示失败原因", "为空白", "测试", "开发");
        service.transitionBug("demo", bug.id(), "REGRESSION_PENDING", "开发已修复");
        service.submitTestReport("demo", "核心链路通过，等待产品验收");
        service.configureDeploymentTarget("demo", "测试环境", "https://test.example.com",
                "deploy@example.com", "按部署文档执行", "https://test.example.com/health", "回滚上一版本");

        var workbench = service.get("demo");

        assertThat(workbench.currentStage()).isEqualTo("缺陷处理中");
        assertThat(workbench.documents()).extracting("title")
                .contains("实现说明", "接口文档", "数据库脚本", "部署文档", "测试报告");
        assertThat(workbench.bugs()).hasSize(1);
        assertThat(workbench.deploymentTargets()).hasSize(1);
        assertThat(workbench.metrics().eventCount()).isGreaterThanOrEqualTo(6);
        assertThat(workbench.metrics().openBugCount()).isEqualTo(1);
    }

    @Test
    void 产品提交验收记录后工作台进入上线准备() {
        var drafts = service.createProductDrafts("demo", "支付失败后允许用户重新发起支付。");
        service.freezeDocument("demo", drafts.getFirst().id(), "user-product");
        service.submitDeveloperDelivery("demo", "/repo/payment", "完成失败态处理", "测试通过",
                "GET /payments/{id}", "alter table payment add fail_reason varchar(255);", "docker compose up -d");
        var bug = service.createBug("demo", "失败原因为空", BugSeverity.HIGH,
                "支付失败", "显示失败原因", "为空白", "测试", "开发");
        service.transitionBug("demo", bug.id(), "REGRESSION_PENDING", "开发已修复");
        service.transitionBug("demo", bug.id(), "CLOSED", "回归通过");
        service.submitTestReport("demo", "核心链路通过，等待产品验收");
        service.configureDeploymentTarget("demo", "测试环境", "https://test.example.com",
                "deploy@example.com", "按部署文档执行", "https://test.example.com/health", "回滚上一版本");

        var acceptance = service.submitAcceptance("demo", true, "验收通过");
        var workbench = service.get("demo");

        assertThat(acceptance.title()).isEqualTo("产品验收记录");
        assertThat(acceptance.content()).contains("通过").contains("验收通过");
        assertThat(workbench.currentStage()).isEqualTo("上线准备");
        assertThat(workbench.events()).extracting("type").contains("ACCEPTANCE_SUBMITTED");
    }

    @Test
    void 部署健康检查和运维操作会进入工作台运行摘要() {
        var target = service.configureDeploymentTarget("demo", "测试环境", "https://test.example.com",
                "deploy@example.com", "按部署文档执行", "https://test.example.com/health", "回滚上一版本");

        var healthCheck = service.runDeploymentHealthCheck("demo", target.id(), "user-ops");
        var deployment = service.recordDeploymentOperation("demo", target.id(), "user-ops",
                DeploymentOperationType.DEPLOYMENT, DeploymentOperationStatus.SUCCEEDED, "部署成功");
        var rollback = service.recordDeploymentOperation("demo", target.id(), "user-ops",
                DeploymentOperationType.ROLLBACK, DeploymentOperationStatus.RECORDED, "回滚预案已记录");

        var workbench = service.get("demo");

        assertThat(workbench.deploymentRuntimeSummaries()).singleElement().satisfies(summary -> {
            assertThat(summary.targetId()).isEqualTo(target.id());
            assertThat(summary.latestHealthCheck()).isEqualTo(healthCheck);
            assertThat(summary.latestDeploymentOperation()).isEqualTo(deployment);
            assertThat(summary.latestRollbackOperation()).isEqualTo(rollback);
        });
        assertThat(workbench.events()).extracting("type")
                .contains("DEPLOYMENT_HEALTH_CHECKED", "DEPLOYMENT_OPERATION_RECORDED");
    }

    @Test
    void Compose演示环境和最近操作会进入工作台运行摘要() throws Exception {
        Files.writeString(workspace.resolve("compose.yml"), "services:\n  web:\n    image: nginx:alpine\n");
        var workspaceAuth = workspaceRegistry.authorize("demo", "演示工作区", workspace.toString());
        var target = service.configureDeploymentTarget("demo", "演示环境", "http://127.0.0.1:8080",
                "deploy@local", "本地 Compose 演示", "http://127.0.0.1:8080/health", "停止演示服务");

        var environment = service.configureComposeEnvironment(
                "demo",
                target.id(),
                workspaceAuth.id(),
                "compose.yml",
                "matrixcode-demo",
                "web"
        );
        var operation = service.validateComposeEnvironment("demo", environment.id(), "user-ops");

        var workbench = service.get("demo");

        assertThat(workbench.composeEnvironments())
                .containsExactly(composeEnvironmentService.requireByProject("demo", environment.id()));
        assertThat(workbench.composeRuntimeViews()).singleElement().satisfies(view -> {
            assertThat(view.environmentId()).isEqualTo(environment.id());
            assertThat(view.targetId()).isEqualTo(target.id());
            assertThat(view.composeFilePath()).isEqualTo("compose.yml");
            assertThat(view.projectName()).isEqualTo("matrixcode-demo");
            assertThat(view.serviceName()).isEqualTo("web");
            assertThat(view.latestOperation()).isEqualTo(operation);
        });
        assertThat(workbench.events()).extracting("type")
                .contains("COMPOSE_ENVIRONMENT_CONFIGURED", "COMPOSE_OPERATION_RECORDED");
    }

    @Test
    void 工作台会返回运行态提醒收件箱投影() {
        var workspaceAuth = workspaceRegistry.authorize("demo", "当前项目", workspace.toString());

        var task = localCommandService.submit("demo", workspaceAuth.id(), "user-ops", "git status");
        var workbench = service.get("demo");

        assertThat(workbench.runtimeNotifications()).singleElement().satisfies(notification -> {
            assertThat(notification.id()).isEqualTo("approval:" + task.taskId());
            assertThat(notification.level().name()).isEqualTo("ACTION");
            assertThat(notification.title()).isEqualTo("需要审批本地命令");
            assertThat(notification.readAt()).isNull();
        });
    }

    @Test
    void 最新验收结论不通过时默认退回开发且不会进入运维或上线阶段() {
        准备可验收项目("demo");

        service.submitAcceptance("demo", true, "第一轮验收通过");
        service.submitAcceptance("demo", false, "第二轮验收发现失败提示缺失");
        var workbench = service.get("demo");

        assertThat(workbench.currentStage()).isEqualTo("验收退回开发");
        assertThat(workbench.documents()).filteredOn(document -> document.type() == DocumentType.ACCEPTANCE_RECORD)
                .anySatisfy(document -> assertThat(document.content()).contains("验收结论：不通过"));
    }

    @Test
    void 验收不通过可以明确退回测试或开发() {
        准备可验收项目("demo");

        service.submitAcceptance("demo", false, "回归证据不足", "测试");
        assertThat(service.get("demo").currentStage()).isEqualTo("验收退回测试");

        service.submitAcceptance("demo", false, "实现逻辑仍需调整", "开发");
        assertThat(service.get("demo").currentStage()).isEqualTo("验收退回开发");
    }

    @Test
    void 不通过验收备注包含结论字段时仍不会进入运维或上线阶段() {
        准备可验收项目("demo");

        var acceptance = service.submitAcceptance("demo", false, "发现问题\n验收结论：通过");
        var workbench = service.get("demo");

        assertThat(acceptance.content())
                .contains("验收结论：不通过")
                .contains("发现问题\n验收结论：通过");
        assertThat(workbench.currentStage()).isEqualTo("验收退回开发");
    }

    @Test
    void 最新验收结论通过时才进入上线准备() {
        准备可验收项目("demo");

        service.submitAcceptance("demo", false, "第一轮验收不通过");
        assertThat(service.get("demo").currentStage()).isEqualTo("验收退回开发");

        service.submitAcceptance("demo", true, "第二轮验收通过");

        assertThat(service.get("demo").currentStage()).isEqualTo("上线准备");
    }

    @Test
    void 服务重建后恢复最新验收退回投影() {
        var store = new InMemoryWorkbenchStateStore();
        var first = harness(store);
        准备可验收项目(first.service(), "persisted");
        first.service().submitAcceptance("persisted", false, "回归证据不足", "测试");
        first.shutdown();

        var second = harness(store);
        try {
            assertThat(second.service().get("persisted").currentStage()).isEqualTo("验收退回测试");
        } finally {
            second.shutdown();
        }
    }

    @Test
    void 提交开发交付物时核心字段不能为空() {
        assertThatThrownBy(() -> service.submitDeveloperDelivery("demo", " ", "完成失败态处理", "测试通过",
                "GET /payments/{id}", "alter table payment add fail_reason varchar(255);", "docker compose up -d"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("工作区路径不能为空");
        assertThatThrownBy(() -> service.submitDeveloperDelivery("demo", "/repo/payment", " ", "测试通过",
                "GET /payments/{id}", "alter table payment add fail_reason varchar(255);", "docker compose up -d"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("实现说明不能为空");
        assertThatThrownBy(() -> service.submitDeveloperDelivery("demo", "/repo/payment", "完成失败态处理", " ",
                "GET /payments/{id}", "alter table payment add fail_reason varchar(255);", "docker compose up -d"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("自测结果不能为空");
    }

    @Test
    void 冻结其他项目文档会被拒绝() {
        var drafts = service.createProductDrafts("demo", "支付失败后允许用户重新发起支付。");

        assertThatThrownBy(() -> service.freezeDocument("other", drafts.getFirst().id(), "user-product"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("文档不属于项目");
    }

    @Test
    void Bug状态字符串会先清理空白再流转() {
        var bug = service.createBug("demo", "失败原因为空", BugSeverity.HIGH,
                "支付失败", "显示失败原因", "为空白", "测试", "开发");

        var transitioned = service.transitionBug("demo", bug.id(), " REGRESSION_PENDING ", "开发已修复");

        assertThat(transitioned.status()).isEqualTo(BugStatus.REGRESSION_PENDING);
    }

    @Test
    void Bug状态字符串为空时给出中文异常() {
        var bug = service.createBug("demo", "失败原因为空", BugSeverity.HIGH,
                "支付失败", "显示失败原因", "为空白", "测试", "开发");

        assertThatThrownBy(() -> service.transitionBug("demo", bug.id(), " ", "开发已修复"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("目标 Bug 状态不能为空");
    }

    @Test
    void Bug状态字符串非法时给出中文异常() {
        var bug = service.createBug("demo", "失败原因为空", BugSeverity.HIGH,
                "支付失败", "显示失败原因", "为空白", "测试", "开发");

        assertThatThrownBy(() -> service.transitionBug("demo", bug.id(), "不存在的状态", "开发已修复"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Bug 状态不合法");
    }

    private void 准备可验收项目(String projectId) {
        准备可验收项目(service, projectId);
    }

    private void 准备可验收项目(WorkbenchService targetService, String projectId) {
        var drafts = targetService.createProductDrafts(projectId, "支付失败后允许用户重新发起支付。");
        targetService.freezeDocument(projectId, drafts.getFirst().id(), "user-product");
        targetService.submitDeveloperDelivery(projectId, "/repo/payment", "完成失败态处理", "测试通过",
                "GET /payments/{id}", "alter table payment add fail_reason varchar(255);", "docker compose up -d");
        var bug = targetService.createBug(projectId, "失败原因为空", BugSeverity.HIGH,
                "支付失败", "显示失败原因", "为空白", "测试", "开发");
        targetService.transitionBug(projectId, bug.id(), "REGRESSION_PENDING", "开发已修复");
        targetService.transitionBug(projectId, bug.id(), "CLOSED", "回归通过");
        targetService.submitTestReport(projectId, "核心链路通过，等待产品验收");
        targetService.configureDeploymentTarget(projectId, "测试环境", "https://test.example.com",
                "deploy@example.com", "按部署文档执行", "https://test.example.com/health", "回滚上一版本");
    }

    private Harness harness(InMemoryWorkbenchStateStore store) {
        var harnessEvents = new ProjectEventBus();
        var harnessProductAgent = new LocalProductDraftAgent();
        var harnessDeploymentTargets = new DeploymentTargetService();
        var harnessDeploymentHealth = new DeploymentHealthService(
                harnessDeploymentTargets,
                uri -> new DeploymentHealthProbe(DeploymentHealthStatus.HEALTHY, 200, 12, "HTTP 200")
        );
        var harnessDeploymentOperations = new DeploymentOperationService(harnessDeploymentTargets);
        var harnessProviders = new ModelProviderRegistry();
        var harnessBindings = new RoleModelBindingService(harnessProviders);
        var harnessWorkspaceRegistry = new WorkspaceRegistry();
        var harnessAuditService = new AuditService();
        var harnessPathGuard = new PathGuard();
        var harnessLocalFileService = new LocalFileService(harnessWorkspaceRegistry, harnessPathGuard);
        var harnessLocalTaskStore = new LocalTaskStore();
        var harnessTaskQueue = new LocalTaskQueueService(harnessWorkspaceRegistry, harnessLocalTaskStore, harnessEvents);
        var harnessLocalCommandService = new LocalCommandService(
                harnessWorkspaceRegistry,
                new ApprovalPolicy(),
                harnessAuditService,
                harnessLocalTaskStore,
                harnessTaskQueue
        );
        var harnessGitDiff = new LocalGitDiffService(harnessWorkspaceRegistry);
        var harnessLocalExecutionSummary = new LocalExecutionSummaryService(
                harnessWorkspaceRegistry,
                harnessLocalFileService,
                harnessLocalCommandService,
                harnessGitDiff,
                harnessAuditService
        );
        var harnessModelGateway = new ModelGatewayService(
                harnessProviders,
                harnessBindings,
                new PromptContractBuilder(),
                new PromptCacheEstimator(),
                new UsageCalculator(),
                new ContextEngine(),
                new DeterministicModelAdapter(harnessProductAgent),
                harnessEvents
        );
        var harnessComposeService = new ComposeEnvironmentService(
                harnessDeploymentTargets,
                harnessWorkspaceRegistry,
                harnessPathGuard,
                new SuccessfulComposeRuntimeClient()
        );
        var harnessService = new WorkbenchService(
                new DocumentService(store),
                harnessProductAgent,
                new BugService(store),
                harnessDeploymentTargets,
                harnessDeploymentHealth,
                harnessDeploymentOperations,
                harnessComposeService,
                harnessEvents,
                harnessModelGateway,
                harnessLocalExecutionSummary,
                new RuntimeNotificationService(),
                store
        );
        return new Harness(harnessService, harnessTaskQueue);
    }

    private record Harness(WorkbenchService service, LocalTaskQueueService localTaskQueueService) {
        private void shutdown() {
            localTaskQueueService.shutdown();
        }
    }

    private static final class SuccessfulComposeRuntimeClient implements ComposeRuntimeClient {
        @Override
        public ComposeRuntimeResult validate(ComposeRuntimeRequest request) {
            return ComposeRuntimeResult.succeeded("Compose 配置有效", "services:\n  web:");
        }

        @Override
        public ComposeRuntimeResult start(ComposeRuntimeRequest request) {
            return ComposeRuntimeResult.succeeded("Compose 已启动", "Container web Started");
        }

        @Override
        public ComposeRuntimeResult stop(ComposeRuntimeRequest request) {
            return ComposeRuntimeResult.succeeded("Compose 已停止", "Container web Stopped");
        }

        @Override
        public ComposeRuntimeResult logs(ComposeRuntimeRequest request) {
            return ComposeRuntimeResult.succeeded("Compose 日志已采集", "web ready");
        }
    }
}
