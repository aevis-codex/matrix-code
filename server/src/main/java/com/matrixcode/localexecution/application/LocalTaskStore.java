package com.matrixcode.localexecution.application;

import com.matrixcode.localexecution.domain.ExecutionTask;
import com.matrixcode.localexecution.domain.ExecutionTaskStatus;
import com.matrixcode.localexecution.domain.LocalTaskLog;
import com.matrixcode.localexecution.domain.LocalTaskLogStream;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;

@Service
public class LocalTaskStore {

    private static final int HISTORY_LIMIT = 20;
    private static final int LOG_LIMIT_PER_TASK = 200;
    private static final int RECENT_LOG_LIMIT = 20;
    private static final int LOG_CONTENT_LIMIT = 4096;

    private final Map<String, ProjectTaskState> projects = new ConcurrentHashMap<>();
    private final LocalExecutionStateStore store;

    public LocalTaskStore() {
        this(new InMemoryLocalExecutionStateStore());
    }

    @Autowired
    public LocalTaskStore(LocalExecutionStateStore store) {
        this.store = Objects.requireNonNull(store, "store 不能为空");
        restore(store.load());
    }

    public ExecutionTask record(ExecutionTask task) {
        var state = projects.computeIfAbsent(task.projectId(), ignored -> new ProjectTaskState());
        synchronized (state) {
            state.tasks.removeIf(existing -> existing.taskId().equals(task.taskId()));
            state.tasks.addFirst(task);
            trimHistory(state);
        }
        persist();
        return task;
    }

    public ExecutionTask recordNew(ExecutionTask task) {
        var state = projects.computeIfAbsent(task.projectId(), ignored -> new ProjectTaskState());
        synchronized (state) {
            if (state.tasks.stream().anyMatch(existing -> existing.taskId().equals(task.taskId()))) {
                throw new IllegalArgumentException("执行任务已存在");
            }
            state.tasks.addFirst(task);
            trimHistory(state);
        }
        persist();
        return task;
    }

    public ExecutionTask replace(String projectId, String taskId, Function<ExecutionTask, ExecutionTask> replacementFactory) {
        var state = stateFor(projectId);
        ExecutionTask replacement;
        synchronized (state) {
            var updated = new ArrayDeque<ExecutionTask>();
            replacement = null;
            for (var existing : state.tasks) {
                if (replacement == null && existing.taskId().equals(taskId)) {
                    replacement = replacementFactory.apply(existing);
                    validateReplacement(projectId, taskId, replacement);
                } else {
                    updated.addLast(existing);
                }
            }
            if (replacement == null) {
                throw new IllegalArgumentException("执行任务不存在");
            }
            state.tasks.clear();
            state.tasks.addFirst(replacement);
            state.tasks.addAll(updated);
            trimHistory(state);
        }
        persist();
        return replacement;
    }

    public ExecutionTask require(String projectId, String taskId) {
        var state = stateFor(projectId);
        synchronized (state) {
            return requireTask(state, taskId);
        }
    }

    public List<ExecutionTask> recentTasks(String projectId) {
        var state = projects.get(projectId);
        if (state == null) {
            return List.of();
        }
        synchronized (state) {
            return List.copyOf(state.tasks);
        }
    }

    public Map<String, List<ExecutionTask>> recentTasksByAllProjects() {
        var snapshot = new HashMap<String, List<ExecutionTask>>();
        projects.forEach((projectId, state) -> {
            synchronized (state) {
                snapshot.put(projectId, List.copyOf(state.tasks));
            }
        });
        return Map.copyOf(snapshot);
    }

    public List<ExecutionTask> activeTasks(String projectId) {
        return recentTasks(projectId).stream()
                .filter(task -> isActive(task.status()))
                .toList();
    }

    public LocalTaskLog appendLog(String projectId, String taskId, LocalTaskLogStream stream, String content) {
        var state = stateFor(projectId);
        LocalTaskLog log;
        synchronized (state) {
            requireTask(state, taskId);
            log = new LocalTaskLog(
                    UUID.randomUUID().toString(),
                    projectId,
                    taskId,
                    stream,
                    summarize(content),
                    Instant.now()
            );
            var records = state.logsByTask.computeIfAbsent(taskId, ignored -> new ArrayDeque<>());
            records.addFirst(log);
            while (records.size() > LOG_LIMIT_PER_TASK) {
                records.removeLast();
            }
        }
        persist();
        return log;
    }

    public List<LocalTaskLog> logsForTask(String projectId, String taskId) {
        var state = stateFor(projectId);
        synchronized (state) {
            requireTask(state, taskId);
            var records = state.logsByTask.get(taskId);
            if (records == null) {
                return List.of();
            }
            return List.copyOf(records);
        }
    }

    public List<LocalTaskLog> recentLogs(String projectId) {
        var state = projects.get(projectId);
        if (state == null) {
            return List.of();
        }
        synchronized (state) {
            return state.tasks.stream()
                    .flatMap(task -> logsForExistingTask(state, task.taskId()).stream())
                    .sorted(Comparator.comparing(LocalTaskLog::createdAt).reversed())
                    .limit(RECENT_LOG_LIMIT)
                    .toList();
        }
    }

    private ProjectTaskState stateFor(String projectId) {
        var state = projects.get(projectId);
        if (state == null) {
            throw new IllegalArgumentException("执行任务不存在");
        }
        return state;
    }

    private ExecutionTask requireTask(ProjectTaskState state, String taskId) {
        return state.tasks.stream()
                .filter(task -> task.taskId().equals(taskId))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("执行任务不存在"));
    }

    private List<LocalTaskLog> logsForExistingTask(ProjectTaskState state, String taskId) {
        var records = state.logsByTask.get(taskId);
        return records == null ? List.of() : List.copyOf(records);
    }

    private void restore(LocalExecutionSnapshot snapshot) {
        var changed = false;
        for (var entry : snapshot.tasks().entrySet()) {
            var projectId = entry.getKey();
            var state = new ProjectTaskState();
            var storedLogs = snapshot.taskLogs().getOrDefault(projectId, Map.of());
            for (var logsEntry : storedLogs.entrySet()) {
                state.logsByTask.put(logsEntry.getKey(), new ArrayDeque<>(logsEntry.getValue()));
            }
            for (var task : entry.getValue()) {
                var restored = task;
                if (task.status() == ExecutionTaskStatus.QUEUED || task.status() == ExecutionTaskStatus.RUNNING) {
                    var canceledAt = Instant.now();
                    restored = new ExecutionTask(
                            task.taskId(),
                            task.projectId(),
                            task.workspaceId(),
                            task.actorId(),
                            task.toolType(),
                            task.command(),
                            task.approvalDecision(),
                            ExecutionTaskStatus.CANCELED,
                            task.exitCode(),
                            task.stdoutSummary(),
                            task.stderrSummary(),
                            task.durationMillis(),
                            task.createdAt(),
                            task.approverId(),
                            task.approvalNote(),
                            task.decidedAt(),
                            task.safetyRejectionReason(),
                            "system",
                            "服务重启后任务已停止",
                            canceledAt
                    );
                    state.logsByTask.computeIfAbsent(task.taskId(), ignored -> new ArrayDeque<>())
                            .addFirst(new LocalTaskLog(
                                    UUID.randomUUID().toString(),
                                    task.projectId(),
                                    task.taskId(),
                                    LocalTaskLogStream.SYSTEM,
                                    "服务重启后任务已停止",
                                    canceledAt
                            ));
                    changed = true;
                }
                state.tasks.addLast(restored);
            }
            trimHistory(state);
            projects.put(projectId, state);
        }
        if (changed) {
            persist();
        }
    }

    private void persist() {
        var tasksSnapshot = new HashMap<String, List<ExecutionTask>>();
        var logsSnapshot = new HashMap<String, Map<String, List<LocalTaskLog>>>();
        projects.forEach((projectId, state) -> {
            synchronized (state) {
                tasksSnapshot.put(projectId, List.copyOf(state.tasks));
                var logsByTaskSnapshot = new HashMap<String, List<LocalTaskLog>>();
                state.logsByTask.forEach((taskId, logs) -> logsByTaskSnapshot.put(taskId, List.copyOf(logs)));
                logsSnapshot.put(projectId, Map.copyOf(logsByTaskSnapshot));
            }
        });
        store.saveTasks(Map.copyOf(tasksSnapshot), Map.copyOf(logsSnapshot));
    }

    private void validateReplacement(String projectId, String taskId, ExecutionTask replacement) {
        if (replacement == null) {
            throw new IllegalArgumentException("替换任务不能为空");
        }
        if (!replacement.projectId().equals(projectId) || !replacement.taskId().equals(taskId)) {
            throw new IllegalArgumentException("替换任务归属不一致");
        }
    }

    private void trimHistory(ProjectTaskState state) {
        var retained = new ArrayDeque<ExecutionTask>();
        var terminalCount = 0;
        for (var task : state.tasks) {
            if (isActive(task.status())) {
                retained.addLast(task);
            } else if (terminalCount < HISTORY_LIMIT) {
                retained.addLast(task);
                terminalCount++;
            } else {
                state.logsByTask.remove(task.taskId());
            }
        }
        state.tasks.clear();
        state.tasks.addAll(retained);
    }

    private boolean isActive(ExecutionTaskStatus status) {
        return status == ExecutionTaskStatus.QUEUED
                || status == ExecutionTaskStatus.RUNNING
                || status == ExecutionTaskStatus.APPROVAL_PENDING;
    }

    private String summarize(String content) {
        if (content == null) {
            return "";
        }
        if (content.length() <= LOG_CONTENT_LIMIT) {
            return content;
        }
        return content.substring(0, LOG_CONTENT_LIMIT - 3) + "...";
    }

    private static final class ProjectTaskState {
        private final ArrayDeque<ExecutionTask> tasks = new ArrayDeque<>();
        private final Map<String, ArrayDeque<LocalTaskLog>> logsByTask = new HashMap<>();
    }
}
