package com.matrixcode.deployment.application;

import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

@Component
public class DockerComposeRuntimeClient implements ComposeRuntimeClient {

    private static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(30);
    private static final int LOG_LIMIT = 2000;

    private final String dockerExecutable;
    private final Duration timeout;

    public DockerComposeRuntimeClient() {
        this("docker", DEFAULT_TIMEOUT);
    }

    public DockerComposeRuntimeClient(String dockerExecutable) {
        this(dockerExecutable, DEFAULT_TIMEOUT);
    }

    DockerComposeRuntimeClient(String dockerExecutable, Duration timeout) {
        this.dockerExecutable = requireText(dockerExecutable, "Docker 可执行文件不能为空");
        this.timeout = timeout == null ? DEFAULT_TIMEOUT : timeout;
    }

    @Override
    public ComposeRuntimeResult validate(ComposeRuntimeRequest request) {
        return run(command(request, List.of("config")));
    }

    @Override
    public ComposeRuntimeResult start(ComposeRuntimeRequest request) {
        return run(command(request, List.of("up", "-d")));
    }

    @Override
    public ComposeRuntimeResult stop(ComposeRuntimeRequest request) {
        return run(command(request, List.of("stop")));
    }

    @Override
    public ComposeRuntimeResult logs(ComposeRuntimeRequest request) {
        return run(command(request, List.of("logs", "--tail", "80", request.serviceName())));
    }

    private List<String> command(ComposeRuntimeRequest request, List<String> action) {
        var command = new ArrayList<String>();
        command.add(dockerExecutable);
        command.add("compose");
        command.add("-f");
        command.add(request.composeFile().toString());
        command.add("-p");
        command.add(request.projectName());
        command.addAll(action);
        return command;
    }

    private ComposeRuntimeResult run(List<String> command) {
        Process process = null;
        Thread outputReader = null;
        var output = new StringBuilder();
        try {
            process = new ProcessBuilder(command)
                    .redirectErrorStream(true)
                    .start();
            outputReader = captureOutput(process, output);
            var completed = process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS);
            if (!completed) {
                destroyProcessTree(process);
                waitForOutput(outputReader);
                return ComposeRuntimeResult.failed("Docker Compose 命令超时", truncate(output(output)));
            }
            waitForOutput(outputReader);
            if (process.exitValue() == 0) {
                return ComposeRuntimeResult.succeeded("Docker Compose 命令执行成功", truncate(output(output)));
            }
            return ComposeRuntimeResult.failed("Docker Compose 命令执行失败，退出码：" + process.exitValue(), truncate(output(output)));
        } catch (IOException exception) {
            return ComposeRuntimeResult.failed("Docker Compose 不可用", truncate(exception.getMessage()));
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            if (process != null) {
                destroyProcessTreeQuietly(process);
            }
            waitForOutputQuietly(outputReader);
            return ComposeRuntimeResult.failed("Docker Compose 命令被中断", truncate(exception.getMessage()));
        }
    }

    private void destroyProcessTree(Process process) throws InterruptedException {
        var handle = process.toHandle();
        var descendants = handle.descendants().toList();
        descendants.forEach(ProcessHandle::destroyForcibly);
        handle.destroyForcibly();
        process.waitFor(1, TimeUnit.SECONDS);
        for (var descendant : descendants) {
            try {
                descendant.onExit().get(1, TimeUnit.SECONDS);
            } catch (ExecutionException | TimeoutException ignored) {
            }
        }
    }

    private void destroyProcessTreeQuietly(Process process) {
        try {
            destroyProcessTree(process);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
        }
    }

    private Thread captureOutput(Process process, StringBuilder output) {
        var reader = new Thread(() -> {
            try (var input = process.getInputStream()) {
                append(output, new String(input.readAllBytes(), StandardCharsets.UTF_8));
            } catch (IOException exception) {
                append(output, exception.getMessage());
            }
        }, "matrixcode-compose-output-reader");
        reader.setDaemon(true);
        reader.start();
        return reader;
    }

    private void waitForOutput(Thread outputReader) throws InterruptedException {
        if (outputReader != null) {
            outputReader.join(TimeUnit.SECONDS.toMillis(1));
        }
    }

    private void waitForOutputQuietly(Thread outputReader) {
        try {
            waitForOutput(outputReader);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
        }
    }

    private void append(StringBuilder output, String value) {
        if (value == null || value.isBlank()) {
            return;
        }
        synchronized (output) {
            output.append(value);
        }
    }

    private String output(StringBuilder output) {
        synchronized (output) {
            return output.toString();
        }
    }

    private String truncate(String value) {
        if (value == null || value.isBlank()) {
            return "";
        }
        var normalized = value.trim();
        if (normalized.length() <= LOG_LIMIT) {
            return normalized;
        }
        return normalized.substring(0, LOG_LIMIT);
    }

    private static String requireText(String value, String message) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(message);
        }
        return value.trim();
    }
}
