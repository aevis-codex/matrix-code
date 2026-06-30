package com.matrixcode.localexecution;

import com.matrixcode.localexecution.application.PathGuard;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PathGuardTest {

    @TempDir
    Path workspace;

    @TempDir
    Path outside;

    @Test
    void 相对路径会被解析到工作区内() throws Exception {
        Files.createDirectories(workspace.resolve("docs"));
        Files.writeString(workspace.resolve("docs/readme.md"), "内容");
        var guard = new PathGuard();

        var resolved = guard.resolveExisting(workspace.toString(), "docs/readme.md");

        assertThat(resolved).isEqualTo(workspace.resolve("docs/readme.md").toRealPath());
    }

    @Test
    void 拒绝绝对路径和路径穿越() {
        var guard = new PathGuard();

        assertThatThrownBy(() -> guard.resolveExisting(workspace.toString(), workspace.resolve("x").toString()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("只能使用相对路径");
        assertThatThrownBy(() -> guard.resolveExisting(workspace.toString(), "../secret.txt"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("路径不能离开授权工作区");
    }

    @Test
    void 拒绝符号链接逃逸() throws Exception {
        Files.writeString(outside.resolve("secret.txt"), "secret");
        Files.createSymbolicLink(workspace.resolve("link.txt"), outside.resolve("secret.txt"));
        var guard = new PathGuard();

        assertThatThrownBy(() -> guard.resolveExisting(workspace.toString(), "link.txt"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("路径不能离开授权工作区");
    }

    @Test
    void 写入新文件时父目录必须在工作区内() throws Exception {
        Files.createDirectories(workspace.resolve("docs"));
        var guard = new PathGuard();

        assertThat(guard.resolveWritable(workspace.toString(), "docs/new.md"))
                .isEqualTo(workspace.resolve("docs/new.md").toAbsolutePath().normalize());
        assertThatThrownBy(() -> guard.resolveWritable(workspace.toString(), "missing/new.md"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("父目录不存在");
    }
}
