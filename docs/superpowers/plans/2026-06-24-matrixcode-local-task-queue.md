# MatrixCode 第七阶段本地长任务队列实现计划

> **面向 AI 代理的工作者：** 必需子技能：使用 superpowers:subagent-driven-development（推荐）或 superpowers:executing-plans 逐任务实现此计划。步骤使用复选框（`- [ ]`）语法来跟踪进度。

**目标：** 构建第七阶段本地长任务队列纵切，让已允许执行的本地命令可以入队、异步运行、追加日志、取消，并在工作台和桌面端持续可见。

**架构：** 在 `localexecution` 模块内新增任务日志领域模型、内存任务存储和单线程队列执行器。`LocalCommandService` 继续负责审批策略和审计，允许执行时委托队列服务；`LocalExecutionSummaryService` 聚合运行中任务和最近日志；桌面端扩展 API 类型、右侧本地执行代理卡片和取消交互。

**技术栈：** Java 21、Spring Boot 3.5.15、JUnit 5、AssertJ、MockMvc、Awaitility、React 19.2.7、TypeScript 6.0.3、Vitest 4.1.9、本地 Maven `/Users/Masons/Ai/Maven` 和仓库 `/Users/Masons/Ai/Maven_Ai_Store`。

---

## 范围检查

本计划只实现本地长任务运行态：任务入队、运行、日志、取消、工作台摘要和桌面展示。第七阶段不做真实 SSH、远程部署、Docker Compose 生命周期、数据库持久化、任务重试、并发池配置、多人权限、登录鉴权，也不新增 WebSocket 或 SSE 日志直连。

安全边界继续复用第五阶段策略。`ASK` 任务不会自动执行；人工批准后仍要再次通过安全校验。SSH、部署、凭证、危险 Shell 语法、删除、回滚和远程服务重启命令都不得进入队列。

## 文件结构

```text
server/src/main/java/com/matrixcode/localexecution/
├── api/
│   └── LocalExecutionController.java
├── application/
│   ├── LocalCommandService.java
│   ├── LocalExecutionSummaryService.java
│   ├── LocalTaskQueueService.java
│   └── LocalTaskStore.java
└── domain/
    ├── ExecutionTask.java
    ├── ExecutionTaskStatus.java
    ├── LocalExecutionSummary.java
    ├── LocalTaskLog.java
    └── LocalTaskLogStream.java

server/src/test/java/com/matrixcode/localexecution/
├── LocalCommandServiceTest.java
├── LocalExecutionControllerTest.java
├── LocalTaskQueueServiceTest.java
├── LocalTaskStoreTest.java
└── WorkbenchLocalExecutionTest.java

server/src/test/java/com/matrixcode/workbench/
└── WorkbenchServiceTest.java

desktop/src/
├── api/
│   ├── client.ts
│   └── client.test.ts
├── components/
│   └── InspectorPanel.tsx
├── test/
│   └── App.test.tsx
├── App.tsx
└── App.css

docs/development/local-run.md
docs/superpowers/plans/2026-06-24-matrixcode-local-task-queue.md
```

---

### 任务 1：新增任务状态、日志领域模型和内存存储

**文件：**

- 修改：`server/src/main/java/com/matrixcode/localexecution/domain/ExecutionTaskStatus.java`
- 修改：`server/src/main/java/com/matrixcode/localexecution/domain/ExecutionTask.java`
- 修改：`server/src/main/java/com/matrixcode/localexecution/domain/LocalExecutionSummary.java`
- 创建：`server/src/main/java/com/matrixcode/localexecution/domain/LocalTaskLogStream.java`
- 创建：`server/src/main/java/com/matrixcode/localexecution/domain/LocalTaskLog.java`
- 创建：`server/src/main/java/com/matrixcode/localexecution/application/LocalTaskStore.java`
- 创建：`server/src/test/java/com/matrixcode/localexecution/LocalTaskStoreTest.java`

- [x] **步骤 1：编写任务存储失败测试**

创建 `LocalTaskStoreTest.java`：

```java
package com.matrixcode.localexecution;

import com.matrixcode.approval.domain.ApprovalDecision;
import com.matrixcode.localexecution.application.LocalTaskStore;
import com.matrixcode.localexecution.domain.ExecutionTask;
import com.matrixcode.localexecution.domain.ExecutionTaskStatus;
import com.matrixcode.localexecution.domain.LocalTaskLogStream;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class LocalTaskStoreTest {

    @Test
    void 可以记录任务查询运行中任务并替换状态() {
        var store = new LocalTaskStore();
        var queued = task("task-1", ExecutionTaskStatus.QUEUED);

        store.record(queued);
        var running = store.replace("demo", "task-1", current -> new ExecutionTask(
                current.taskId(),
                current.projectId(),
                current.workspaceId(),
                current.actorId(),
                current.toolType(),
                current.command(),
                current.approvalDecision(),
                ExecutionTaskStatus.RUNNING,
                null,
                "",
                "",
                0,
                current.createdAt(),
                current.approverId(),
                current.approvalNote(),
                current.decidedAt(),
                current.safetyRejectionReason(),
                "",
                "",
                null
        ));

        assertThat(running.status()).isEqualTo(ExecutionTaskStatus.RUNNING);
        assertThat(store.recentTasks("demo")).extracting("taskId").containsExactly("task-1");
        assertThat(store.activeTasks("demo")).extracting("status").containsExactly(ExecutionTaskStatus.RUNNING);
    }

    @Test
    void 可以追加任务日志并按项目和任务查询() {
        var store = new LocalTaskStore();
        store.record(task("task-1", ExecutionTaskStatus.RUNNING));

        var log = store.appendLog("demo", "task-1", LocalTaskLogStream.SYSTEM, "任务已进入队列");

        assertThat(log.content()).isEqualTo("任务已进入队列");
        assertThat(store.logsForTask("demo", "task-1")).containsExactly(log);
        assertThat(store.recentLogs("demo")).containsExactly(log);
    }

    @Test
    void 查询不存在任务返回中文错误() {
        var store = new LocalTaskStore();

        assertThatThrownBy(() -> store.require("demo", "missing"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("执行任务不存在");
    }

    private ExecutionTask task(String taskId, ExecutionTaskStatus status) {
        return new ExecutionTask(
                taskId,
                "demo",
                "workspace-1",
                "user-dev",
                "SHELL",
                "npm test",
                ApprovalDecision.ALLOW,
                status,
                null,
                "",
                "",
                0,
                Instant.now()
        );
    }
}
```

- [x] **步骤 2：运行任务存储测试验证失败**

运行：

```bash
/Users/Masons/Ai/Maven/bin/mvn -Dmaven.repo.local=/Users/Masons/Ai/Maven_Ai_Store -q -pl server -Dtest=LocalTaskStoreTest test
```

预期：编译失败，错误包含 `LocalTaskStore`、`LocalTaskLogStream` 或 `QUEUED` 不存在。

- [x] **步骤 3：扩展执行任务状态**

修改 `ExecutionTaskStatus.java`：

```java
package com.matrixcode.localexecution.domain;

public enum ExecutionTaskStatus {
    APPROVAL_PENDING,
    DENIED,
    QUEUED,
    RUNNING,
    SUCCESS,
    FAILED,
    CANCELED
}
```

- [x] **步骤 4：扩展执行任务取消元数据**

修改 `ExecutionTask.java`，新增完整构造字段和兼容构造器：

```java
public record ExecutionTask(
        String taskId,
        String projectId,
        String workspaceId,
        String actorId,
        String toolType,
        String command,
        ApprovalDecision approvalDecision,
        ExecutionTaskStatus status,
        Integer exitCode,
        String stdoutSummary,
        String stderrSummary,
        long durationMillis,
        Instant createdAt,
        String approverId,
        String approvalNote,
        Instant decidedAt,
        String safetyRejectionReason,
        String canceledBy,
        String cancelNote,
        Instant canceledAt
) {
    public ExecutionTask(
            String taskId,
            String projectId,
            String workspaceId,
            String actorId,
            String toolType,
            String command,
            ApprovalDecision approvalDecision,
            ExecutionTaskStatus status,
            Integer exitCode,
            String stdoutSummary,
            String stderrSummary,
            long durationMillis,
            Instant createdAt
    ) {
        this(taskId, projectId, workspaceId, actorId, toolType, command, approvalDecision, status,
                exitCode, stdoutSummary, stderrSummary, durationMillis, createdAt,
                "", "", null, "", "", "", null);
    }

    public ExecutionTask {
        taskId = requireText(taskId, "任务编号不能为空");
        projectId = requireText(projectId, "项目编号不能为空");
        workspaceId = requireText(workspaceId, "工作区编号不能为空");
        actorId = requireText(actorId, "执行人不能为空");
        toolType = requireText(toolType, "工具类型不能为空");
        command = requireText(command, "命令不能为空");
        if (approvalDecision == null) {
            throw new IllegalArgumentException("审批结果不能为空");
        }
        if (status == null) {
            throw new IllegalArgumentException("执行状态不能为空");
        }
        stdoutSummary = stdoutSummary == null ? "" : stdoutSummary;
        stderrSummary = stderrSummary == null ? "" : stderrSummary;
        if (durationMillis < 0) {
            throw new IllegalArgumentException("执行耗时不能为负数");
        }
        if (createdAt == null) {
            throw new IllegalArgumentException("任务创建时间不能为空");
        }
        approverId = approverId == null ? "" : approverId.trim();
        approvalNote = approvalNote == null ? "" : approvalNote.trim();
        safetyRejectionReason = safetyRejectionReason == null ? "" : safetyRejectionReason.trim();
        canceledBy = canceledBy == null ? "" : canceledBy.trim();
        cancelNote = cancelNote == null ? "" : cancelNote.trim();
    }
}
```

保留现有 `requireText` 方法。

- [x] **步骤 5：新增任务日志模型**

创建 `LocalTaskLogStream.java`：

```java
package com.matrixcode.localexecution.domain;

public enum LocalTaskLogStream {
    STDOUT,
    STDERR,
    SYSTEM
}
```

创建 `LocalTaskLog.java`：

```java
package com.matrixcode.localexecution.domain;

import java.time.Instant;

public record LocalTaskLog(
        String id,
        String projectId,
        String taskId,
        LocalTaskLogStream stream,
        String content,
        Instant createdAt
) {
    public LocalTaskLog {
        id = requireText(id, "日志编号不能为空");
        projectId = requireText(projectId, "项目编号不能为空");
        taskId = requireText(taskId, "任务编号不能为空");
        if (stream == null) {
            throw new IllegalArgumentException("日志流不能为空");
        }
        content = content == null ? "" : content;
        if (createdAt == null) {
            throw new IllegalArgumentException("日志时间不能为空");
        }
    }

    private static String requireText(String value, String message) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(message);
        }
        return value.trim();
    }
}
```

- [x] **步骤 6：实现内存任务存储**

创建 `LocalTaskStore.java`：

```java
package com.matrixcode.localexecution.application;

import com.matrixcode.localexecution.domain.ExecutionTask;
import com.matrixcode.localexecution.domain.ExecutionTaskStatus;
import com.matrixcode.localexecution.domain.LocalTaskLog;
import com.matrixcode.localexecution.domain.LocalTaskLogStream;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;

@Service
public class LocalTaskStore {

    private static final int HISTORY_LIMIT = 20;
    private static final int LOG_LIMIT_PER_TASK = 200;
    private static final int RECENT_LOG_LIMIT = 20;
    private static final int LOG_CONTENT_LIMIT = 4096;

    private final Map<String, ArrayDeque<ExecutionTask>> tasks = new ConcurrentHashMap<>();
    private final Map<String, ArrayDeque<LocalTaskLog>> logsByTask = new ConcurrentHashMap<>();

    public ExecutionTask record(ExecutionTask task) {
        var records = tasks.computeIfAbsent(task.projectId(), ignored -> new ArrayDeque<>());
        synchronized (records) {
            records.removeIf(existing -> existing.taskId().equals(task.taskId()));
            records.addFirst(task);
            while (records.size() > HISTORY_LIMIT) {
                var removed = records.removeLast();
                logsByTask.remove(removed.taskId());
            }
        }
        return task;
    }

    public ExecutionTask replace(String projectId, String taskId, Function<ExecutionTask, ExecutionTask> replacementFactory) {
        var records = recordsFor(projectId);
        synchronized (records) {
            var updated = new ArrayDeque<ExecutionTask>();
            ExecutionTask replacement = null;
            for (var existing : records) {
                if (replacement == null && existing.taskId().equals(taskId)) {
                    replacement = replacementFactory.apply(existing);
                    updated.addLast(replacement);
                } else {
                    updated.addLast(existing);
                }
            }
            if (replacement == null) {
                throw new IllegalArgumentException("执行任务不存在");
            }
            records.clear();
            records.addAll(updated);
            return replacement;
        }
    }

    public ExecutionTask require(String projectId, String taskId) {
        var records = recordsFor(projectId);
        synchronized (records) {
            return records.stream()
                    .filter(task -> task.taskId().equals(taskId))
                    .findFirst()
                    .orElseThrow(() -> new IllegalArgumentException("执行任务不存在"));
        }
    }

    public List<ExecutionTask> recentTasks(String projectId) {
        var records = tasks.get(projectId);
        if (records == null) {
            return List.of();
        }
        synchronized (records) {
            return List.copyOf(records);
        }
    }

    public List<ExecutionTask> activeTasks(String projectId) {
        return recentTasks(projectId).stream()
                .filter(task -> task.status() == ExecutionTaskStatus.QUEUED || task.status() == ExecutionTaskStatus.RUNNING)
                .toList();
    }

    public LocalTaskLog appendLog(String projectId, String taskId, LocalTaskLogStream stream, String content) {
        require(projectId, taskId);
        var log = new LocalTaskLog(
                UUID.randomUUID().toString(),
                projectId,
                taskId,
                stream,
                summarize(content),
                Instant.now()
        );
        var records = logsByTask.computeIfAbsent(taskId, ignored -> new ArrayDeque<>());
        synchronized (records) {
            records.addFirst(log);
            while (records.size() > LOG_LIMIT_PER_TASK) {
                records.removeLast();
            }
        }
        return log;
    }

    public List<LocalTaskLog> logsForTask(String projectId, String taskId) {
        require(projectId, taskId);
        var records = logsByTask.get(taskId);
        if (records == null) {
            return List.of();
        }
        synchronized (records) {
            return List.copyOf(records);
        }
    }

    public List<LocalTaskLog> recentLogs(String projectId) {
        return recentTasks(projectId).stream()
                .flatMap(task -> logsForTask(projectId, task.taskId()).stream())
                .sorted(Comparator.comparing(LocalTaskLog::createdAt).reversed())
                .limit(RECENT_LOG_LIMIT)
                .toList();
    }

    private ArrayDeque<ExecutionTask> recordsFor(String projectId) {
        var records = tasks.get(projectId);
        if (records == null) {
            throw new IllegalArgumentException("执行任务不存在");
        }
        return records;
    }

    private String summarize(String content) {
        if (content == null) {
            return "";
        }
        if (content.length() <= LOG_CONTENT_LIMIT) {
            return content;
        }
        return content.substring(0, LOG_CONTENT_LIMIT) + "...";
    }
}
```

- [x] **步骤 7：扩展本地执行摘要模型**

修改 `LocalExecutionSummary.java`，在 `recentTasks` 后增加 `activeTasks` 和 `recentTaskLogs`：

```java
public record LocalExecutionSummary(
        List<WorkspaceAuthorization> workspaces,
        List<FileOperationRecord> recentFileOperations,
        List<ExecutionTask> recentTasks,
        List<ExecutionTask> activeTasks,
        List<LocalTaskLog> recentTaskLogs,
        GitDiffSummary recentGitDiff,
        List<AuditRecord> recentAuditRecords
) {
    public LocalExecutionSummary {
        workspaces = List.copyOf(workspaces == null ? List.of() : workspaces);
        recentFileOperations = List.copyOf(recentFileOperations == null ? List.of() : recentFileOperations);
        recentTasks = List.copyOf(recentTasks == null ? List.of() : recentTasks);
        activeTasks = List.copyOf(activeTasks == null ? List.of() : activeTasks);
        recentTaskLogs = List.copyOf(recentTaskLogs == null ? List.of() : recentTaskLogs);
        recentAuditRecords = List.copyOf(recentAuditRecords == null ? List.of() : recentAuditRecords);
    }
}
```

- [x] **步骤 8：运行任务存储测试验证通过**

运行：

```bash
/Users/Masons/Ai/Maven/bin/mvn -Dmaven.repo.local=/Users/Masons/Ai/Maven_Ai_Store -q -pl server -Dtest=LocalTaskStoreTest test
```

预期：`LocalTaskStoreTest` 通过。

- [x] **步骤 9：提交**

```bash
git add server/src/main/java/com/matrixcode/localexecution/domain/ExecutionTaskStatus.java
git add server/src/main/java/com/matrixcode/localexecution/domain/ExecutionTask.java
git add server/src/main/java/com/matrixcode/localexecution/domain/LocalExecutionSummary.java
git add server/src/main/java/com/matrixcode/localexecution/domain/LocalTaskLogStream.java
git add server/src/main/java/com/matrixcode/localexecution/domain/LocalTaskLog.java
git add server/src/main/java/com/matrixcode/localexecution/application/LocalTaskStore.java
git add server/src/test/java/com/matrixcode/localexecution/LocalTaskStoreTest.java
git commit -m "feat: 添加本地任务状态和日志存储"
```

---

### 任务 2：实现本地长任务队列执行器

**文件：**

- 创建：`server/src/main/java/com/matrixcode/localexecution/application/LocalTaskQueueService.java`
- 创建：`server/src/test/java/com/matrixcode/localexecution/LocalTaskQueueServiceTest.java`

- [x] **步骤 1：编写队列服务失败测试**

创建 `LocalTaskQueueServiceTest.java`：

```java
package com.matrixcode.localexecution;

import com.matrixcode.approval.domain.ApprovalDecision;
import com.matrixcode.localexecution.application.LocalTaskQueueService;
import com.matrixcode.localexecution.application.LocalTaskStore;
import com.matrixcode.localexecution.application.WorkspaceRegistry;
import com.matrixcode.localexecution.domain.ExecutionTaskStatus;
import com.matrixcode.localexecution.domain.LocalTaskLogStream;
import com.matrixcode.realtime.application.ProjectEventBus;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

class LocalTaskQueueServiceTest {

    @TempDir
    Path workspace;

    @Test
    void 入队任务会异步运行并记录标准输出和事件() throws Exception {
        Files.writeString(workspace.resolve("package.json"), """
                {"scripts":{"test":"node -e \\"console.log('queue-ok')\\""}}
                """);
        var registry = new WorkspaceRegistry();
        var store = new LocalTaskStore();
        var events = new ProjectEventBus();
        var queue = new LocalTaskQueueService(registry, store, events);
        var authorized = registry.authorize("demo", "当前项目", workspace.toString());

        var queued = queue.enqueue("demo", authorized.id(), "user-dev", "npm test", ApprovalDecision.ALLOW);

        assertThat(queued.status()).isEqualTo(ExecutionTaskStatus.QUEUED);
        await().atMost(5, TimeUnit.SECONDS).untilAsserted(() ->
                assertThat(store.require("demo", queued.taskId()).status()).isEqualTo(ExecutionTaskStatus.SUCCESS));
        assertThat(store.logsForTask("demo", queued.taskId()))
                .anySatisfy(log -> {
                    assertThat(log.stream()).isEqualTo(LocalTaskLogStream.STDOUT);
                    assertThat(log.content()).contains("queue-ok");
                });
        assertThat(events.recent("demo")).extracting("type")
                .contains("LOCAL_COMMAND_QUEUED", "LOCAL_COMMAND_STARTED", "LOCAL_COMMAND_COMPLETED");
    }

    @Test
    void 运行中任务可以取消并记录取消信息() throws Exception {
        var registry = new WorkspaceRegistry();
        var store = new LocalTaskStore();
        var queue = new LocalTaskQueueService(registry, store, new ProjectEventBus());
        var authorized = registry.authorize("demo", "当前项目", workspace.toString());
        var queued = queue.enqueue("demo", authorized.id(), "user-dev", "sleep 5", ApprovalDecision.ALLOW);
        await().atMost(2, TimeUnit.SECONDS).untilAsserted(() ->
                assertThat(store.require("demo", queued.taskId()).status()).isEqualTo(ExecutionTaskStatus.RUNNING));

        var canceled = queue.cancel("demo", queued.taskId(), "user-reviewer", "验证取消");

        assertThat(canceled.status()).isEqualTo(ExecutionTaskStatus.CANCELED);
        assertThat(canceled.canceledBy()).isEqualTo("user-reviewer");
        assertThat(canceled.cancelNote()).isEqualTo("验证取消");
        assertThat(store.logsForTask("demo", queued.taskId()))
                .anySatisfy(log -> assertThat(log.content()).contains("任务已取消"));
    }

    @Test
    void 已完成任务不能取消() throws Exception {
        Files.writeString(workspace.resolve("package.json"), """
                {"scripts":{"test":"node -e \\"console.log('done')\\""}}
                """);
        var registry = new WorkspaceRegistry();
        var store = new LocalTaskStore();
        var queue = new LocalTaskQueueService(registry, store, new ProjectEventBus());
        var authorized = registry.authorize("demo", "当前项目", workspace.toString());
        var queued = queue.enqueue("demo", authorized.id(), "user-dev", "npm test", ApprovalDecision.ALLOW);
        await().atMost(5, TimeUnit.SECONDS).untilAsserted(() ->
                assertThat(store.require("demo", queued.taskId()).status()).isEqualTo(ExecutionTaskStatus.SUCCESS));

        assertThatThrownBy(() -> queue.cancel("demo", queued.taskId(), "user-reviewer", "重复取消"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("任务已结束，不能取消");
    }
}
```

- [x] **步骤 2：运行队列服务测试验证失败**

运行：

```bash
/Users/Masons/Ai/Maven/bin/mvn -Dmaven.repo.local=/Users/Masons/Ai/Maven_Ai_Store -q -pl server -Dtest=LocalTaskQueueServiceTest test
```

预期：编译失败，错误包含 `LocalTaskQueueService` 不存在。

- [x] **步骤 3：实现队列服务**

创建 `LocalTaskQueueService.java`：

```java
package com.matrixcode.localexecution.application;

import com.matrixcode.approval.domain.ApprovalDecision;
import com.matrixcode.localexecution.domain.ExecutionTask;
import com.matrixcode.localexecution.domain.ExecutionTaskStatus;
import com.matrixcode.localexecution.domain.LocalTaskLogStream;
import com.matrixcode.realtime.application.ProjectEventBus;
import com.matrixcode.realtime.domain.ProjectEvent;
import org.springframework.stereotype.Service;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

@Service
public class LocalTaskQueueService {

    private static final int OUTPUT_LIMIT = 4096;
    private static final Duration TASK_TIMEOUT = Duration.ofSeconds(60);

    private final WorkspaceRegistry workspaces;
    private final LocalTaskStore taskStore;
    private final ProjectEventBus eventBus;
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final Map<String, Process> runningProcesses = new ConcurrentHashMap<>();

    public LocalTaskQueueService(WorkspaceRegistry workspaces, LocalTaskStore taskStore, ProjectEventBus eventBus) {
        this.workspaces = workspaces;
        this.taskStore = taskStore;
        this.eventBus = eventBus;
    }

    public ExecutionTask enqueue(
            String projectId,
            String workspaceId,
            String actorId,
            String command,
            ApprovalDecision decision
    ) {
        return enqueue(UUID.randomUUID().toString(), projectId, workspaceId, actorId, command, decision);
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
        var task = new ExecutionTask(
                taskId,
                projectId,
                workspaceId,
                actorId,
                "SHELL",
                command,
                decision,
                ExecutionTaskStatus.QUEUED,
                null,
                "",
                "",
                0,
                Instant.now()
        );
        taskStore.record(task);
        taskStore.appendLog(projectId, task.taskId(), LocalTaskLogStream.SYSTEM, "任务已进入队列");
        publish(projectId, "LOCAL_COMMAND_QUEUED", "本地命令已进入队列");
        executor.submit(() -> runQueuedTask(task.taskId(), workspace.rootPath()));
        return task;
    }

    public ExecutionTask enqueueExisting(ExecutionTask task, String rootPath) {
        var queued = copyWithState(task, ExecutionTaskStatus.QUEUED, null, "", "", 0);
        taskStore.record(queued);
        taskStore.appendLog(queued.projectId(), queued.taskId(), LocalTaskLogStream.SYSTEM, "任务已进入队列");
        publish(queued.projectId(), "LOCAL_COMMAND_QUEUED", "本地命令已进入队列");
        executor.submit(() -> runQueuedTask(queued.taskId(), rootPath));
        return queued;
    }

    public ExecutionTask cancel(String projectId, String taskId, String actorId, String note) {
        actorId = requireText(actorId, "执行人不能为空");
        var current = taskStore.require(projectId, taskId);
        if (isFinished(current.status())) {
            throw new IllegalArgumentException("任务已结束，不能取消");
        }
        var process = runningProcesses.remove(taskId);
        if (process != null && process.isAlive()) {
            process.destroy();
            try {
                if (!process.waitFor(500, TimeUnit.MILLISECONDS)) {
                    process.destroyForcibly();
                }
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                process.destroyForcibly();
            }
        }
        var canceledAt = Instant.now();
        var canceled = taskStore.replace(projectId, taskId, existing -> new ExecutionTask(
                existing.taskId(),
                existing.projectId(),
                existing.workspaceId(),
                existing.actorId(),
                existing.toolType(),
                existing.command(),
                existing.approvalDecision(),
                ExecutionTaskStatus.CANCELED,
                existing.exitCode(),
                existing.stdoutSummary(),
                existing.stderrSummary(),
                Duration.between(existing.createdAt(), canceledAt).toMillis(),
                existing.createdAt(),
                existing.approverId(),
                existing.approvalNote(),
                existing.decidedAt(),
                existing.safetyRejectionReason(),
                actorId,
                note,
                canceledAt
        ));
        taskStore.appendLog(projectId, taskId, LocalTaskLogStream.SYSTEM, "任务已取消：" + (note == null ? "" : note));
        publish(projectId, "LOCAL_COMMAND_CANCELED", "本地命令已取消");
        return canceled;
    }

    private void runQueuedTask(String taskId, String rootPath) {
        var current = findByTaskId(taskId);
        if (current.status() == ExecutionTaskStatus.CANCELED) {
            return;
        }
        var startedAt = Instant.now();
        var running = taskStore.replace(current.projectId(), taskId, task ->
                copyWithState(task, ExecutionTaskStatus.RUNNING, null, "", "", 0));
        taskStore.appendLog(running.projectId(), taskId, LocalTaskLogStream.SYSTEM, "任务开始运行");
        publish(running.projectId(), "LOCAL_COMMAND_STARTED", "本地命令开始运行");

        try {
            var process = new ProcessBuilder(tokensOf(running.command()))
                    .directory(java.nio.file.Path.of(rootPath).toFile())
                    .start();
            runningProcesses.put(taskId, process);
            var stdout = new StringBuilder();
            var stderr = new StringBuilder();
            var stdoutReader = readerThread(running.projectId(), taskId, process, true, stdout);
            var stderrReader = readerThread(running.projectId(), taskId, process, false, stderr);
            stdoutReader.start();
            stderrReader.start();
            var finished = process.waitFor(TASK_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
            if (!finished) {
                process.destroyForcibly();
                finish(running, ExecutionTaskStatus.FAILED, null, stdout.toString(), "命令执行超时", startedAt);
                taskStore.appendLog(running.projectId(), taskId, LocalTaskLogStream.SYSTEM, "命令执行超时，已终止进程");
                publish(running.projectId(), "LOCAL_COMMAND_FAILED", "本地命令执行超时");
                return;
            }
            stdoutReader.join(1000);
            stderrReader.join(1000);
            var status = process.exitValue() == 0 ? ExecutionTaskStatus.SUCCESS : ExecutionTaskStatus.FAILED;
            finish(running, status, process.exitValue(), stdout.toString(), stderr.toString(), startedAt);
            publish(running.projectId(), status == ExecutionTaskStatus.SUCCESS ? "LOCAL_COMMAND_COMPLETED" : "LOCAL_COMMAND_FAILED",
                    status == ExecutionTaskStatus.SUCCESS ? "本地命令执行完成" : "本地命令执行失败");
        } catch (IOException exception) {
            finish(running, ExecutionTaskStatus.FAILED, -1, "", "命令无法启动：" + exception.getMessage(), startedAt);
            publish(running.projectId(), "LOCAL_COMMAND_FAILED", "本地命令无法启动");
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            finish(running, ExecutionTaskStatus.FAILED, -1, "", "命令执行被中断", startedAt);
            publish(running.projectId(), "LOCAL_COMMAND_FAILED", "本地命令执行被中断");
        } finally {
            runningProcesses.remove(taskId);
        }
    }

    private Thread readerThread(String projectId, String taskId, Process process, boolean stdout, StringBuilder target) {
        var stream = stdout ? process.getInputStream() : process.getErrorStream();
        var logStream = stdout ? LocalTaskLogStream.STDOUT : LocalTaskLogStream.STDERR;
        return Thread.ofVirtual().unstarted(() -> {
            try (var reader = new BufferedReader(new InputStreamReader(stream, StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    target.append(line).append(System.lineSeparator());
                    taskStore.appendLog(projectId, taskId, logStream, line);
                    publish(projectId, "LOCAL_COMMAND_LOGGED", "本地命令产生新日志");
                }
            } catch (IOException ignored) {
                taskStore.appendLog(projectId, taskId, LocalTaskLogStream.SYSTEM, "读取命令日志结束");
            }
        });
    }

    private void finish(
            ExecutionTask task,
            ExecutionTaskStatus status,
            Integer exitCode,
            String stdout,
            String stderr,
            Instant startedAt
    ) {
        var current = taskStore.require(task.projectId(), task.taskId());
        if (current.status() == ExecutionTaskStatus.CANCELED) {
            return;
        }
        var duration = Duration.between(startedAt, Instant.now()).toMillis();
        taskStore.replace(task.projectId(), task.taskId(), existing -> new ExecutionTask(
                existing.taskId(),
                existing.projectId(),
                existing.workspaceId(),
                existing.actorId(),
                existing.toolType(),
                existing.command(),
                existing.approvalDecision(),
                status,
                exitCode,
                summarize(stdout),
                summarize(stderr),
                duration,
                existing.createdAt(),
                existing.approverId(),
                existing.approvalNote(),
                existing.decidedAt(),
                existing.safetyRejectionReason(),
                existing.canceledBy(),
                existing.cancelNote(),
                existing.canceledAt()
        ));
        taskStore.appendLog(task.projectId(), task.taskId(), LocalTaskLogStream.SYSTEM, "任务结束：" + status);
    }

    private ExecutionTask findByTaskId(String taskId) {
        return taskStore.recentTasksByAllProjects().stream()
                .filter(task -> task.taskId().equals(taskId))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("执行任务不存在"));
    }

    private ExecutionTask copyWithState(
            ExecutionTask task,
            ExecutionTaskStatus status,
            Integer exitCode,
            String stdout,
            String stderr,
            long durationMillis
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
                stdout,
                stderr,
                durationMillis,
                task.createdAt(),
                task.approverId(),
                task.approvalNote(),
                task.decidedAt(),
                task.safetyRejectionReason(),
                task.canceledBy(),
                task.cancelNote(),
                task.canceledAt()
        );
    }

    private boolean isFinished(ExecutionTaskStatus status) {
        return status == ExecutionTaskStatus.DENIED
                || status == ExecutionTaskStatus.SUCCESS
                || status == ExecutionTaskStatus.FAILED
                || status == ExecutionTaskStatus.CANCELED;
    }

    private String summarize(String output) {
        if (output == null) {
            return "";
        }
        if (output.length() <= OUTPUT_LIMIT) {
            return output;
        }
        return output.substring(0, OUTPUT_LIMIT) + "...";
    }

    private java.util.List<String> tokensOf(String command) {
        return Arrays.stream(command.trim().split("\\s+")).toList();
    }

    private void publish(String projectId, String type, String message) {
        eventBus.publish(new ProjectEvent(projectId, type, message));
    }

    private String requireText(String value, String message) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(message);
        }
        return value.trim();
    }
}
```

同时在 `LocalTaskStore` 增加队列服务需要的全项目查询：

```java
public List<ExecutionTask> recentTasksByAllProjects() {
    return tasks.keySet().stream()
            .flatMap(projectId -> recentTasks(projectId).stream())
            .toList();
}
```

- [x] **步骤 4：运行队列服务测试验证通过**

运行：

```bash
/Users/Masons/Ai/Maven/bin/mvn -Dmaven.repo.local=/Users/Masons/Ai/Maven_Ai_Store -q -pl server -Dtest=LocalTaskQueueServiceTest,LocalTaskStoreTest test
```

预期：两个测试类通过。

- [x] **步骤 5：提交**

```bash
git add server/src/main/java/com/matrixcode/localexecution/application/LocalTaskQueueService.java
git add server/src/main/java/com/matrixcode/localexecution/application/LocalTaskStore.java
git add server/src/test/java/com/matrixcode/localexecution/LocalTaskQueueServiceTest.java
git commit -m "feat: 添加本地长任务队列服务"
```

---

### 任务 3：接入命令提交、审批和取消流程

**文件：**

- 修改：`server/src/main/java/com/matrixcode/localexecution/application/LocalCommandService.java`
- 修改：`server/src/main/java/com/matrixcode/localexecution/application/LocalExecutionSummaryService.java`
- 修改：`server/src/test/java/com/matrixcode/localexecution/LocalCommandServiceTest.java`
- 修改：`server/src/test/java/com/matrixcode/localexecution/WorkbenchLocalExecutionTest.java`
- 修改：`server/src/test/java/com/matrixcode/workbench/WorkbenchServiceTest.java`

- [x] **步骤 1：编写命令服务失败测试**

修改 `LocalCommandServiceTest`：

1. 所有 `new LocalCommandService(...)` 改为使用共享工厂：

```java
private TestServices services() {
    var registry = new WorkspaceRegistry();
    var audit = new AuditService();
    var store = new LocalTaskStore();
    var queue = new LocalTaskQueueService(registry, store, new ProjectEventBus());
    var commandService = new LocalCommandService(registry, new ApprovalPolicy(), audit, store, queue);
    return new TestServices(registry, audit, store, queue, commandService);
}

private record TestServices(
        WorkspaceRegistry registry,
        AuditService audit,
        LocalTaskStore store,
        LocalTaskQueueService queue,
        LocalCommandService commandService
) {
}
```

2. 更新安全命令测试，让它断言入队而不是同步完成：

```java
@Test
void 安全命令会进入队列并记录审计() throws Exception {
    Files.writeString(workspace.resolve("package.json"), """
            {"scripts":{"test":"node -e \\"console.log('queued')\\""}}
            """);
    var services = services();
    var authorized = services.registry().authorize("demo", "当前项目", workspace.toString());

    var task = services.commandService().submit("demo", authorized.id(), "user-dev", "npm test");

    assertThat(task.approvalDecision()).isEqualTo(ApprovalDecision.ALLOW);
    assertThat(task.status()).isEqualTo(ExecutionTaskStatus.QUEUED);
    assertThat(services.commandService().recentTasks("demo")).extracting("taskId").contains(task.taskId());
    assertThat(services.audit().records()).hasSize(1);
}
```

3. 新增审批通过后入队测试：

```java
@Test
void 待审批本地命令批准后进入队列() {
    var services = services();
    var authorized = services.registry().authorize("demo", "当前项目", workspace.toString());
    var pending = services.commandService().submit("demo", authorized.id(), "user-dev", "sleep 1");

    var queued = services.commandService().decide("demo", pending.taskId(), "user-reviewer", ApprovalDecision.ALLOW, "允许运行");

    assertThat(queued.taskId()).isEqualTo(pending.taskId());
    assertThat(queued.status()).isEqualTo(ExecutionTaskStatus.QUEUED);
    assertThat(queued.approvalDecision()).isEqualTo(ApprovalDecision.ALLOW);
    assertThat(queued.approverId()).isEqualTo("user-reviewer");
    assertThat(services.audit().records()).extracting("decision").containsExactly(ApprovalDecision.ASK, ApprovalDecision.ALLOW);
}
```

4. 新增取消委托测试：

```java
@Test
void 可以通过命令服务取消运行中任务() {
    var services = services();
    var authorized = services.registry().authorize("demo", "当前项目", workspace.toString());
    var pending = services.commandService().submit("demo", authorized.id(), "user-dev", "sleep 5");
    var queued = services.commandService().decide("demo", pending.taskId(), "user-reviewer", ApprovalDecision.ALLOW, "允许运行");

    var canceled = services.commandService().cancel("demo", queued.taskId(), "user-reviewer", "停止验证");

    assertThat(canceled.status()).isEqualTo(ExecutionTaskStatus.CANCELED);
    assertThat(canceled.canceledBy()).isEqualTo("user-reviewer");
}
```

- [x] **步骤 2：运行命令服务测试验证失败**

运行：

```bash
/Users/Masons/Ai/Maven/bin/mvn -Dmaven.repo.local=/Users/Masons/Ai/Maven_Ai_Store -q -pl server -Dtest=LocalCommandServiceTest test
```

预期：编译失败，错误包含 `LocalCommandService` 构造器、`cancel`、`activeTasks` 或 `recentLogs` 不存在。

- [x] **步骤 3：重构 LocalCommandService 使用任务存储和队列**

修改字段和构造器：

```java
private final WorkspaceRegistry workspaces;
private final ApprovalPolicy approvalPolicy;
private final AuditService auditService;
private final LocalTaskStore taskStore;
private final LocalTaskQueueService queueService;

public LocalCommandService(
        WorkspaceRegistry workspaces,
        ApprovalPolicy approvalPolicy,
        AuditService auditService,
        LocalTaskStore taskStore,
        LocalTaskQueueService queueService
) {
    this.workspaces = workspaces;
    this.approvalPolicy = approvalPolicy;
    this.auditService = auditService;
    this.taskStore = taskStore;
    this.queueService = queueService;
}
```

修改 `submit(...)`：

```java
if (decision == ApprovalDecision.ASK) {
    return taskStore.record(new ExecutionTask(taskId, projectId, workspaceId, actorId, "SHELL", command, decision,
            ExecutionTaskStatus.APPROVAL_PENDING, null, "", "", 0, Instant.now()));
}
if (decision == ApprovalDecision.DENY) {
    return taskStore.record(new ExecutionTask(taskId, projectId, workspaceId, actorId, "SHELL", command, decision,
            ExecutionTaskStatus.DENIED, null, "", "", 0, Instant.now()));
}

return queueService.enqueue(taskId, projectId, workspaceId, actorId, command, decision);
```

修改 `recentTasks(...)`：

```java
public List<ExecutionTask> recentTasks(String projectId) {
    return taskStore.recentTasks(projectId);
}

public List<ExecutionTask> activeTasks(String projectId) {
    return taskStore.activeTasks(projectId);
}

public List<LocalTaskLog> recentLogs(String projectId) {
    return taskStore.recentLogs(projectId);
}

public List<LocalTaskLog> logsForTask(String projectId, String taskId) {
    return taskStore.logsForTask(projectId, taskId);
}

public ExecutionTask cancel(String projectId, String taskId, String actorId, String note) {
    var canceled = queueService.cancel(projectId, taskId, actorId, note);
    auditService.record(actionFor(canceled, actorId, workspacePathForAudit(canceled)), ApprovalDecision.DENY);
    return canceled;
}
```

把原来的 `record(...)`、`findTask(...)`、`replacePending(...)`、`replace(...)` 改为调用 `taskStore`。人工批准通过时构造带审批元数据的任务，并调用 `queueService.enqueueExisting(...)`：

```java
var pending = requirePendingTask(targetProjectId, targetTaskId);
var workspace = workspaces.requireAuthorized(targetProjectId, pending.workspaceId());
var rejectionReason = manualApprovalRejectionReason(pending.command());
if (!rejectionReason.isEmpty()) {
    var rejected = taskStore.replace(targetProjectId, targetTaskId, current -> withApprovalMetadata(
            taskWithState(current, ApprovalDecision.DENY, ExecutionTaskStatus.DENIED,
                    null, current.stdoutSummary(), rejectionReason, current.durationMillis()),
            approverId,
            approvalNote,
            decidedAt,
            rejectionReason
    ));
    auditService.record(actionFor(rejected, approverId, workspace.rootPath()), ApprovalDecision.DENY);
    return rejected;
}

var approved = taskStore.replace(targetProjectId, targetTaskId, current -> withApprovalMetadata(
        taskWithState(current, ApprovalDecision.ALLOW, ExecutionTaskStatus.QUEUED,
                null, "", "", 0),
        approverId,
        approvalNote,
        decidedAt,
        ""
));
auditService.record(actionFor(approved, approverId, workspace.rootPath()), ApprovalDecision.ALLOW);
return queueService.enqueueExisting(approved, workspace.rootPath());
```

`requirePendingTask(...)` 改为：

```java
private ExecutionTask requirePendingTask(String projectId, String taskId) {
    var task = taskStore.require(projectId, taskId);
    if (task.status() != ExecutionTaskStatus.APPROVAL_PENDING) {
        throw new IllegalArgumentException("任务已完成审批，不能重复处理");
    }
    return task;
}
```

- [x] **步骤 4：更新本地执行摘要服务**

修改 `LocalExecutionSummaryService.summary(...)`：

```java
return new LocalExecutionSummary(
        workspaceRegistry.list(projectId),
        fileService.recentOperations(projectId),
        commandService.recentTasks(projectId),
        commandService.activeTasks(projectId),
        commandService.recentLogs(projectId),
        gitDiffService.latest(projectId),
        auditService.records().stream()
                .sorted(Comparator.comparing(AuditRecord::occurredAt).reversed())
                .limit(20)
                .toList()
);
```

- [x] **步骤 5：更新测试构造器调用点**

更新 `WorkbenchLocalExecutionTest`、`WorkbenchServiceTest` 和其他直接 new `LocalCommandService` 的测试：

```java
var localTaskStore = new LocalTaskStore();
var localTaskQueueService = new LocalTaskQueueService(workspaceRegistry, localTaskStore, events);
var localCommandService = new LocalCommandService(
        workspaceRegistry,
        new ApprovalPolicy(),
        auditService,
        localTaskStore,
        localTaskQueueService
);
```

- [x] **步骤 6：运行命令服务和工作台测试验证通过**

运行：

```bash
/Users/Masons/Ai/Maven/bin/mvn -Dmaven.repo.local=/Users/Masons/Ai/Maven_Ai_Store -q -pl server -Dtest=LocalCommandServiceTest,LocalTaskQueueServiceTest,WorkbenchLocalExecutionTest,WorkbenchServiceTest test
```

预期：四个测试类通过。

- [x] **步骤 7：提交**

```bash
git add server/src/main/java/com/matrixcode/localexecution/application/LocalCommandService.java
git add server/src/main/java/com/matrixcode/localexecution/application/LocalExecutionSummaryService.java
git add server/src/test/java/com/matrixcode/localexecution/LocalCommandServiceTest.java
git add server/src/test/java/com/matrixcode/localexecution/WorkbenchLocalExecutionTest.java
git add server/src/test/java/com/matrixcode/workbench/WorkbenchServiceTest.java
git commit -m "feat: 接入本地命令队列执行"
```

---

### 任务 4：暴露取消、日志接口并接入工作台摘要

**文件：**

- 修改：`server/src/main/java/com/matrixcode/localexecution/api/LocalExecutionController.java`
- 修改：`server/src/test/java/com/matrixcode/localexecution/LocalExecutionControllerTest.java`

- [x] **步骤 1：编写控制器失败测试**

在 `LocalExecutionControllerTest` 新增测试：

```java
@Test
void 可以通过接口取消本地长任务并查询日志() throws Exception {
    var workspaceResponse = mockMvc.perform(post("/api/projects/demo/local-execution/workspaces")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""
                            {"name":"当前项目","rootPath":"%s"}
                            """.formatted(escapePath(workspace))))
            .andExpect(status().isOk())
            .andReturn();
    var workspaceId = JsonPath.read(workspaceResponse.getResponse().getContentAsString(), "$.id").toString();
    var commandResponse = mockMvc.perform(post("/api/projects/demo/local-execution/commands")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""
                            {"workspaceId":"%s","actorId":"user-dev","command":"sleep 5"}
                            """.formatted(workspaceId)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.status").value("APPROVAL_PENDING"))
            .andReturn();
    var taskId = JsonPath.read(commandResponse.getResponse().getContentAsString(), "$.taskId").toString();

    mockMvc.perform(post("/api/projects/demo/local-execution/commands/%s/approval".formatted(taskId))
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""
                            {"actorId":"user-reviewer","decision":"ALLOW","note":"允许运行长任务"}
                            """))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.status").value("QUEUED"));

    mockMvc.perform(post("/api/projects/demo/local-execution/commands/%s/cancel".formatted(taskId))
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""
                            {"actorId":"user-reviewer","note":"停止验证"}
                            """))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.status").value("CANCELED"))
            .andExpect(jsonPath("$.canceledBy").value("user-reviewer"));

    mockMvc.perform(get("/api/projects/demo/local-execution/commands/%s/logs".formatted(taskId)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$[0].taskId").value(taskId));

    mockMvc.perform(get("/api/projects/demo/local-execution/summary"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.recentTasks[0].taskId").value(taskId))
            .andExpect(jsonPath("$.recentTaskLogs").isArray());
}
```

更新既有摘要断言，允许响应包含 `activeTasks` 和 `recentTaskLogs`。

- [x] **步骤 2：运行控制器测试验证失败**

运行：

```bash
/Users/Masons/Ai/Maven/bin/mvn -Dmaven.repo.local=/Users/Masons/Ai/Maven_Ai_Store -q -pl server -Dtest=LocalExecutionControllerTest test
```

预期：测试失败，错误包含取消接口 404 或日志接口 404。

- [x] **步骤 3：实现取消和日志接口**

修改 `LocalExecutionController.java`：

```java
@PostMapping("/commands/{taskId}/cancel")
public ExecutionTask cancelCommand(
        @PathVariable String projectId,
        @PathVariable String taskId,
        @RequestBody CancelCommand command
) {
    return commandService.cancel(projectId, taskId, command.actorId(), command.note());
}

@GetMapping("/commands/{taskId}/logs")
public List<LocalTaskLog> commandLogs(
        @PathVariable String projectId,
        @PathVariable String taskId
) {
    return commandService.logsForTask(projectId, taskId);
}

public record CancelCommand(String actorId, String note) {
}
```

增加 import：

```java
import com.matrixcode.localexecution.domain.LocalTaskLog;
```

- [x] **步骤 4：运行本地执行接口测试验证通过**

运行：

```bash
/Users/Masons/Ai/Maven/bin/mvn -Dmaven.repo.local=/Users/Masons/Ai/Maven_Ai_Store -q -pl server -Dtest=LocalExecutionControllerTest,LocalCommandServiceTest,LocalTaskQueueServiceTest test
```

预期：三个测试类通过。

- [x] **步骤 5：提交**

```bash
git add server/src/main/java/com/matrixcode/localexecution/api/LocalExecutionController.java
git add server/src/test/java/com/matrixcode/localexecution/LocalExecutionControllerTest.java
git commit -m "feat: 暴露本地任务取消和日志接口"
```

---

### 任务 5：扩展桌面 API、右侧卡片和取消交互

**文件：**

- 修改：`desktop/src/api/client.ts`
- 修改：`desktop/src/api/client.test.ts`
- 修改：`desktop/src/components/InspectorPanel.tsx`
- 修改：`desktop/src/App.tsx`
- 修改：`desktop/src/App.css`
- 修改：`desktop/src/test/App.test.tsx`

- [x] **步骤 1：编写桌面 API 失败测试**

修改 `client.test.ts` imports，增加：

```ts
cancelLocalExecutionTask,
loadLocalExecutionTaskLogs,
```

新增测试：

```ts
it('取消本地长任务并查询任务日志', async () => {
  const fetchMock = vi
    .fn()
    .mockResolvedValueOnce({
      ok: true,
      json: async () => ({ taskId: 'task-1', status: 'CANCELED' })
    })
    .mockResolvedValueOnce({
      ok: true,
      json: async () => [{ taskId: 'task-1', stream: 'SYSTEM', content: '任务已取消' }]
    });
  vi.stubGlobal('fetch', fetchMock);
  const input = { actorId: 'user-reviewer', note: '停止验证' };

  await cancelLocalExecutionTask('demo', 'task/1', input, 'http://localhost:8080');
  await loadLocalExecutionTaskLogs('demo', 'task/1', 'http://localhost:8080');

  expect(fetchMock).toHaveBeenCalledWith(
    'http://localhost:8080/api/projects/demo/local-execution/commands/task%2F1/cancel',
    {
      method: 'POST',
      headers: { Accept: 'application/json', 'Content-Type': 'application/json' },
      body: JSON.stringify(input)
    }
  );
  expect(fetchMock).toHaveBeenCalledWith(
    'http://localhost:8080/api/projects/demo/local-execution/commands/task%2F1/logs',
    {
      headers: { Accept: 'application/json' }
    }
  );
});
```

- [x] **步骤 2：运行桌面 API 测试验证失败**

运行：

```bash
cd desktop
npm test -- src/api/client.test.ts
```

预期：Vitest 失败，错误包含 `cancelLocalExecutionTask` 或 `loadLocalExecutionTaskLogs` 未导出。

- [x] **步骤 3：扩展桌面 API 类型和函数**

修改 `client.ts`：

```ts
export type ExecutionTaskStatus =
  | 'APPROVAL_PENDING'
  | 'DENIED'
  | 'QUEUED'
  | 'RUNNING'
  | 'SUCCESS'
  | 'FAILED'
  | 'CANCELED';
export type LocalTaskLogStream = 'STDOUT' | 'STDERR' | 'SYSTEM';
export type LocalTaskLog = {
  id: string;
  projectId: string;
  taskId: string;
  stream: LocalTaskLogStream;
  content: string;
  createdAt: string;
};
```

扩展 `ExecutionTask`：

```ts
canceledBy: string;
cancelNote: string;
canceledAt: string | null;
```

扩展 `LocalExecutionSummary`：

```ts
activeTasks: ExecutionTask[];
recentTaskLogs: LocalTaskLog[];
```

新增输入类型和函数：

```ts
export type LocalCommandCancelInput = {
  actorId: string;
  note?: string;
};

export function cancelLocalExecutionTask(
  projectId: string,
  taskId: string,
  input: LocalCommandCancelInput,
  serverUrl = matrixCodeServerUrl()
): Promise<ExecutionTask> {
  return requestJson<ExecutionTask>(
    projectUrl(serverUrl, projectId, `/local-execution/commands/${encodeURIComponent(taskId)}/cancel`),
    {
      method: 'POST',
      body: JSON.stringify(input)
    }
  );
}

export function loadLocalExecutionTaskLogs(
  projectId: string,
  taskId: string,
  serverUrl = matrixCodeServerUrl()
): Promise<LocalTaskLog[]> {
  return requestJson<LocalTaskLog[]>(
    projectUrl(serverUrl, projectId, `/local-execution/commands/${encodeURIComponent(taskId)}/logs`)
  );
}
```

- [x] **步骤 4：运行桌面 API 测试验证通过**

运行：

```bash
cd desktop
npm test -- src/api/client.test.ts
```

预期：`client.test.ts` 通过。

- [x] **步骤 5：编写桌面 UI 失败测试**

修改 `App.test.tsx`：

1. import 和 mock 增加：

```ts
cancelLocalExecutionTask,
```

2. `基础工作台.localExecution` 增加新字段：

```ts
activeTasks: [],
recentTaskLogs: [],
```

3. 每个 `ExecutionTask` fixture 增加：

```ts
canceledBy: '',
cancelNote: '',
canceledAt: null
```

4. 新增运行中任务工作台 fixture：

```ts
const 长任务工作台: ProjectWorkbench = {
  ...基础工作台,
  localExecution: {
    ...基础工作台.localExecution,
    recentTasks: [
      {
        ...基础工作台.localExecution.recentTasks[0],
        taskId: 'task-long',
        command: 'sleep 5',
        approvalDecision: 'ALLOW',
        status: 'RUNNING',
        approverId: 'user-reviewer',
        decidedAt: '2026-06-24T10:10:00Z'
      }
    ],
    activeTasks: [
      {
        ...基础工作台.localExecution.recentTasks[0],
        taskId: 'task-long',
        command: 'sleep 5',
        approvalDecision: 'ALLOW',
        status: 'RUNNING',
        approverId: 'user-reviewer',
        decidedAt: '2026-06-24T10:10:00Z'
      }
    ],
    recentTaskLogs: [
      {
        id: 'log-1',
        projectId: 'demo',
        taskId: 'task-long',
        stream: 'SYSTEM',
        content: '任务开始运行',
        createdAt: '2026-06-24T10:10:01Z'
      }
    ]
  }
};
```

5. 新增 UI 测试：

```ts
it('右侧本地执行代理展示运行中任务日志并可以取消', async () => {
  加载项目工作台
    .mockResolvedValueOnce(长任务工作台)
    .mockResolvedValueOnce({
      ...长任务工作台,
      localExecution: {
        ...长任务工作台.localExecution,
        activeTasks: [],
        recentTasks: [
          {
            ...长任务工作台.localExecution.recentTasks[0],
            status: 'CANCELED',
            canceledBy: 'user-reviewer',
            cancelNote: '用户在工作台右侧指标栏取消任务',
            canceledAt: '2026-06-24T10:11:00Z'
          }
        ]
      }
    });
  vi.mocked(cancelLocalExecutionTask).mockResolvedValue({
    ...长任务工作台.localExecution.recentTasks[0],
    status: 'CANCELED',
    canceledBy: 'user-reviewer',
    cancelNote: '用户在工作台右侧指标栏取消任务',
    canceledAt: '2026-06-24T10:11:00Z'
  });
  render(<App />);

  const 本地执行代理 = within(await screen.findByLabelText('本地执行代理'));
  expect(本地执行代理.getByText(/最近命令 运行中 · ALLOW · sleep 5/)).toBeTruthy();
  expect(本地执行代理.getByText(/SYSTEM · 任务开始运行/)).toBeTruthy();

  fireEvent.click(本地执行代理.getByRole('button', { name: '取消任务' }));

  await waitFor(() =>
    expect(vi.mocked(cancelLocalExecutionTask)).toHaveBeenCalledWith('demo', 'task-long', {
      actorId: 'user-reviewer',
      note: '用户在工作台右侧指标栏取消任务'
    })
  );
  await waitFor(() => expect(加载项目工作台).toHaveBeenCalledTimes(2));
});
```

- [x] **步骤 6：运行桌面 UI 测试验证失败**

运行：

```bash
cd desktop
npm test -- src/test/App.test.tsx
```

预期：Vitest 失败，错误包含取消函数未 mock、`activeTasks` 字段缺失、日志文案不存在或“取消任务”按钮不存在。

- [x] **步骤 7：实现 App 取消处理函数**

修改 `App.tsx` import：

```ts
cancelLocalExecutionTask,
```

新增状态：

```ts
const [cancelBusyTaskId, setCancelBusyTaskId] = useState<string | null>(null);
```

新增处理函数：

```ts
async function handleCancelLocalExecutionTask(taskId: string) {
  if (workbenchState.type !== 'ready') {
    return;
  }

  setCancelBusyTaskId(taskId);
  try {
    await cancelLocalExecutionTask(workbenchState.workbench.projectId, taskId, {
      actorId: localApprovalActorId,
      note: '用户在工作台右侧指标栏取消任务'
    });
    await refreshWorkbench({ keepCurrent: true });
  } finally {
    setCancelBusyTaskId(null);
  }
}
```

传给 `InspectorPanel`：

```tsx
cancelBusyTaskId={cancelBusyTaskId}
onCancelLocalExecutionTask={handleCancelLocalExecutionTask}
```

- [x] **步骤 8：实现右侧本地执行代理任务日志和取消按钮**

修改 `InspectorPanel.tsx`：

```ts
import type {
  ExecutionTaskStatus,
  LocalTaskLogStream,
  ...
} from '../api/client';
```

新增 props：

```ts
cancelBusyTaskId?: string | null;
onCancelLocalExecutionTask: (taskId: string) => Promise<void>;
```

新增状态标签：

```ts
const taskStatusLabels: Record<ExecutionTaskStatus, string> = {
  APPROVAL_PENDING: '待审批',
  DENIED: '已拒绝',
  QUEUED: '排队中',
  RUNNING: '运行中',
  SUCCESS: '成功',
  FAILED: '失败',
  CANCELED: '已取消'
};

const taskLogStreamLabels: Record<LocalTaskLogStream, string> = {
  STDOUT: '输出',
  STDERR: '错误',
  SYSTEM: 'SYSTEM'
};
```

选择展示任务和日志：

```ts
const activeTask = localExecution.activeTasks[0];
const recentTask = localExecution.recentTasks[0];
const pendingTask = localExecution.recentTasks.find((task) => task.status === 'APPROVAL_PENDING');
const taskForDisplay = pendingTask ?? activeTask ?? recentTask;
const taskLogs = localExecution.recentTaskLogs
  .filter((log) => !taskForDisplay || log.taskId === taskForDisplay.taskId)
  .slice(0, 3);
const canCancelTask = taskForDisplay?.status === 'QUEUED' || taskForDisplay?.status === 'RUNNING';
const cancelBusy = taskForDisplay ? cancelBusyTaskId === taskForDisplay.taskId : false;
```

把原本最近命令文案改为中文状态：

```tsx
最近命令 {taskStatusLabels[taskForDisplay.status]} · {taskForDisplay.approvalDecision} · {taskForDisplay.command}
```

在审批按钮后追加取消按钮：

```tsx
{canCancelTask ? (
  <div className="approval-actions" aria-label="本地任务取消操作">
    <button
      className="secondary-button approval-actions__button"
      disabled={cancelBusy}
      onClick={() => void onCancelLocalExecutionTask(taskForDisplay.taskId)}
      type="button"
    >
      取消任务
    </button>
  </div>
) : null}
```

展示最近日志：

```tsx
{taskLogs.length ? (
  <ul className="task-log-list" aria-label="本地任务最近日志">
    {taskLogs.map((log) => (
      <li key={log.id}>
        <span>
          {taskLogStreamLabels[log.stream]} · {log.content}
        </span>
      </li>
    ))}
  </ul>
) : null}
```

展示取消人：

```tsx
{taskForDisplay?.canceledBy ? <p className="form-hint">取消人：{taskForDisplay.canceledBy}</p> : null}
```

- [x] **步骤 9：补充样式**

修改 `App.css`：

```css
.task-log-list {
  display: grid;
  gap: 8px;
  margin: 10px 0 0;
  padding: 0;
  list-style: none;
}

.task-log-list li {
  border: 1px solid #272d35;
  border-radius: 8px;
  padding: 8px 10px;
  color: #b9c2cd;
  background: #12161c;
  font-size: 12px;
  line-height: 1.45;
}
```

- [x] **步骤 10：运行桌面 UI 测试验证通过**

运行：

```bash
cd desktop
npm test -- src/test/App.test.tsx
```

预期：`App.test.tsx` 通过。

- [x] **步骤 11：运行桌面类型检查和全量测试**

运行：

```bash
cd desktop
npm exec tsc -- --noEmit
npm test
```

预期：TypeScript 类型检查通过，桌面端测试通过。

- [x] **步骤 12：提交**

```bash
git add desktop/src/api/client.ts
git add desktop/src/api/client.test.ts
git add desktop/src/components/InspectorPanel.tsx
git add desktop/src/App.tsx
git add desktop/src/App.css
git add desktop/src/test/App.test.tsx
git commit -m "feat: 展示本地长任务日志和取消操作"
```

---

### 任务 6：整体验证和文档更新

**文件：**

- 修改：`docs/development/local-run.md`
- 修改：`docs/superpowers/plans/2026-06-24-matrixcode-local-task-queue.md`

- [x] **步骤 1：运行服务端全量测试**

运行：

```bash
/Users/Masons/Ai/Maven/bin/mvn -Dmaven.repo.local=/Users/Masons/Ai/Maven_Ai_Store -q -pl server test
```

再汇总 Surefire：

```bash
awk '/Tests run:/ {gsub(",", ""); run += $3; fail += $6; err += $9; skip += $12} END {printf "Tests run: %d, Failures: %d, Errors: %d, Skipped: %d\n", run, fail, err, skip}' server/target/surefire-reports/*.txt
```

预期：服务端全部测试通过，失败数和错误数均为 0。

- [x] **步骤 2：运行桌面端测试和构建**

运行：

```bash
cd desktop
npm test
npm run build
npm run tauri:build -- --help
```

预期：Vitest、TypeScript/Vite 构建和 Tauri CLI 帮助均通过。

- [x] **步骤 3：启动服务端做运行态验证**

确认 8080 未被占用：

```bash
lsof -nP -iTCP:8080 -sTCP:LISTEN
```

预期：无输出。

启动服务端：

```bash
/Users/Masons/Ai/Maven/bin/mvn -Dmaven.repo.local=/Users/Masons/Ai/Maven_Ai_Store -pl server spring-boot:run
```

另开终端执行验证脚本：

```bash
WORKSPACE_JSON=$(curl -sS -X POST http://localhost:8080/api/projects/demo/local-execution/workspaces \
  -H 'Content-Type: application/json' \
  -d '{"name":"MatrixCode 工作区","rootPath":"/Users/Masons/Ai/Codex/MatrixCode/.worktrees/mvp-vertical-slice"}')
WORKSPACE_ID=$(node -e 'console.log(JSON.parse(process.argv[1]).id)' "$WORKSPACE_JSON")

TASK_JSON=$(curl -sS -X POST http://localhost:8080/api/projects/demo/local-execution/commands \
  -H 'Content-Type: application/json' \
  -d "{\"workspaceId\":\"${WORKSPACE_ID}\",\"actorId\":\"user-dev\",\"command\":\"sleep 5\"}")
TASK_ID=$(node -e 'console.log(JSON.parse(process.argv[1]).taskId)' "$TASK_JSON")

curl -sS -X POST "http://localhost:8080/api/projects/demo/local-execution/commands/${TASK_ID}/approval" \
  -H 'Content-Type: application/json' \
  -d '{"actorId":"user-reviewer","decision":"ALLOW","note":"运行态验证允许执行"}'

curl -sS http://localhost:8080/api/projects/demo/workbench

curl -sS -X POST "http://localhost:8080/api/projects/demo/local-execution/commands/${TASK_ID}/cancel" \
  -H 'Content-Type: application/json' \
  -d '{"actorId":"user-reviewer","note":"运行态验证取消"}'

curl -sS "http://localhost:8080/api/projects/demo/local-execution/commands/${TASK_ID}/logs"
```

预期：

- 审批响应返回 `QUEUED` 或 `RUNNING`。
- 工作台响应包含 `localExecution.activeTasks` 或 `localExecution.recentTaskLogs`。
- 取消响应返回 `CANCELED`、`canceledBy=user-reviewer`。
- 日志响应包含 `SYSTEM` 日志。

提交 SSH 命令并尝试批准：

```bash
SSH_TASK_JSON=$(curl -sS -X POST http://localhost:8080/api/projects/demo/local-execution/commands \
  -H 'Content-Type: application/json' \
  -d "{\"workspaceId\":\"${WORKSPACE_ID}\",\"actorId\":\"user-ops\",\"command\":\"ssh prod systemctl restart app\"}")
SSH_TASK_ID=$(node -e 'console.log(JSON.parse(process.argv[1]).taskId)' "$SSH_TASK_JSON")
curl -sS -X POST "http://localhost:8080/api/projects/demo/local-execution/commands/${SSH_TASK_ID}/approval" \
  -H 'Content-Type: application/json' \
  -d '{"actorId":"user-reviewer","decision":"ALLOW","note":"验证 SSH 不入队"}'
```

预期：返回 `DENIED`，`safetyRejectionReason` 包含 `该命令不在第五阶段可批准执行范围内`，不会进入 `activeTasks`。

停止服务端后再次确认端口：

```bash
lsof -nP -iTCP:8080 -sTCP:LISTEN
```

预期：无输出。

- [x] **步骤 4：更新本地运行文档**

在 `docs/development/local-run.md` 的第六阶段验证后新增：

````markdown
## 第七阶段本地长任务队列验证

服务端启动后，先授权当前本地工作区：

```bash
WORKSPACE_JSON=$(curl -sS -X POST http://localhost:8080/api/projects/demo/local-execution/workspaces \
  -H 'Content-Type: application/json' \
  -d '{"name":"MatrixCode 工作区","rootPath":"/Users/Masons/Ai/Codex/MatrixCode/.worktrees/mvp-vertical-slice"}')
WORKSPACE_ID=$(node -e 'console.log(JSON.parse(process.argv[1]).id)' "$WORKSPACE_JSON")
```

提交一个需要人工审批的长任务并批准执行：

```bash
TASK_JSON=$(curl -sS -X POST http://localhost:8080/api/projects/demo/local-execution/commands \
  -H 'Content-Type: application/json' \
  -d "{\"workspaceId\":\"${WORKSPACE_ID}\",\"actorId\":\"user-dev\",\"command\":\"sleep 5\"}")
TASK_ID=$(node -e 'console.log(JSON.parse(process.argv[1]).taskId)' "$TASK_JSON")

curl -sS -X POST "http://localhost:8080/api/projects/demo/local-execution/commands/${TASK_ID}/approval" \
  -H 'Content-Type: application/json' \
  -d '{"actorId":"user-reviewer","decision":"ALLOW","note":"运行态验证允许执行"}'
```

响应应进入 `QUEUED` 或 `RUNNING`。查询工作台：

```bash
curl -sS http://localhost:8080/api/projects/demo/workbench
```

`localExecution.activeTasks` 或 `localExecution.recentTaskLogs` 应包含该任务。

取消任务并查询日志：

```bash
curl -sS -X POST "http://localhost:8080/api/projects/demo/local-execution/commands/${TASK_ID}/cancel" \
  -H 'Content-Type: application/json' \
  -d '{"actorId":"user-reviewer","note":"运行态验证取消"}'

curl -sS "http://localhost:8080/api/projects/demo/local-execution/commands/${TASK_ID}/logs"
```

取消响应应包含 `CANCELED` 和 `canceledBy=user-reviewer`。日志响应应包含 `SYSTEM` 日志。SSH、部署、凭证和危险命令即使人工批准，也不会进入长任务队列。
````

- [x] **步骤 5：勾选计划并追加验证记录**

在本计划末尾新增“第七阶段验证记录”，记录：

- 服务端全量测试统计。
- 桌面端测试、构建和 Tauri 命令入口。
- 运行态入队、取消、日志和 SSH 安全拒绝结果。
- 端口清理结果。

把任务 1 到任务 6 的完成步骤勾选为 `[x]`。

- [x] **步骤 6：检查文档和 diff**

运行：

```bash
rg -n "T(O)DO|T[B]D|F[I]XME|\\bplace(holder)\\b|\\bS[u]mmary\\b|\\bG[o]als\\b|\\bAcceptance C[r]iteria\\b" docs
```

预期：无输出。

运行：

```bash
rg --pcre2 -n "(?<!bin/)\\bm[v]n\\b" docs
```

预期：无输出。

运行：

```bash
git diff --check
```

预期：无输出，退出码 0。

- [x] **步骤 7：提交**

```bash
git add docs/development/local-run.md
git add docs/superpowers/plans/2026-06-24-matrixcode-local-task-queue.md
git commit -m "docs: 记录第七阶段长任务队列验证"
```

---

## 第七阶段验证记录

- 服务端全量测试：`/Users/Masons/Ai/Maven/bin/mvn -Dmaven.repo.local=/Users/Masons/Ai/Maven_Ai_Store -q -pl server test` 通过。Surefire 汇总为 `Tests run: 190, Failures: 0, Errors: 0, Skipped: 28`。
- 桌面端测试：`cd desktop && npm test` 通过，`2` 个测试文件、`43` 个测试通过。
- 桌面端构建：`cd desktop && npm run build` 通过，TypeScript 检查和 Vite 生产构建均完成。
- Tauri 命令入口：`cd desktop && npm run tauri:build -- --help` 通过，CLI 正常输出构建帮助。
- 运行态验证前端口检查：启动服务端前 `lsof -nP -iTCP:8080 -sTCP:LISTEN` 无输出。
- 运行态长任务：授权本地工作区返回 `AUTHORIZED`；提交 `sleep 20` 返回 `APPROVAL_PENDING` 和 `ASK`；审批通过返回 `QUEUED`；工作台返回 `activeTaskCountBeforeCancel=1` 且能通过 `activeTasks` 或 `recentTaskLogs` 看到该任务。
- 运行态取消和日志：取消响应返回 `CANCELED`，`canceledBy=user-reviewer`；任务日志包含 `SYSTEM` 日志，共返回 `3` 条。
- 运行态安全边界：提交 `ssh prod systemctl restart app` 后尝试人工批准，响应返回 `DENIED`，`safetyRejectionReason=该命令不在第五阶段可批准执行范围内`，且该 SSH 任务未进入 `activeTasks`。
- 浏览器验证：在 `http://127.0.0.1:5173/` 打开桌面端，右侧“本地执行代理”能展示运行中任务日志和“取消任务”按钮；点击取消后刷新为“已取消”，展示取消人、取消说明和取消日志；浏览器控制台无 error。
- 运行态验证后端口清理：停止服务端后再次运行 `lsof -nP -iTCP:8080 -sTCP:LISTEN` 无输出。

## 自检记录

- 规格覆盖：入队、运行、日志、取消、审批接入、工作台摘要、桌面展示、运行态验证和安全边界均有对应任务。
- 范围控制：真实 SSH、远程部署、Docker Compose 生命周期、数据库持久化、任务重试、并发池配置、多人权限、登录鉴权和实时日志直连均未纳入本计划。
- 类型一致性：服务端 `ExecutionTaskStatus`、`LocalTaskLog`、`LocalExecutionSummary` 与桌面端同名类型字段保持一致。
- 安全边界：队列只接收已通过审批策略的本地命令；人工审批通过后仍需安全校验。
- 验证命令：服务端命令均使用 `/Users/Masons/Ai/Maven/bin/mvn -Dmaven.repo.local=/Users/Masons/Ai/Maven_Ai_Store`。
