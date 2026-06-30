package com.matrixcode.workbench;

import com.matrixcode.MatrixCodeServerApplication;
import com.matrixcode.bug.domain.BugSeverity;
import com.matrixcode.bug.domain.BugStatus;
import com.matrixcode.deployment.domain.DeploymentOperationStatus;
import com.matrixcode.deployment.domain.DeploymentOperationType;
import com.matrixcode.document.domain.DocumentState;
import com.matrixcode.document.domain.DocumentType;
import com.matrixcode.localexecution.application.LocalFileService;
import com.matrixcode.localexecution.application.LocalGitDiffService;
import com.matrixcode.localexecution.application.WorkspaceRegistry;
import com.matrixcode.workbench.application.WorkbenchService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class WorkbenchStatePersistenceSpringTest {

    @TempDir
    Path tempDir;

    @Test
    void Spring上下文重启后恢复项目工作台核心状态() throws Exception {
        var workbenchStoragePath = tempDir.resolve("state/workbench-state.json");
        var localExecutionStoragePath = tempDir.resolve("state/local-execution.json");
        var notificationStoragePath = tempDir.resolve("state/runtime-notifications.json");
        var workspaceRoot = tempDir.resolve("workspace");
        Files.createDirectories(workspaceRoot.resolve("docs"));
        Files.writeString(workspaceRoot.resolve("README.md"), "MatrixCode 工作台状态持久化验证");
        Files.writeString(workspaceRoot.resolve("compose.yml"), "services:\n  web:\n    image: nginx:alpine\n");

        try (var context = startContext(workbenchStoragePath, localExecutionStoragePath, notificationStoragePath)) {
            var workbench = context.getBean(WorkbenchService.class);
            var workspaces = context.getBean(WorkspaceRegistry.class);
            var localFiles = context.getBean(LocalFileService.class);
            var gitDiff = context.getBean(LocalGitDiffService.class);

            var workspaceId = workspaces.authorize("demo", "持久化工作区", workspaceRoot.toString()).id();
            localFiles.read("demo", workspaceId, "README.md");
            gitDiff.capture("demo", workspaceId);

            var drafts = workbench.createProductDrafts("demo", "支付失败后允许用户重新发起支付。");
            workbench.freezeDocument("demo", drafts.getFirst().id(), "user-product");
            workbench.submitDeveloperDelivery("demo", "/repo/payment", "完成失败态处理", "测试通过",
                    "GET /payments/{id}", "alter table payment add fail_reason varchar(255);", "docker compose up -d");
            var bug = workbench.createBug("demo", "失败原因为空", BugSeverity.HIGH,
                    "支付失败", "显示失败原因", "为空白", "测试", "开发");
            workbench.transitionBug("demo", bug.id(), BugStatus.CLOSED.name(), "确认关闭");
            workbench.submitTestReport("demo", "核心链路通过，等待产品验收");
            workbench.submitAcceptance("demo", false, "回归证据不足", "测试");
            var target = workbench.configureDeploymentTarget("demo", "测试环境", "https://test.example.com",
                    "deploy@example.com", "按部署文档执行", "ssh://test.example.com/health", "回滚上一版本");
            workbench.runDeploymentHealthCheck("demo", target.id(), "user-ops");
            workbench.recordDeploymentOperation("demo", target.id(), "user-ops",
                    DeploymentOperationType.DEPLOYMENT, DeploymentOperationStatus.SUCCEEDED, "部署记录保留");
            workbench.configureComposeEnvironment("demo", target.id(), workspaceId, "compose.yml", "matrixcode-demo", "web");
        }

        try (var context = startContext(workbenchStoragePath, localExecutionStoragePath, notificationStoragePath)) {
            var workbench = context.getBean(WorkbenchService.class);

            var restored = workbench.get("demo");

            assertThat(restored.currentStage()).isEqualTo("验收退回测试");
            assertThat(restored.documents()).anySatisfy(document -> {
                assertThat(document.type()).isEqualTo(DocumentType.PRD);
                assertThat(document.state()).isEqualTo(DocumentState.FROZEN);
            });
            assertThat(restored.bugs()).singleElement().satisfies(bug -> {
                assertThat(bug.title()).isEqualTo("失败原因为空");
                assertThat(bug.status()).isEqualTo(BugStatus.CLOSED);
            });
            assertThat(restored.deploymentTargets()).singleElement()
                    .satisfies(target -> assertThat(target.environmentName()).isEqualTo("测试环境"));
            assertThat(restored.deploymentRuntimeSummaries()).singleElement().satisfies(summary -> {
                assertThat(summary.latestHealthCheck().summary()).contains("协议不支持");
                assertThat(summary.latestDeploymentOperation().note()).isEqualTo("部署记录保留");
            });
            assertThat(restored.composeEnvironments()).singleElement().satisfies(environment -> {
                assertThat(environment.composeFilePath()).isEqualTo("compose.yml");
                assertThat(environment.projectName()).isEqualTo("matrixcode-demo");
            });
            assertThat(restored.modelGateway().metrics().requestCount()).isEqualTo(1);
            assertThat(restored.events()).extracting("type")
                    .contains("PRODUCT_DRAFT_CREATED", "ACCEPTANCE_SUBMITTED", "DEPLOYMENT_TARGET_CONFIGURED");
            assertThat(restored.localExecution().recentFileOperations())
                    .extracting("relativePath")
                    .contains("README.md");
            assertThat(restored.localExecution().recentGitDiff()).isNotNull();
            assertThat(restored.localExecution().recentGitDiff().repository()).isFalse();
        }

        assertThat(Files.exists(workbenchStoragePath)).isTrue();
    }

    private ConfigurableApplicationContext startContext(
            Path workbenchStoragePath,
            Path localExecutionStoragePath,
            Path notificationStoragePath
    ) {
        return new SpringApplicationBuilder(MatrixCodeServerApplication.class)
                .web(WebApplicationType.NONE)
                .properties(Map.of(
                        "matrixcode.workbench-state.storage-path", workbenchStoragePath.toString(),
                        "matrixcode.local-execution.storage-path", localExecutionStoragePath.toString(),
                        "matrixcode.runtime-notifications.storage-path", notificationStoragePath.toString(),
                        "spring.main.banner-mode", "off"
                ))
                .run();
    }
}
