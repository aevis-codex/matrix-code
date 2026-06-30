package com.matrixcode.localexecution.application;

import com.matrixcode.approval.domain.ApprovalDecision;
import com.matrixcode.localexecution.domain.ExecutionTask;
import com.matrixcode.localexecution.domain.ExecutionTaskStatus;
import com.matrixcode.localexecution.domain.LocalTaskLogStream;
import com.matrixcode.realtime.application.ProjectEventBus;
import com.matrixcode.realtime.domain.ProjectEvent;
import jakarta.annotation.PreDestroy;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

@Service
public class LocalTaskQueueService {

    private static final int OUTPUT_SUMMARY_LIMIT = 4096;
    private static final Duration DEFAULT_COMMAND_TIMEOUT = Duration.ofSeconds(60);
    private static final int COMMAND_WORKERS = 4;
    private static final int STREAM_WORKERS = COMMAND_WORKERS * 2;
    private static final int ACCEPTED_TASK_LIMIT = 200;
    private static final AtomicInteger THREAD_COUNTER = new AtomicInteger();

    private final WorkspaceRegistry workspaces;
    private final LocalTaskStore tasks;
    private final ProjectEventBus events;
    private final ExecutorService taskExecutor;
    private final ExecutorService streamExecutor;
    private final Duration commandTimeout;
    private final int acceptedTaskLimit;
    private final Object acceptedTasksLock = new Object();
    private final ConcurrentHashMap<TaskKey, Process> runningProcesses = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<TaskKey, Boolean> scheduledTasks = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<TaskKey, Boolean> acceptedTasks = new ConcurrentHashMap<>();
    private final ArrayDeque<TaskKey> acceptedTaskOrder = new ArrayDeque<>();

    @Autowired
    public LocalTaskQueueService(WorkspaceRegistry workspaces, LocalTaskStore tasks, ProjectEventBus events) {
        this(workspaces, tasks, events, COMMAND_WORKERS, DEFAULT_COMMAND_TIMEOUT);
    }

    LocalTaskQueueService(WorkspaceRegistry workspaces, LocalTaskStore tasks, ProjectEventBus events, int commandWorkers) {
        this(workspaces, tasks, events, commandWorkers, DEFAULT_COMMAND_TIMEOUT);
    }

    LocalTaskQueueService(
            WorkspaceRegistry workspaces,
            LocalTaskStore tasks,
            ProjectEventBus events,
            int commandWorkers,
            Duration commandTimeout
    ) {
        this(workspaces, tasks, events, commandWorkers, commandTimeout, ACCEPTED_TASK_LIMIT);
    }

    LocalTaskQueueService(
            WorkspaceRegistry workspaces,
            LocalTaskStore tasks,
            ProjectEventBus events,
            int commandWorkers,
            Duration commandTimeout,
            int acceptedTaskLimit
    ) {
        this.workspaces = Objects.requireNonNull(workspaces, "workspaces 不能为空");
        this.tasks = Objects.requireNonNull(tasks, "tasks 不能为空");
        this.events = Objects.requireNonNull(events, "events 不能为空");
        if (commandWorkers <= 0) {
            throw new IllegalArgumentException("队列执行线程数必须为正数");
        }
        this.commandTimeout = requirePositiveDuration(commandTimeout);
        this.acceptedTaskLimit = requirePositiveLimit(acceptedTaskLimit);
        this.taskExecutor = Executors.newFixedThreadPool(commandWorkers, daemonThreadFactory("local-task-queue-"));
        this.streamExecutor = Executors.newFixedThreadPool(streamWorkersFor(commandWorkers), daemonThreadFactory("local-task-stream-"));
    }

    @PreDestroy
    public void shutdown() {
        List.copyOf(runningProcesses.values()).forEach(this::stopProcess);
        runningProcesses.clear();
        scheduledTasks.clear();
        clearAcceptedTasks();
        taskExecutor.shutdownNow();
        streamExecutor.shutdownNow();
        awaitTermination(taskExecutor);
        awaitTermination(streamExecutor);
    }

    public ExecutionTask enqueue(
            String projectId,
            String workspaceId,
            String actorId,
            String command,
            ApprovalDecision decision
    ) {
        var workspace = workspaces.requireAuthorized(projectId, workspaceId);
        return enqueue(UUID.randomUUID().toString(), projectId, workspaceId, actorId, command, decision, workspace.rootPath());
    }

    public ExecutionTask enqueue(
            String taskId,
            String projectId,
            String workspaceId,
            String actorId,
            String command,
            ApprovalDecision decision
    ) {
        var workspace = workspaces.requireAuthorized(projectId, workspaceId);
        return enqueue(taskId, projectId, workspaceId, actorId, command, decision, workspace.rootPath());
    }

    public ExecutionTask enqueueExisting(ExecutionTask task, String rootPath) {
        var queued = Objects.requireNonNull(task, "task 不能为空");
        if (queued.status() != ExecutionTaskStatus.QUEUED) {
            throw new IllegalArgumentException("任务必须处于队列状态");
        }
        requireApproved(queued.approvalDecision());
        var key = new TaskKey(queued.projectId(), queued.taskId());
        acquireSchedule(key);
        var accepted = false;
        try {
            rejectConflictingExistingTask(queued);
            rejectAcceptedTask(key);
            var authorizedRoot = validateExecutionRoot(queued.projectId(), queued.workspaceId(), rootPath);

            recordQueuedTask(queued);
            tasks.appendLog(queued.projectId(), queued.taskId(), LocalTaskLogStream.SYSTEM, "任务已进入队列");
            publish(queued.projectId(), "LOCAL_COMMAND_QUEUED", "本地命令已进入队列：" + queued.command());
            acceptTask(key);
            accepted = true;
            taskExecutor.submit(() -> runQueuedTask(queued, authorizedRoot));
            return queued;
        } catch (RuntimeException exception) {
            releaseSchedule(key);
            if (accepted) {
                releaseAcceptedTask(key);
            }
            markScheduleFailure(queued, exception);
            throw exception;
        }
    }

    private ExecutionTask recordQueuedTask(ExecutionTask queued) {
        try {
            return tasks.recordNew(queued);
        } catch (IllegalArgumentException exception) {
            if (!"执行任务已存在".equals(exception.getMessage())) {
                throw exception;
            }
            var existing = tasks.require(queued.projectId(), queued.taskId());
            if (existing.equals(queued)) {
                return existing;
            }
            throw exception;
        }
    }

    private void rejectConflictingExistingTask(ExecutionTask queued) {
        try {
            var existing = tasks.require(queued.projectId(), queued.taskId());
            if (!existing.equals(queued)) {
                throw new IllegalArgumentException("执行任务已存在");
            }
        } catch (IllegalArgumentException exception) {
            if (!"执行任务不存在".equals(exception.getMessage())) {
                throw exception;
            }
        }
    }

    Path validateExecutionRoot(String projectId, String workspaceId, String rootPath) {
        var workspace = workspaces.requireAuthorized(projectId, workspaceId);
        var authorizedRoot = requireDirectory(workspace.rootPath());
        var requestedRoot = requireDirectory(rootPath);
        if (!requestedRoot.equals(authorizedRoot)) {
            throw new IllegalArgumentException("任务工作区路径与授权工作区不一致");
        }
        return authorizedRoot;
    }

    private void acquireSchedule(TaskKey key) {
        if (scheduledTasks.putIfAbsent(key, true) != null) {
            throw new IllegalArgumentException("任务已进入队列，不能重复提交");
        }
    }

    private void rejectAcceptedTask(TaskKey key) {
        synchronized (acceptedTasksLock) {
            if (acceptedTasks.containsKey(key)) {
                throw new IllegalArgumentException("执行任务已存在");
            }
        }
    }

    private void acceptTask(TaskKey key) {
        synchronized (acceptedTasksLock) {
            if (acceptedTasks.putIfAbsent(key, true) != null) {
                throw new IllegalArgumentException("执行任务已存在");
            }
            acceptedTaskOrder.addLast(key);
            trimAcceptedTasks();
        }
    }

    private void trimAcceptedTasks() {
        while (acceptedTasks.size() > acceptedTaskLimit && !acceptedTaskOrder.isEmpty()) {
            var oldest = acceptedTaskOrder.removeFirst();
            acceptedTasks.remove(oldest);
        }
    }

    private void releaseAcceptedTask(TaskKey key) {
        synchronized (acceptedTasksLock) {
            if (acceptedTasks.remove(key) != null) {
                acceptedTaskOrder.remove(key);
            }
        }
    }

    private void clearAcceptedTasks() {
        synchronized (acceptedTasksLock) {
            acceptedTasks.clear();
            acceptedTaskOrder.clear();
        }
    }

    int acceptedTaskMarkerCount() {
        synchronized (acceptedTasksLock) {
            return acceptedTasks.size();
        }
    }

    private void releaseSchedule(TaskKey key) {
        scheduledTasks.remove(key);
    }

    private void markScheduleFailure(ExecutionTask queued, RuntimeException exception) {
        try {
            var markedFailed = new AtomicBoolean(false);
            tasks.replace(queued.projectId(), queued.taskId(), current -> {
                if (current.status() != ExecutionTaskStatus.QUEUED || !current.equals(queued)) {
                    return current;
                }
                markedFailed.set(true);
                return copyTask(
                        current,
                        ExecutionTaskStatus.FAILED,
                        -1,
                        current.stdoutSummary(),
                        mergeSummary(current.stderrSummary(), "任务提交执行队列失败：" + exception.getMessage()),
                        current.durationMillis(),
                        current.approverId(),
                        current.approvalNote(),
                        current.decidedAt(),
                        current.safetyRejectionReason(),
                        current.canceledBy(),
                        current.cancelNote(),
                        current.canceledAt()
                );
            });
            if (markedFailed.get()) {
                tasks.appendLog(queued.projectId(), queued.taskId(), LocalTaskLogStream.SYSTEM, "任务提交执行队列失败");
                publish(queued.projectId(), "LOCAL_COMMAND_FAILED", "任务提交执行队列失败");
            }
        } catch (IllegalArgumentException ignored) {
            // 任务可能在并发路径中已被移除或终止，原始异常会继续向调用方抛出。
        }
    }

    public ExecutionTask cancel(String projectId, String taskId, String actorId, String note) {
        var targetProjectId = requireText(projectId, "项目编号不能为空");
        var targetTaskId = requireText(taskId, "任务编号不能为空");
        var cancelActorId = requireText(actorId, "取消人不能为空");
        var cancelNote = normalizeNote(note);
        var canceledAt = Instant.now();

        var canceled = tasks.replace(targetProjectId, targetTaskId, current -> {
            if (isTerminal(current.status())) {
                throw new IllegalArgumentException("任务已结束，不能取消");
            }
            if (current.status() != ExecutionTaskStatus.QUEUED && current.status() != ExecutionTaskStatus.RUNNING) {
                throw new IllegalArgumentException("任务未进入执行队列，不能取消");
            }
            return copyTask(
                    current,
                    ExecutionTaskStatus.CANCELED,
                    current.exitCode(),
                    current.stdoutSummary(),
                    current.stderrSummary(),
                    current.durationMillis(),
                    current.approverId(),
                    current.approvalNote(),
                    current.decidedAt(),
                    current.safetyRejectionReason(),
                    cancelActorId,
                    cancelNote,
                    canceledAt
            );
        });

        var process = runningProcesses.remove(new TaskKey(targetProjectId, targetTaskId));
        if (process != null) {
            stopProcess(process);
        }
        var message = cancelNote.isBlank() ? "任务已取消" : "任务已取消：" + cancelNote;
        tasks.appendLog(targetProjectId, targetTaskId, LocalTaskLogStream.SYSTEM, message);
        publish(targetProjectId, "LOCAL_COMMAND_CANCELED", message);
        return canceled;
    }

    private ExecutionTask enqueue(
            String taskId,
            String projectId,
            String workspaceId,
            String actorId,
            String command,
            ApprovalDecision decision,
            String rootPath
    ) {
        var queued = new ExecutionTask(
                requireText(taskId, "任务编号不能为空"),
                requireText(projectId, "项目编号不能为空"),
                requireText(workspaceId, "工作区编号不能为空"),
                requireText(actorId, "执行人不能为空"),
                "SHELL",
                requireText(command, "命令不能为空"),
                Objects.requireNonNull(decision, "审批结果不能为空"),
                ExecutionTaskStatus.QUEUED,
                null,
                "",
                "",
                0,
                Instant.now()
        );
        return enqueueExisting(queued, rootPath);
    }

    private void runQueuedTask(ExecutionTask queued, Path root) {
        var key = new TaskKey(queued.projectId(), queued.taskId());
        var startedAt = Instant.now();
        var stdout = new OutputSummary();
        var stderr = new OutputSummary();
        Process process = null;
        Future<?> stdoutReader = null;
        Future<?> stderrReader = null;
        try {
            if (!markRunning(queued)) {
                return;
            }
            releaseSchedule(key);
            if (isCanceled(queued.projectId(), queued.taskId())) {
                return;
            }
            process = new ProcessBuilder(tokensOf(queued.command()))
                    .directory(root.toFile())
                    .start();
            runningProcesses.put(key, process);
            if (isCanceled(queued.projectId(), queued.taskId())) {
                stopProcess(process);
                return;
            }

            var startedProcess = process;
            stdoutReader = streamExecutor.submit(() -> captureOutput(queued, startedProcess.getInputStream(), LocalTaskLogStream.STDOUT, stdout));
            stderrReader = streamExecutor.submit(() -> captureOutput(queued, startedProcess.getErrorStream(), LocalTaskLogStream.STDERR, stderr));

            var finished = process.waitFor(commandTimeout.toMillis(), TimeUnit.MILLISECONDS);
            if (!finished) {
                tasks.appendLog(queued.projectId(), queued.taskId(), LocalTaskLogStream.SYSTEM, "任务执行超时，已终止");
                stopProcess(process);
                waitForOutput(stdoutReader);
                waitForOutput(stderrReader);
                finishIfActive(
                        queued,
                        ExecutionTaskStatus.FAILED,
                        -1,
                        stdout.value(),
                        mergeSummary(stderr.value(), "命令执行超时"),
                        Duration.between(startedAt, Instant.now()).toMillis(),
                        "任务运行失败：命令执行超时",
                        "LOCAL_COMMAND_FAILED"
                );
                return;
            }

            waitForOutput(stdoutReader);
            waitForOutput(stderrReader);
            var exitCode = process.exitValue();
            var status = exitCode == 0 ? ExecutionTaskStatus.SUCCESS : ExecutionTaskStatus.FAILED;
            finishIfActive(
                    queued,
                    status,
                    exitCode,
                    stdout.value(),
                    stderr.value(),
                    Duration.between(startedAt, Instant.now()).toMillis(),
                    exitCode == 0 ? "任务运行完成，退出码：0" : "任务运行失败，退出码：" + exitCode,
                    exitCode == 0 ? "LOCAL_COMMAND_COMPLETED" : "LOCAL_COMMAND_FAILED"
            );
        } catch (IOException exception) {
            finishIfActive(
                    queued,
                    ExecutionTaskStatus.FAILED,
                    -1,
                    stdout.value(),
                    mergeSummary(stderr.value(), "命令无法启动：" + exception.getMessage()),
                    Duration.between(startedAt, Instant.now()).toMillis(),
                    "任务运行失败：命令无法启动",
                    "LOCAL_COMMAND_FAILED"
            );
        } catch (InterruptedException exception) {
            if (process != null) {
                stopProcess(process);
            }
            Thread.currentThread().interrupt();
            finishIfActive(
                    queued,
                    ExecutionTaskStatus.FAILED,
                    -1,
                    stdout.value(),
                    mergeSummary(stderr.value(), "命令执行被中断"),
                    Duration.between(startedAt, Instant.now()).toMillis(),
                    "任务运行失败：命令执行被中断",
                    "LOCAL_COMMAND_FAILED"
            );
        } finally {
            releaseSchedule(key);
            if (process != null) {
                runningProcesses.remove(key, process);
            }
        }
    }

    private boolean markRunning(ExecutionTask queued) {
        var transitioned = new AtomicBoolean(false);
        var running = tasks.replace(queued.projectId(), queued.taskId(), current -> {
            if (current.status() != ExecutionTaskStatus.QUEUED || !current.equals(queued)) {
                return current;
            }
            transitioned.set(true);
            return copyTask(
                    current,
                    ExecutionTaskStatus.RUNNING,
                    null,
                    "",
                    "",
                    0,
                    current.approverId(),
                    current.approvalNote(),
                    current.decidedAt(),
                    current.safetyRejectionReason(),
                    current.canceledBy(),
                    current.cancelNote(),
                    current.canceledAt()
            );
        });
        if (!transitioned.get() || running.status() != ExecutionTaskStatus.RUNNING) {
            return false;
        }
        tasks.appendLog(queued.projectId(), queued.taskId(), LocalTaskLogStream.SYSTEM, "任务开始运行");
        publish(queued.projectId(), "LOCAL_COMMAND_STARTED", "本地命令开始运行：" + queued.command());
        return true;
    }

    private void finishIfActive(
            ExecutionTask queued,
            ExecutionTaskStatus status,
            Integer exitCode,
            String stdoutSummary,
            String stderrSummary,
            long durationMillis,
            String systemMessage,
            String eventType
    ) {
        var finished = tasks.replace(queued.projectId(), queued.taskId(), current -> {
            if (current.status() == ExecutionTaskStatus.CANCELED || isTerminal(current.status())) {
                return current;
            }
            return copyTask(
                    current,
                    status,
                    exitCode,
                    summarize(stdoutSummary),
                    summarize(stderrSummary),
                    durationMillis,
                    current.approverId(),
                    current.approvalNote(),
                    current.decidedAt(),
                    current.safetyRejectionReason(),
                    current.canceledBy(),
                    current.cancelNote(),
                    current.canceledAt()
            );
        });
        if (finished.status() != status) {
            return;
        }
        tasks.appendLog(queued.projectId(), queued.taskId(), LocalTaskLogStream.SYSTEM, systemMessage);
        publish(queued.projectId(), eventType, systemMessage);
    }

    private void captureOutput(
            ExecutionTask task,
            InputStream input,
            LocalTaskLogStream stream,
            OutputSummary summary
    ) {
        try (var reader = new BufferedReader(new InputStreamReader(input, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                summary.append(line);
                tasks.appendLog(task.projectId(), task.taskId(), stream, line);
            }
        } catch (IOException exception) {
            tasks.appendLog(task.projectId(), task.taskId(), LocalTaskLogStream.SYSTEM,
                    "读取任务输出失败：" + exception.getMessage());
        }
    }

    private void waitForOutput(Future<?> future) throws InterruptedException {
        if (future == null) {
            return;
        }
        try {
            future.get(5, TimeUnit.SECONDS);
        } catch (ExecutionException ignored) {
            // captureOutput 已将可见失败写入任务日志，这里只负责等待输出线程结束。
        } catch (TimeoutException exception) {
            future.cancel(true);
        }
    }

    private boolean isCanceled(String projectId, String taskId) {
        try {
            return tasks.require(projectId, taskId).status() == ExecutionTaskStatus.CANCELED;
        } catch (IllegalArgumentException exception) {
            return false;
        }
    }

    private void stopProcess(Process process) {
        var root = process.toHandle();
        var handles = new ArrayList<ProcessHandle>();
        handles.addAll(root.descendants()
                .sorted(Comparator.comparingLong((ProcessHandle handle) -> handle.descendants().count()).reversed())
                .toList());
        handles.forEach(ProcessHandle::destroy);
        root.destroy();
        handles.add(root);
        waitForHandles(handles, Duration.ofSeconds(2));
        handles.stream()
                .filter(ProcessHandle::isAlive)
                .forEach(ProcessHandle::destroyForcibly);
        waitForHandles(handles, Duration.ofSeconds(2));
    }

    private void waitForHandles(List<ProcessHandle> handles, Duration timeout) {
        var deadline = System.nanoTime() + timeout.toNanos();
        for (var handle : handles) {
            if (!handle.isAlive()) {
                continue;
            }
            var remaining = deadline - System.nanoTime();
            if (remaining <= 0) {
                return;
            }
            try {
                handle.onExit().get(remaining, TimeUnit.NANOSECONDS);
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                return;
            } catch (ExecutionException | TimeoutException ignored) {
                return;
            }
        }
    }

    private ThreadFactory daemonThreadFactory(String prefix) {
        return runnable -> {
            var thread = new Thread(runnable, prefix + THREAD_COUNTER.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        };
    }

    private int streamWorkersFor(int commandWorkers) {
        if (commandWorkers == COMMAND_WORKERS) {
            return STREAM_WORKERS;
        }
        return Math.max(1, commandWorkers * 2);
    }

    private void awaitTermination(ExecutorService executor) {
        try {
            executor.awaitTermination(5, TimeUnit.SECONDS);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
        }
    }

    private void requireApproved(ApprovalDecision decision) {
        if (Objects.requireNonNull(decision, "审批结果不能为空") != ApprovalDecision.ALLOW) {
            throw new IllegalArgumentException("只有已批准任务可以进入执行队列");
        }
    }

    private Path requireDirectory(String rootPath) {
        var root = Path.of(requireText(rootPath, "工作区路径不能为空")).toAbsolutePath().normalize();
        if (!Files.isDirectory(root)) {
            throw new IllegalArgumentException("工作区路径必须是已存在目录");
        }
        try {
            return root.toRealPath();
        } catch (IOException exception) {
            throw new IllegalArgumentException("工作区路径必须是已存在目录");
        }
    }

    private void publish(String projectId, String type, String message) {
        events.publish(new ProjectEvent(projectId, type, message));
    }

    private List<String> tokensOf(String command) {
        return Arrays.stream(requireText(command, "命令不能为空").split("\\s+"))
                .toList();
    }

    private Duration requirePositiveDuration(Duration timeout) {
        var duration = Objects.requireNonNull(timeout, "命令超时时间不能为空");
        if (duration.isZero() || duration.isNegative()) {
            throw new IllegalArgumentException("命令超时时间必须为正数");
        }
        return duration;
    }

    private int requirePositiveLimit(int limit) {
        if (limit <= 0) {
            throw new IllegalArgumentException("已接收任务标记上限必须为正数");
        }
        return limit;
    }

    private ExecutionTask copyTask(
            ExecutionTask task,
            ExecutionTaskStatus status,
            Integer exitCode,
            String stdoutSummary,
            String stderrSummary,
            long durationMillis,
            String approverId,
            String approvalNote,
            Instant decidedAt,
            String safetyRejectionReason,
            String canceledBy,
            String cancelNote,
            Instant canceledAt
    ) {
        return new ExecutionTask(
                task.taskId(),
                task.projectId(),
                task.workspaceId(),
                task.actorId(),
                task.toolType(),
                task.command(),
                task.approvalDecision(),
                status,
                exitCode,
                stdoutSummary,
                stderrSummary,
                durationMillis,
                task.createdAt(),
                approverId,
                approvalNote,
                decidedAt,
                safetyRejectionReason,
                canceledBy,
                cancelNote,
                canceledAt
        );
    }

    private boolean isTerminal(ExecutionTaskStatus status) {
        return status == ExecutionTaskStatus.DENIED
                || status == ExecutionTaskStatus.SUCCESS
                || status == ExecutionTaskStatus.FAILED
                || status == ExecutionTaskStatus.CANCELED;
    }

    private String mergeSummary(String existing, String message) {
        var normalizedExisting = existing == null ? "" : existing;
        if (normalizedExisting.isBlank()) {
            return summarize(message);
        }
        return summarize(normalizedExisting + System.lineSeparator() + message);
    }

    private String summarize(String output) {
        if (output == null) {
            return "";
        }
        if (output.length() <= OUTPUT_SUMMARY_LIMIT) {
            return output;
        }
        return output.substring(0, OUTPUT_SUMMARY_LIMIT - 3) + "...";
    }

    private String normalizeNote(String note) {
        return note == null ? "" : note.trim();
    }

    private static String requireText(String value, String message) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(message);
        }
        return value.trim();
    }

    private record TaskKey(String projectId, String taskId) {
    }

    private static final class OutputSummary {
        private final StringBuilder builder = new StringBuilder();
        private boolean truncated;

        synchronized void append(String line) {
            if (truncated) {
                return;
            }
            var addition = (line == null ? "" : line) + System.lineSeparator();
            if (builder.length() + addition.length() <= OUTPUT_SUMMARY_LIMIT) {
                builder.append(addition);
                return;
            }

            var targetContentLength = OUTPUT_SUMMARY_LIMIT - 3;
            if (builder.length() > targetContentLength) {
                builder.setLength(targetContentLength);
            }
            var remaining = targetContentLength - builder.length();
            if (remaining > 0) {
                builder.append(addition, 0, Math.min(remaining, addition.length()));
            }
            builder.append("...");
            truncated = true;
        }

        synchronized String value() {
            return builder.toString();
        }
    }
}
