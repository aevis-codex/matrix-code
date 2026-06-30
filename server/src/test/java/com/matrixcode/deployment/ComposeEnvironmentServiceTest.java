package com.matrixcode.deployment;

import com.matrixcode.deployment.application.ComposeEnvironmentService;
import com.matrixcode.deployment.application.ComposeRuntimeClient;
import com.matrixcode.deployment.application.ComposeRuntimeRequest;
import com.matrixcode.deployment.application.ComposeRuntimeResult;
import com.matrixcode.deployment.application.DeploymentTargetService;
import com.matrixcode.deployment.domain.ComposeEnvironmentStatus;
import com.matrixcode.deployment.domain.ComposeOperationStatus;
import com.matrixcode.deployment.domain.ComposeOperationType;
import com.matrixcode.localexecution.application.PathGuard;
import com.matrixcode.localexecution.application.WorkspaceRegistry;
import com.matrixcode.workbench.application.InMemoryWorkbenchStateStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ComposeEnvironmentServiceTest {

    @TempDir
    Path workspace;

    private final DeploymentTargetService targets = new DeploymentTargetService();
    private final WorkspaceRegistry workspaces = new WorkspaceRegistry();
    private final RecordingComposeRuntimeClient runtime = new RecordingComposeRuntimeClient();
    private final ComposeEnvironmentService service = new ComposeEnvironmentService(
            targets,
            workspaces,
            new PathGuard(),
            runtime
    );

    @Test
    void 配置Compose环境时要求授权工作区内Yaml文件并关联部署目标() throws Exception {
        Files.writeString(workspace.resolve("compose.yml"), "services:\n  web:\n    image: nginx:alpine\n");
        var workspaceAuth = workspaces.authorize("demo", "演示工作区", workspace.toString());
        var target = targets.configure("demo", "演示环境", "http://127.0.0.1:8080",
                "deploy@local", "本地 Compose 演示", "http://127.0.0.1:8080/health", "停止演示服务");

        var environment = service.configure("demo", target.id(), workspaceAuth.id(), "compose.yml", "matrixcode-demo", "web");

        assertThat(environment.projectId()).isEqualTo("demo");
        assertThat(environment.targetId()).isEqualTo(target.id());
        assertThat(environment.workspaceId()).isEqualTo(workspaceAuth.id());
        assertThat(environment.composeFilePath()).isEqualTo("compose.yml");
        assertThat(environment.projectName()).isEqualTo("matrixcode-demo");
        assertThat(environment.serviceName()).isEqualTo("web");
        assertThat(environment.status()).isEqualTo(ComposeEnvironmentStatus.CONFIGURED);
        assertThat(environment.createdAt()).isNotNull();
        assertThat(environment.updatedAt()).isNotNull();
        assertThat(service.listByProject("demo")).containsExactly(environment);
    }

    @Test
    void 越界路径非Yaml文件和非法名称会被拒绝() throws Exception {
        Files.writeString(workspace.resolve("README.md"), "不是 Compose 文件");
        Files.writeString(workspace.resolve("compose.yml"), "services:\n  web:\n    image: nginx:alpine\n");
        var workspaceAuth = workspaces.authorize("demo", "演示工作区", workspace.toString());
        var target = targets.configure("demo", "演示环境", "http://127.0.0.1:8080",
                "deploy@local", "本地 Compose 演示", "http://127.0.0.1:8080/health", "停止演示服务");

        assertThatThrownBy(() -> service.configure("demo", target.id(), workspaceAuth.id(),
                "../compose.yml", "matrixcode-demo", "web"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("路径");
        assertThatThrownBy(() -> service.configure("demo", target.id(), workspaceAuth.id(),
                "README.md", "matrixcode-demo", "web"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Compose 文件必须使用 .yml 或 .yaml 后缀");
        assertThatThrownBy(() -> service.configure("demo", target.id(), workspaceAuth.id(),
                "compose.yml", "matrixcode demo", "web"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Compose 项目名不合法");
        assertThatThrownBy(() -> service.configure("demo", target.id(), workspaceAuth.id(),
                "compose.yml", "matrixcode-demo", "web;rm"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Compose 服务名不合法");
    }

    @Test
    void 校验启动停止和日志采样会更新状态并记录最近操作() throws Exception {
        var environment = prepareEnvironment();
        runtime.nextResult = ComposeRuntimeResult.succeeded("Compose 配置有效", "services:\n  web:");

        var validation = service.validate("demo", environment.id(), "user-ops");
        var start = service.start("demo", environment.id(), "user-ops");
        var logs = service.captureLogs("demo", environment.id(), "user-ops");
        var stop = service.stop("demo", environment.id(), "user-ops");

        assertThat(validation.type()).isEqualTo(ComposeOperationType.VALIDATE);
        assertThat(validation.status()).isEqualTo(ComposeOperationStatus.SUCCEEDED);
        assertThat(start.type()).isEqualTo(ComposeOperationType.START);
        assertThat(logs.type()).isEqualTo(ComposeOperationType.LOGS);
        assertThat(stop.type()).isEqualTo(ComposeOperationType.STOP);
        assertThat(runtime.actions).containsExactly("validate", "start", "logs", "stop");
        assertThat(runtime.lastRequest.composeFile()).isEqualTo(workspace.resolve("compose.yml").toRealPath());
        assertThat(runtime.lastRequest.projectName()).isEqualTo("matrixcode-demo");
        assertThat(runtime.lastRequest.serviceName()).isEqualTo("web");
        assertThat(service.requireByProject("demo", environment.id()).status()).isEqualTo(ComposeEnvironmentStatus.STOPPED);
        assertThat(service.latestOperationForEnvironment("demo", environment.id())).isEqualTo(stop);
        assertThat(service.latestByProject("demo")).containsExactly(stop, logs, start, validation);
    }

    @Test
    void 运行客户端失败会记录失败并让环境进入失败状态() throws Exception {
        var environment = prepareEnvironment();
        runtime.nextResult = ComposeRuntimeResult.failed("Docker Compose 不可用", "Cannot run program docker");

        var operation = service.start("demo", environment.id(), "user-ops");

        assertThat(operation.status()).isEqualTo(ComposeOperationStatus.FAILED);
        assertThat(operation.summary()).isEqualTo("Docker Compose 不可用");
        assertThat(operation.logExcerpt()).contains("Cannot run program docker");
        assertThat(service.requireByProject("demo", environment.id()).status()).isEqualTo(ComposeEnvironmentStatus.FAILED);
    }

    @Test
    void 服务重建后恢复Compose环境和最近操作() throws Exception {
        var store = new InMemoryWorkbenchStateStore();
        Files.writeString(workspace.resolve("compose.yml"), "services:\n  web:\n    image: nginx:alpine\n");
        var firstTargets = new DeploymentTargetService(store);
        var firstWorkspaces = new WorkspaceRegistry();
        var workspaceAuth = firstWorkspaces.authorize("demo", "演示工作区", workspace.toString());
        var target = firstTargets.configure("demo", "演示环境", "http://127.0.0.1:8080",
                "deploy@local", "本地 Compose 演示", "http://127.0.0.1:8080/health", "停止演示服务");
        var firstRuntime = new RecordingComposeRuntimeClient();
        var firstService = new ComposeEnvironmentService(
                firstTargets,
                firstWorkspaces,
                new PathGuard(),
                firstRuntime,
                store
        );
        var environment = firstService.configure("demo", target.id(), workspaceAuth.id(),
                "compose.yml", "matrixcode-demo", "web");
        var operation = firstService.validate("demo", environment.id(), "user-ops");

        var secondService = new ComposeEnvironmentService(
                new DeploymentTargetService(store),
                new WorkspaceRegistry(),
                new PathGuard(),
                new RecordingComposeRuntimeClient(),
                store
        );

        assertThat(secondService.listByProject("demo")).singleElement().satisfies(restored -> {
            assertThat(restored.id()).isEqualTo(environment.id());
            assertThat(restored.status()).isEqualTo(ComposeEnvironmentStatus.VALIDATED);
        });
        assertThat(secondService.latestByProject("demo")).containsExactly(operation);
        assertThat(secondService.latestOperationForEnvironment("demo", environment.id())).isEqualTo(operation);
    }

    private com.matrixcode.deployment.domain.ComposeEnvironment prepareEnvironment() throws Exception {
        Files.writeString(workspace.resolve("compose.yml"), "services:\n  web:\n    image: nginx:alpine\n");
        var workspaceAuth = workspaces.authorize("demo", "演示工作区", workspace.toString());
        var target = targets.configure("demo", "演示环境", "http://127.0.0.1:8080",
                "deploy@local", "本地 Compose 演示", "http://127.0.0.1:8080/health", "停止演示服务");
        return service.configure("demo", target.id(), workspaceAuth.id(), "compose.yml", "matrixcode-demo", "web");
    }

    private static final class RecordingComposeRuntimeClient implements ComposeRuntimeClient {
        private ComposeRuntimeResult nextResult = ComposeRuntimeResult.succeeded("执行成功", "日志摘录");
        private final List<String> actions = new ArrayList<>();
        private ComposeRuntimeRequest lastRequest;

        @Override
        public ComposeRuntimeResult validate(ComposeRuntimeRequest request) {
            actions.add("validate");
            lastRequest = request;
            return nextResult;
        }

        @Override
        public ComposeRuntimeResult start(ComposeRuntimeRequest request) {
            actions.add("start");
            lastRequest = request;
            return nextResult;
        }

        @Override
        public ComposeRuntimeResult stop(ComposeRuntimeRequest request) {
            actions.add("stop");
            lastRequest = request;
            return nextResult;
        }

        @Override
        public ComposeRuntimeResult logs(ComposeRuntimeRequest request) {
            actions.add("logs");
            lastRequest = request;
            return nextResult;
        }
    }
}
