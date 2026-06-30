package com.matrixcode.localexecution;

import com.matrixcode.localexecution.application.LocalGitDiffService;
import com.matrixcode.localexecution.application.WorkspaceRegistry;
import com.matrixcode.workbench.application.InMemoryWorkbenchStateStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

class LocalGitDiffServiceTest {

    @TempDir
    Path workspace;

    @Test
    void Git仓库返回变更文件和统计摘要() throws Exception {
        run("git", "init");
        run("git", "config", "user.email", "matrixcode@example.com");
        run("git", "config", "user.name", "MatrixCode Test");
        Files.writeString(workspace.resolve("README.md"), "初始内容\n");
        run("git", "add", "README.md");
        run("git", "commit", "-m", "init");
        Files.writeString(workspace.resolve("README.md"), "初始内容\n新增内容\n");
        var registry = new WorkspaceRegistry();
        var service = new LocalGitDiffService(registry);
        var authorized = registry.authorize("demo", "当前项目", workspace.toString());

        var summary = service.capture("demo", authorized.id());

        assertThat(summary.repository()).isTrue();
        assertThat(summary.changedFiles()).containsExactly("README.md");
        assertThat(summary.stat()).contains("README.md");
    }

    @Test
    void 非Git目录返回非仓库状态() {
        var registry = new WorkspaceRegistry();
        var service = new LocalGitDiffService(registry);
        var authorized = registry.authorize("demo", "当前项目", workspace.toString());

        var summary = service.capture("demo", authorized.id());

        assertThat(summary.repository()).isFalse();
        assertThat(summary.changedFiles()).isEmpty();
    }

    @Test
    void 服务重建后恢复最近GitDiff摘要() {
        var store = new InMemoryWorkbenchStateStore();
        var registry = new WorkspaceRegistry();
        var authorized = registry.authorize("demo", "当前项目", workspace.toString());
        var firstService = new LocalGitDiffService(registry, store);
        var summary = firstService.capture("demo", authorized.id());

        var secondService = new LocalGitDiffService(new WorkspaceRegistry(), store);

        assertThat(secondService.latest("demo")).isEqualTo(summary);
    }

    @Test
    void GitWorktree的Git文件形态也会被识别为仓库() throws Exception {
        var mainRepository = workspace.resolve("main");
        var linkedWorktree = workspace.resolve("linked");
        Files.createDirectories(mainRepository);
        run(mainRepository, "git", "init");
        run(mainRepository, "git", "config", "user.email", "matrixcode@example.com");
        run(mainRepository, "git", "config", "user.name", "MatrixCode Test");
        Files.writeString(mainRepository.resolve("README.md"), "初始内容\n");
        run(mainRepository, "git", "add", "README.md");
        run(mainRepository, "git", "commit", "-m", "init");
        run(mainRepository, "git", "worktree", "add", linkedWorktree.toString());
        Files.writeString(linkedWorktree.resolve("README.md"), "初始内容\nworktree 修改\n");
        var registry = new WorkspaceRegistry();
        var service = new LocalGitDiffService(registry);
        var authorized = registry.authorize("demo", "当前项目", linkedWorktree.toString());

        var summary = service.capture("demo", authorized.id());

        assertThat(Files.isRegularFile(linkedWorktree.resolve(".git"))).isTrue();
        assertThat(summary.repository()).isTrue();
        assertThat(summary.changedFiles()).containsExactly("README.md");
    }

    private void run(String... command) throws Exception {
        run(workspace, command);
    }

    private void run(Path directory, String... command) throws Exception {
        var process = new ProcessBuilder(command)
                .directory(directory.toFile())
                .redirectErrorStream(true)
                .start();
        assertThat(process.waitFor(10, TimeUnit.SECONDS)).isTrue();
        assertThat(process.exitValue()).isEqualTo(0);
    }
}
