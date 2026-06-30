package com.matrixcode.deployment;

import com.matrixcode.deployment.application.ComposeRuntimeRequest;
import com.matrixcode.deployment.application.DockerComposeRuntimeClient;
import com.matrixcode.deployment.domain.ComposeOperationStatus;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

class DockerComposeRuntimeClientTest {

    @TempDir
    Path workspace;

    @Test
    void Docker命令不可用时返回失败结果而不是抛出异常() throws Exception {
        var composeFile = workspace.resolve("compose.yml");
        Files.writeString(composeFile, "services:\n  web:\n    image: nginx:alpine\n");
        var client = new DockerComposeRuntimeClient("definitely-missing-docker-binary-for-matrixcode");

        var result = client.validate(new ComposeRuntimeRequest(composeFile, "matrixcode-demo", "web"));

        assertThat(result.status()).isEqualTo(ComposeOperationStatus.FAILED);
        assertThat(result.summary()).contains("Docker Compose 不可用");
        assertThat(result.logExcerpt()).contains("definitely-missing-docker-binary-for-matrixcode");
    }

    @Test
    void Compose命令超过超时时间会终止并返回失败结果() throws Exception {
        var composeFile = workspace.resolve("compose.yml");
        Files.writeString(composeFile, "services:\n  web:\n    image: nginx:alpine\n");
        var sleepyDocker = workspace.resolve("sleepy-docker.sh");
        Files.writeString(sleepyDocker, "#!/bin/sh\nsleep 5\n");
        assertThat(sleepyDocker.toFile().setExecutable(true)).isTrue();
        var client = dockerClient(sleepyDocker, Duration.ofMillis(50));

        var result = assertTimeoutPreemptively(Duration.ofSeconds(2),
                () -> client.start(new ComposeRuntimeRequest(composeFile, "matrixcode-demo", "web")));

        assertThat(result.status()).isEqualTo(ComposeOperationStatus.FAILED);
        assertThat(result.summary()).contains("Docker Compose 命令超时");
    }

    @Test
    void Compose命令超时时会一并终止子进程() throws Exception {
        var composeFile = workspace.resolve("compose.yml");
        Files.writeString(composeFile, "services:\n  web:\n    image: nginx:alpine\n");
        var childPidFile = workspace.resolve("child.pid");
        var sleepyDocker = workspace.resolve("sleepy-child-docker.sh");
        Files.writeString(sleepyDocker, "#!/bin/sh\nsleep 10 &\necho $! > %s\nwait\n"
                .formatted(shellQuote(childPidFile.toString())));
        assertThat(sleepyDocker.toFile().setExecutable(true)).isTrue();
        var client = dockerClient(sleepyDocker, Duration.ofSeconds(2));

        var result = assertTimeoutPreemptively(Duration.ofSeconds(6),
                () -> client.start(new ComposeRuntimeRequest(composeFile, "matrixcode-demo", "web")));
        var childPid = waitForChildPid(childPidFile);

        try {
            assertThat(eventuallyProcessExits(childPid)).isTrue();
        } finally {
            ProcessHandle.of(childPid).ifPresent(ProcessHandle::destroyForcibly);
        }
        assertThat(result.status()).isEqualTo(ComposeOperationStatus.FAILED);
        assertThat(result.summary()).contains("Docker Compose 命令超时");
    }

    private DockerComposeRuntimeClient dockerClient(Path executable, Duration timeout) throws Exception {
        var constructor = DockerComposeRuntimeClient.class.getDeclaredConstructor(String.class, Duration.class);
        constructor.setAccessible(true);
        return constructor.newInstance(executable.toString(), timeout);
    }

    private long waitForChildPid(Path childPidFile) throws Exception {
        for (int attempt = 0; attempt < 20; attempt++) {
            if (Files.exists(childPidFile)) {
                return Long.parseLong(Files.readString(childPidFile).trim());
            }
            Thread.sleep(50);
        }
        throw new AssertionError("子进程 PID 未写入");
    }

    private boolean eventuallyProcessExits(long pid) throws InterruptedException {
        for (int attempt = 0; attempt < 20; attempt++) {
            var alive = ProcessHandle.of(pid).map(ProcessHandle::isAlive).orElse(false);
            if (!alive) {
                return true;
            }
            Thread.sleep(50);
        }
        return false;
    }

    private String shellQuote(String value) {
        return "'" + value.replace("'", "'\\''") + "'";
    }
}
