package com.matrixcode.localexecution;

import com.matrixcode.agent.application.LocalProductDraftAgent;
import com.matrixcode.approval.domain.ApprovalDecision;
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
import com.matrixcode.localexecution.domain.ExecutionTaskStatus;
import com.matrixcode.modelgateway.application.DeterministicModelAdapter;
import com.matrixcode.modelgateway.application.ModelGatewayService;
import com.matrixcode.modelgateway.application.ModelProviderRegistry;
import com.matrixcode.modelgateway.application.PromptCacheEstimator;
import com.matrixcode.modelgateway.application.PromptContractBuilder;
import com.matrixcode.modelgateway.application.RoleModelBindingService;
import com.matrixcode.realtime.application.ProjectEventBus;
import com.matrixcode.runtime.application.RuntimeNotificationService;
import com.matrixcode.usage.application.UsageCalculator;
import com.matrixcode.workbench.application.WorkbenchService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class WorkbenchLocalExecutionTest {

    @TempDir
    Path workspace;

    @Test
    void 工作台返回本地执行代理摘要() {
        var events = new ProjectEventBus();
        var productDraftAgent = new LocalProductDraftAgent();
        var providers = new ModelProviderRegistry();
        var bindings = new RoleModelBindingService(providers);
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
        var workspaceRegistry = new WorkspaceRegistry();
        var fileService = new LocalFileService(workspaceRegistry, new PathGuard());
        var audit = new AuditService();
        var taskStore = new LocalTaskStore();
        var taskQueue = new LocalTaskQueueService(workspaceRegistry, taskStore, events);
        var commandService = new LocalCommandService(workspaceRegistry, new ApprovalPolicy(), audit, taskStore, taskQueue);
        var gitDiffService = new LocalGitDiffService(workspaceRegistry);
        var deploymentTargetService = new DeploymentTargetService();
        workspaceRegistry.authorize("demo", "当前项目", workspace.toString());
        try {
            var service = new WorkbenchService(
                    new DocumentService(),
                    productDraftAgent,
                    new BugService(),
                    deploymentTargetService,
                    new DeploymentHealthService(
                            deploymentTargetService,
                            uri -> new DeploymentHealthProbe(DeploymentHealthStatus.HEALTHY, 200, 1, "HTTP 200")
                    ),
                    new DeploymentOperationService(deploymentTargetService),
                    new ComposeEnvironmentService(
                            deploymentTargetService,
                            workspaceRegistry,
                            new PathGuard(),
                            new SuccessfulComposeRuntimeClient()
                    ),
                    events,
                    modelGateway,
                    new LocalExecutionSummaryService(workspaceRegistry, fileService, commandService, gitDiffService, audit),
                    new RuntimeNotificationService()
            );
            var pending = commandService.submit("demo", workspaceRegistry.list("demo").getFirst().id(), "user-dev", "git status");
            var executablePending = commandService.submit("demo", workspaceRegistry.list("demo").getFirst().id(), "user-dev", "sleep 5");
            var queued = commandService.decide("demo", executablePending.taskId(), "user-reviewer", ApprovalDecision.ALLOW, "允许等待");

            var workbench = service.get("demo");

            assertThat(workbench.localExecution().workspaces()).hasSize(1);
            assertThat(workbench.localExecution().workspaces().getFirst().name()).isEqualTo("当前项目");
            assertThat(workbench.localExecution().activeTasks())
                    .extracting("taskId")
                    .contains(queued.taskId())
                    .doesNotContain(pending.taskId());
            assertThat(workbench.localExecution().activeTasks())
                    .extracting("status")
                    .allSatisfy(status -> assertThat(status).isIn(ExecutionTaskStatus.QUEUED, ExecutionTaskStatus.RUNNING));
            assertThat(workbench.localExecution().recentTaskLogs())
                    .anySatisfy(log -> assertThat(log.content()).contains("任务已进入队列"));
        } finally {
            taskQueue.shutdown();
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
