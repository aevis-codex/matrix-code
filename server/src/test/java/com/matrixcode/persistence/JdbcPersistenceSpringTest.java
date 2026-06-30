package com.matrixcode.persistence;

import com.matrixcode.MatrixCodeServerApplication;
import com.matrixcode.bug.application.BugRepository;
import com.matrixcode.bug.domain.BugSeverity;
import com.matrixcode.bug.domain.BugStatus;
import com.matrixcode.codingagent.application.CodingAgentHandoffService;
import com.matrixcode.deployment.domain.DeploymentOperationStatus;
import com.matrixcode.deployment.domain.DeploymentOperationType;
import com.matrixcode.document.application.DocumentRepository;
import com.matrixcode.document.domain.DocumentState;
import com.matrixcode.document.domain.DocumentType;
import com.matrixcode.deployment.application.DeploymentRuntimeRepository;
import com.matrixcode.localexecution.application.LocalCommandService;
import com.matrixcode.localexecution.application.LocalFileService;
import com.matrixcode.localexecution.application.LocalGitDiffService;
import com.matrixcode.localexecution.application.LocalExecutionStateStore;
import com.matrixcode.localexecution.application.LocalWorkspaceActivityRepository;
import com.matrixcode.localexecution.application.WorkspaceRegistry;
import com.matrixcode.localexecution.domain.ExecutionTaskStatus;
import com.matrixcode.deployment.application.DeploymentTargetRepository;
import com.matrixcode.identity.application.ProjectIdentityRepository;
import com.matrixcode.modelgateway.domain.ModelRole;
import com.matrixcode.persistence.application.JdbcWorkbenchStateStore;
import com.matrixcode.persistence.application.MybatisPlusBugRepository;
import com.matrixcode.persistence.application.MybatisPlusDeploymentTargetRepository;
import com.matrixcode.persistence.application.MybatisPlusDeploymentRuntimeRepository;
import com.matrixcode.persistence.application.MybatisPlusDocumentRepository;
import com.matrixcode.persistence.application.MybatisPlusLocalExecutionStateStore;
import com.matrixcode.persistence.application.MybatisPlusLocalWorkspaceActivityRepository;
import com.matrixcode.persistence.application.MybatisPlusProjectActivityRepository;
import com.matrixcode.persistence.application.MybatisPlusProjectIdentityRepository;
import com.matrixcode.persistence.application.MybatisPlusRoleModelBindingRepository;
import com.matrixcode.persistence.application.MybatisPlusWorkbenchProgressRepository;
import com.matrixcode.persistence.application.MybatisPlusRuntimeNotificationStore;
import com.matrixcode.modelgateway.application.RoleModelBindingRepository;
import com.matrixcode.roleagent.application.RoleAgentConfigCommand;
import com.matrixcode.roleagent.application.RoleAgentConfigService;
import com.matrixcode.runtime.application.RuntimeNotificationStore;
import com.matrixcode.workbench.application.ProjectActivityRepository;
import com.matrixcode.workbench.application.WorkbenchProgressRepository;
import com.matrixcode.workbench.application.WorkbenchService;
import com.matrixcode.workbench.application.WorkbenchStateStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class JdbcPersistenceSpringTest {

    @TempDir
    Path tempDir;

    @Test
    void Jdbc模式下Spring上下文重启后恢复三类快照状态() throws Exception {
        var databaseName = "matrixcode_jdbc_spring_" + System.nanoTime();
        var jdbcUrl = "jdbc:h2:mem:" + databaseName
                + ";MODE=MySQL;DATABASE_TO_LOWER=TRUE;CASE_INSENSITIVE_IDENTIFIERS=TRUE;DB_CLOSE_DELAY=-1";
        var workbenchStoragePath = tempDir.resolve("state/workbench-state.json");
        var localExecutionStoragePath = tempDir.resolve("state/local-execution.json");
        var notificationStoragePath = tempDir.resolve("state/runtime-notifications.json");
        var workspaceRoot = tempDir.resolve("workspace");
        Files.createDirectories(workspaceRoot);
        Files.writeString(workspaceRoot.resolve("README.md"), "MatrixCode JDBC 快照验证");
        Files.writeString(workspaceRoot.resolve("compose.yml"), "services:\n  web:\n    image: nginx:alpine\n");

        try (var context = startContext(jdbcUrl, workbenchStoragePath, localExecutionStoragePath, notificationStoragePath)) {
            assertThat(context.getBean(WorkbenchStateStore.class)).isInstanceOf(JdbcWorkbenchStateStore.class);
            assertThat(context.getBean(DocumentRepository.class))
                    .isInstanceOf(MybatisPlusDocumentRepository.class);
            assertThat(context.getBean(BugRepository.class)).isInstanceOf(MybatisPlusBugRepository.class);
            assertThat(context.getBean(DeploymentTargetRepository.class))
                    .isInstanceOf(MybatisPlusDeploymentTargetRepository.class);
            assertThat(context.getBean(DeploymentRuntimeRepository.class))
                    .isInstanceOf(MybatisPlusDeploymentRuntimeRepository.class);
            assertThat(context.getBean(ProjectActivityRepository.class))
                    .isInstanceOf(MybatisPlusProjectActivityRepository.class);
            assertThat(context.getBean(RoleModelBindingRepository.class))
                    .isInstanceOf(MybatisPlusRoleModelBindingRepository.class);
            assertThat(context.getBean(ProjectIdentityRepository.class))
                    .isInstanceOf(MybatisPlusProjectIdentityRepository.class);
            assertThat(context.getBean(WorkbenchProgressRepository.class))
                    .isInstanceOf(MybatisPlusWorkbenchProgressRepository.class);
            assertThat(context.getBean(LocalExecutionStateStore.class))
                    .isInstanceOf(MybatisPlusLocalExecutionStateStore.class);
            assertThat(context.getBean(LocalWorkspaceActivityRepository.class))
                    .isInstanceOf(MybatisPlusLocalWorkspaceActivityRepository.class);
            assertThat(context.getBean(RuntimeNotificationStore.class))
                    .isInstanceOf(MybatisPlusRuntimeNotificationStore.class);
            var workbench = context.getBean(WorkbenchService.class);
            var handoff = context.getBean(CodingAgentHandoffService.class);
            var roleAgents = context.getBean(RoleAgentConfigService.class);
            var workspaces = context.getBean(WorkspaceRegistry.class);
            var localFiles = context.getBean(LocalFileService.class);
            var localCommands = context.getBean(LocalCommandService.class);
            var gitDiff = context.getBean(LocalGitDiffService.class);

            var workspaceId = workspaces.authorize("demo", "JDBC 工作区", workspaceRoot.toString()).id();
            localFiles.read("demo", workspaceId, "README.md");
            var commandTask = localCommands.submit("demo", workspaceId, "user-dev", "git status");
            assertThat(commandTask.status()).isEqualTo(ExecutionTaskStatus.APPROVAL_PENDING);
            gitDiff.capture("demo", workspaceId);

            var drafts = workbench.createProductDrafts("demo", "支付失败后允许用户重新发起支付。");
            workbench.freezeDocument("demo", drafts.getFirst().id(), "user-product");
            handoff.record("demo", ModelRole.DEVELOPER, workspaceId, "user-dev", "修复支付失败重试",
                    "README.md", "补充失败态处理", "1 file changed", "task-1", "SUCCESS",
                    "mvn test", "测试通过，可以交付");
            workbench.submitDeveloperDelivery("demo", "/repo/payment", "完成失败态处理", "测试通过",
                    "GET /payments/{id}", "alter table payment add fail_reason varchar(255);", "docker compose up -d");
            var bug = workbench.createBug("demo", "失败原因为空", BugSeverity.HIGH,
                    "支付失败", "显示失败原因", "为空白", "测试", "开发");
            workbench.transitionBug("demo", bug.id(), BugStatus.CLOSED.name(), "确认关闭");
            workbench.submitTestReport("demo", "核心链路通过，等待产品验收");
            workbench.submitAcceptance("demo", false, "回归证据不足", "测试");
            var target = workbench.configureDeploymentTarget("demo", "JDBC 测试环境", "https://test.example.com",
                    "deploy@example.com", "按部署文档执行", "ssh://test.example.com/health", "回滚上一版本");
            workbench.runDeploymentHealthCheck("demo", target.id(), "user-ops");
            workbench.recordDeploymentOperation("demo", target.id(), "user-ops",
                    DeploymentOperationType.DEPLOYMENT, DeploymentOperationStatus.SUCCEEDED, "部署记录保留");
            workbench.configureComposeEnvironment("demo", target.id(), workspaceId, "compose.yml", "matrixcode-demo", "web");
            roleAgents.update("demo", ModelRole.DEVELOPER, new RoleAgentConfigCommand(
                    "开发智能体 Pro",
                    "coding",
                    "local-deterministic",
                    "matrixcode-local-developer-pro",
                    "tools-v2",
                    "你是开发编码智能体，必须先读代码再修改。",
                    "请基于以下任务输出计划并执行：{{instruction}}",
                    "#0f766e",
                    "Inter",
                    15,
                    2,
                    true,
                    "deepseek-prefix-v2",
                    "stable-prefix-dynamic-tail",
                    "provider-role"
            ));
            workbench.get("demo");

            assertThat(sliceKeys(jdbcUrl))
                    .doesNotContain("workbench-state", "runtime-notifications", "local-execution");
        }

        try (var context = startContext(jdbcUrl, workbenchStoragePath, localExecutionStoragePath, notificationStoragePath)) {
            var workbench = context.getBean(WorkbenchService.class);
            var roleAgents = context.getBean(RoleAgentConfigService.class);

            var restored = workbench.get("demo");
            var restoredDeveloperAgent = roleAgents.require("demo", ModelRole.DEVELOPER);

            assertThat(restored.currentStage()).isEqualTo("验收退回测试");
            assertThat(restoredDeveloperAgent.displayName()).isEqualTo("开发智能体 Pro");
            assertThat(restoredDeveloperAgent.systemPrompt()).contains("必须先读代码");
            assertThat(restoredDeveloperAgent.themeColor()).isEqualTo("#0f766e");
            assertThat(restoredDeveloperAgent.cachePolicyId()).isEqualTo("deepseek-prefix-v2");
            assertThat(restoredDeveloperAgent.volatileSuffixStrategy()).isEqualTo("stable-prefix-dynamic-tail");
            assertThat(restoredDeveloperAgent.cacheScopeStrategy()).isEqualTo("provider-role");
            assertThat(restored.documents()).anySatisfy(document -> {
                assertThat(document.type()).isEqualTo(DocumentType.PRD);
                assertThat(document.state()).isEqualTo(DocumentState.FROZEN);
            });
            assertThat(restored.documents()).anySatisfy(document -> {
                assertThat(document.type()).isEqualTo(DocumentType.CODING_AGENT_HANDOFF);
                assertThat(document.content()).contains("交付结论：测试通过，可以交付");
            });
            assertThat(restored.bugs()).singleElement().satisfies(bug -> {
                assertThat(bug.title()).isEqualTo("失败原因为空");
                assertThat(bug.status()).isEqualTo(BugStatus.CLOSED);
            });
            assertThat(restored.deploymentTargets()).singleElement()
                    .satisfies(target -> assertThat(target.environmentName()).isEqualTo("JDBC 测试环境"));
            assertThat(restored.composeEnvironments()).singleElement()
                    .satisfies(environment -> assertThat(environment.projectName()).isEqualTo("matrixcode-demo"));
            assertThat(restored.modelGateway().metrics().requestCount()).isEqualTo(1);
            assertThat(restored.events()).extracting("type")
                    .contains("PRODUCT_DRAFT_CREATED", "ACCEPTANCE_SUBMITTED", "DEPLOYMENT_TARGET_CONFIGURED");
            assertThat(restored.localExecution().recentFileOperations())
                    .extracting("relativePath")
                    .contains("README.md");
            assertThat(restored.localExecution().workspaces())
                    .extracting("name")
                    .contains("JDBC 工作区");
            assertThat(restored.localExecution().recentTasks())
                    .anySatisfy(task -> {
                        assertThat(task.command()).isEqualTo("git status");
                        assertThat(task.status()).isEqualTo(ExecutionTaskStatus.APPROVAL_PENDING);
                    });
            assertThat(restored.localExecution().recentAuditRecords())
                    .anySatisfy(record -> assertThat(record.summary()).isEqualTo("git status"));
            assertThat(restored.localExecution().recentGitDiff()).isNotNull();
            assertThat(restored.runtimeNotifications())
                    .anySatisfy(notification -> {
                        assertThat(notification.id()).startsWith("approval:");
                        assertThat(notification.title()).isEqualTo("需要审批本地命令");
                    });
        }

        assertThat(sliceKeys(jdbcUrl))
                .doesNotContain("workbench-state", "runtime-notifications", "local-execution");
        assertThat(roleAgentRoles(jdbcUrl)).containsExactlyInAnyOrder("PRODUCT", "DEVELOPER");
        assertThat(documentTypes(jdbcUrl)).contains(DocumentType.PRD.name(), DocumentType.CODING_AGENT_HANDOFF.name());
        assertThat(bugCount(jdbcUrl)).isEqualTo(1);
        assertThat(localExecutionTableCount(jdbcUrl, "matrixcode_deployment_targets")).isEqualTo(1);
        assertThat(localExecutionTableCount(jdbcUrl, "matrixcode_local_workspaces")).isEqualTo(1);
        assertThat(localExecutionTableCount(jdbcUrl, "matrixcode_local_file_operations")).isGreaterThan(0);
        assertThat(localExecutionTableCount(jdbcUrl, "matrixcode_local_git_diff_summaries")).isEqualTo(1);
        assertThat(localExecutionTableCount(jdbcUrl, "matrixcode_local_execution_tasks")).isEqualTo(1);
        assertThat(localExecutionTableCount(jdbcUrl, "matrixcode_audit_records")).isEqualTo(1);
        assertThat(localExecutionTableCount(jdbcUrl, "matrixcode_runtime_notifications")).isGreaterThan(0);
        assertThat(workbenchStoragePath).doesNotExist();
        assertThat(localExecutionStoragePath).doesNotExist();
        assertThat(notificationStoragePath).doesNotExist();
    }

    private ConfigurableApplicationContext startContext(
            String jdbcUrl,
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
                .run(
                        "--matrixcode.persistence.mode=jdbc",
                        "--matrixcode.persistence.jdbc.url=" + jdbcUrl,
                        "--matrixcode.persistence.jdbc.username=sa",
                        "--matrixcode.persistence.jdbc.password=",
                        "--matrixcode.persistence.jdbc.migrate-on-startup=true"
                );
    }

    private List<String> sliceKeys(String jdbcUrl) throws Exception {
        var keys = new ArrayList<String>();
        try (var connection = DriverManager.getConnection(jdbcUrl, "sa", "");
             var statement = connection.prepareStatement(
                     "select slice_key from matrixcode_state_snapshots order by slice_key");
             var resultSet = statement.executeQuery()) {
            while (resultSet.next()) {
                keys.add(resultSet.getString("slice_key"));
            }
        }
        return keys;
    }

    private List<String> roleAgentRoles(String jdbcUrl) throws Exception {
        var roles = new ArrayList<String>();
        try (var connection = DriverManager.getConnection(jdbcUrl, "sa", "");
             var statement = connection.prepareStatement("select role_key from matrixcode_role_agent_configs order by role_key");
             var resultSet = statement.executeQuery()) {
            while (resultSet.next()) {
                roles.add(resultSet.getString("role_key"));
            }
            return roles;
        }
    }

    private List<String> documentTypes(String jdbcUrl) throws Exception {
        var types = new ArrayList<String>();
        try (var connection = DriverManager.getConnection(jdbcUrl, "sa", "");
             var statement = connection.prepareStatement("select document_type from matrixcode_documents order by document_type");
             var resultSet = statement.executeQuery()) {
            while (resultSet.next()) {
                types.add(resultSet.getString("document_type"));
            }
            return types;
        }
    }

    private int bugCount(String jdbcUrl) throws Exception {
        try (var connection = DriverManager.getConnection(jdbcUrl, "sa", "");
             var statement = connection.prepareStatement("select count(*) from matrixcode_bugs");
             var resultSet = statement.executeQuery()) {
            resultSet.next();
            return resultSet.getInt(1);
        }
    }

    private int localExecutionTableCount(String jdbcUrl, String tableName) throws Exception {
        try (var connection = DriverManager.getConnection(jdbcUrl, "sa", "");
             var statement = connection.prepareStatement("select count(*) from " + tableName);
             var resultSet = statement.executeQuery()) {
            resultSet.next();
            return resultSet.getInt(1);
        }
    }
}
