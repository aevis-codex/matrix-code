package com.matrixcode.localexecution;

import com.matrixcode.localexecution.application.InMemoryLocalExecutionStateStore;
import com.matrixcode.localexecution.application.WorkspaceRegistry;
import com.matrixcode.localexecution.domain.WorkspaceStatus;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class WorkspaceRegistryTest {

    @TempDir
    Path tempDir;

    @Test
    void 可以授权存在的本地目录() {
        var registry = new WorkspaceRegistry();

        var workspace = registry.authorize("demo", "当前项目", tempDir.toString());

        assertThat(workspace.id()).isNotBlank();
        assertThat(workspace.projectId()).isEqualTo("demo");
        assertThat(workspace.name()).isEqualTo("当前项目");
        assertThat(workspace.rootPath()).isEqualTo(tempDir.toAbsolutePath().normalize().toString());
        assertThat(workspace.status()).isEqualTo(WorkspaceStatus.AUTHORIZED);
        assertThat(registry.requireAuthorized("demo", workspace.id()).id()).isEqualTo(workspace.id());
    }

    @Test
    void 授权工作区拒绝空项目和不存在路径() {
        var registry = new WorkspaceRegistry();

        assertThatThrownBy(() -> registry.authorize(" ", "当前项目", tempDir.toString()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("项目编号不能为空");
        assertThatThrownBy(() -> registry.authorize("demo", "当前项目", tempDir.resolve("missing").toString()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("工作区路径必须是已存在目录");
    }

    @Test
    void 授权工作区拒绝文件路径() throws Exception {
        var registry = new WorkspaceRegistry();
        var file = Files.writeString(tempDir.resolve("README.md"), "说明");

        assertThatThrownBy(() -> registry.authorize("demo", "当前项目", file.toString()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("工作区路径必须是已存在目录");
    }

    @Test
    void 撤销后不能作为授权工作区使用() {
        var registry = new WorkspaceRegistry();
        var workspace = registry.authorize("demo", "当前项目", tempDir.toString());

        registry.revoke("demo", workspace.id());

        assertThat(registry.list("demo")).extracting("status").containsExactly(WorkspaceStatus.REVOKED);
        assertThatThrownBy(() -> registry.requireAuthorized("demo", workspace.id()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("工作区未授权");
    }

    @Test
    void 重建服务后恢复授权工作区和撤销状态() {
        var store = new InMemoryLocalExecutionStateStore();
        var registry = new WorkspaceRegistry(store);
        var workspace = registry.authorize("demo", "当前项目", tempDir.toString());
        registry.revoke("demo", workspace.id());

        var restored = new WorkspaceRegistry(store);

        assertThat(restored.list("demo")).hasSize(1);
        assertThat(restored.list("demo").getFirst().status()).isEqualTo(WorkspaceStatus.REVOKED);
    }
}
