package com.matrixcode.deployment;

import com.matrixcode.deployment.application.ComposeEnvironmentService;
import com.matrixcode.deployment.application.ComposeRuntimeClient;
import com.matrixcode.deployment.application.ComposeRuntimeRequest;
import com.matrixcode.deployment.application.ComposeRuntimeResult;
import com.matrixcode.deployment.application.DeploymentOperationService;
import com.matrixcode.deployment.application.DeploymentRuntimeRepository;
import com.matrixcode.deployment.application.DeploymentTargetService;
import com.matrixcode.deployment.domain.ComposeEnvironment;
import com.matrixcode.deployment.domain.ComposeEnvironmentStatus;
import com.matrixcode.deployment.domain.ComposeOperationRecord;
import com.matrixcode.deployment.domain.DeploymentHealthCheck;
import com.matrixcode.deployment.domain.DeploymentOperationRecord;
import com.matrixcode.deployment.domain.DeploymentOperationStatus;
import com.matrixcode.deployment.domain.DeploymentOperationType;
import com.matrixcode.localexecution.application.PathGuard;
import com.matrixcode.localexecution.application.WorkspaceRegistry;
import com.matrixcode.workbench.application.InMemoryWorkbenchStateStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class DeploymentRuntimeRepositoryServiceTest {

    @TempDir
    Path workspace;

    @Test
    void 部署操作写入正式仓储而不写旧快照() {
        var store = new InMemoryWorkbenchStateStore();
        var repository = new RecordingDeploymentRuntimeRepository();
        var targets = new DeploymentTargetService(store);
        var target = targets.configure("demo", "预发环境", "https://pre.example.com",
                "deploy@example.com", "部署", "https://pre.example.com/health", "回滚");
        var service = new DeploymentOperationService(targets, store, repository);

        var operation = service.record("demo", target.id(), "user-ops",
                DeploymentOperationType.DEPLOYMENT, DeploymentOperationStatus.SUCCEEDED, "部署完成");

        assertThat(repository.deploymentOperations.get("demo")).containsExactly(operation);
        assertThat(store.load().deploymentOperations()).isEmpty();
    }

    @Test
    void Compose旧快照在正式仓储为空时回填() throws Exception {
        var store = new InMemoryWorkbenchStateStore();
        Files.writeString(workspace.resolve("compose.yml"), "services:\n  web:\n    image: nginx:alpine\n");
        var firstTargets = new DeploymentTargetService(store);
        var firstWorkspaces = new WorkspaceRegistry();
        var workspaceAuth = firstWorkspaces.authorize("demo", "演示工作区", workspace.toString());
        var target = firstTargets.configure("demo", "演示环境", "http://127.0.0.1:8080",
                "deploy@local", "本地 Compose 演示", "http://127.0.0.1:8080/health", "停止演示服务");
        var firstService = new ComposeEnvironmentService(
                firstTargets,
                firstWorkspaces,
                new PathGuard(),
                new SuccessfulComposeRuntimeClient(),
                store
        );
        var environment = firstService.configure("demo", target.id(), workspaceAuth.id(),
                "compose.yml", "matrixcode-demo", "web");
        var operation = firstService.validate("demo", environment.id(), "user-ops");
        var repository = new RecordingDeploymentRuntimeRepository();

        var secondService = new ComposeEnvironmentService(
                new DeploymentTargetService(store),
                firstWorkspaces,
                new PathGuard(),
                new SuccessfulComposeRuntimeClient(),
                store,
                repository
        );

        assertThat(secondService.listByProject("demo")).singleElement().satisfies(restored -> {
            assertThat(restored.id()).isEqualTo(environment.id());
            assertThat(restored.status()).isEqualTo(ComposeEnvironmentStatus.VALIDATED);
            assertThat(restored.projectName()).isEqualTo("matrixcode-demo");
        });
        assertThat(repository.composeEnvironments).singleElement().satisfies(restored -> {
            assertThat(restored.id()).isEqualTo(environment.id());
            assertThat(restored.projectName()).isEqualTo("matrixcode-demo");
            assertThat(restored.status()).isEqualTo(ComposeEnvironmentStatus.VALIDATED);
        });
        assertThat(repository.composeOperations.get("demo")).containsExactly(operation);
    }

    private static final class RecordingDeploymentRuntimeRepository implements DeploymentRuntimeRepository {
        private Map<String, List<DeploymentOperationRecord>> deploymentOperations = Map.of();
        private Map<String, List<DeploymentHealthCheck>> deploymentHealthChecks = Map.of();
        private List<ComposeEnvironment> composeEnvironments = List.of();
        private Map<String, List<ComposeOperationRecord>> composeOperations = Map.of();

        @Override
        public Map<String, List<DeploymentOperationRecord>> loadDeploymentOperations() {
            return deploymentOperations;
        }

        @Override
        public void saveDeploymentOperations(Map<String, List<DeploymentOperationRecord>> operations) {
            deploymentOperations = copy(operations);
        }

        @Override
        public Map<String, List<DeploymentHealthCheck>> loadDeploymentHealthChecks() {
            return deploymentHealthChecks;
        }

        @Override
        public void saveDeploymentHealthChecks(Map<String, List<DeploymentHealthCheck>> checks) {
            deploymentHealthChecks = copy(checks);
        }

        @Override
        public List<ComposeEnvironment> loadComposeEnvironments() {
            return composeEnvironments;
        }

        @Override
        public void saveComposeEnvironments(List<ComposeEnvironment> environments) {
            composeEnvironments = List.copyOf(environments);
        }

        @Override
        public Map<String, List<ComposeOperationRecord>> loadComposeOperations() {
            return composeOperations;
        }

        @Override
        public void saveComposeOperations(Map<String, List<ComposeOperationRecord>> operations) {
            composeOperations = copy(operations);
        }

        private static <T> Map<String, List<T>> copy(Map<String, List<T>> values) {
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
