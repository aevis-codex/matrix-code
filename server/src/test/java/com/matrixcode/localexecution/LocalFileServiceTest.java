package com.matrixcode.localexecution;

import com.matrixcode.localexecution.application.LocalFileService;
import com.matrixcode.localexecution.application.PathGuard;
import com.matrixcode.localexecution.application.WorkspaceRegistry;
import com.matrixcode.workbench.application.InMemoryWorkbenchStateStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class LocalFileServiceTest {

    @TempDir
    Path workspace;

    @Test
    void 可以列目录并读取文本文件() throws Exception {
        Files.createDirectories(workspace.resolve("docs"));
        Files.writeString(workspace.resolve("docs/readme.md"), "本地执行代理说明");
        var registry = new WorkspaceRegistry();
        var service = new LocalFileService(registry, new PathGuard());
        var authorized = registry.authorize("demo", "当前项目", workspace.toString());

        var entries = service.list("demo", authorized.id(), "docs");
        var read = service.read("demo", authorized.id(), "docs/readme.md");

        assertThat(entries).extracting("name").containsExactly("readme.md");
        assertThat(read.content()).isEqualTo("本地执行代理说明");
        assertThat(read.sizeBytes()).isGreaterThan(0);
    }

    @Test
    void 可以写入小型文本文件并记录摘要() throws Exception {
        Files.createDirectories(workspace.resolve("docs"));
        var registry = new WorkspaceRegistry();
        var service = new LocalFileService(registry, new PathGuard());
        var authorized = registry.authorize("demo", "当前项目", workspace.toString());

        var result = service.write("demo", authorized.id(), "docs/agent.md", "执行说明");

        assertThat(Files.readString(workspace.resolve("docs/agent.md"))).isEqualTo("执行说明");
        assertThat(result.bytesWritten()).isGreaterThan(0);
        assertThat(service.recentOperations("demo")).extracting("relativePath").contains("docs/agent.md");
    }

    @Test
    void 服务重建后恢复最近文件操作记录() throws Exception {
        Files.createDirectories(workspace.resolve("docs"));
        var store = new InMemoryWorkbenchStateStore();
        var registry = new WorkspaceRegistry();
        var authorized = registry.authorize("demo", "当前项目", workspace.toString());
        var firstService = new LocalFileService(registry, new PathGuard(), store);
        firstService.write("demo", authorized.id(), "docs/agent.md", "执行说明");

        var secondService = new LocalFileService(new WorkspaceRegistry(), new PathGuard(), store);

        assertThat(secondService.recentOperations("demo"))
                .extracting("relativePath")
                .containsExactly("docs/agent.md");
    }

    @Test
    void 拒绝读取大文件和二进制文件() throws Exception {
        Files.writeString(workspace.resolve("large.txt"), "甲".repeat(70000));
        Files.write(workspace.resolve("binary.bin"), new byte[] {0, 1, 2, 3});
        var registry = new WorkspaceRegistry();
        var service = new LocalFileService(registry, new PathGuard());
        var authorized = registry.authorize("demo", "当前项目", workspace.toString());

        assertThatThrownBy(() -> service.read("demo", authorized.id(), "large.txt"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("文件超过读取上限");
        assertThatThrownBy(() -> service.read("demo", authorized.id(), "binary.bin"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("不支持读取二进制文件");
    }

    @Test
    void 文件操作不能离开授权工作区() {
        var registry = new WorkspaceRegistry();
        var service = new LocalFileService(registry, new PathGuard());
        var authorized = registry.authorize("demo", "当前项目", workspace.toString());

        assertThatThrownBy(() -> service.read("demo", authorized.id(), "../secret.txt"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("路径不能离开授权工作区");
    }
}
