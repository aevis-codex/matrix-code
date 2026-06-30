# MatrixCode 第十五阶段本地执行状态轻量持久化实现计划

> **面向 AI 代理的工作者：** 必需子技能：使用 superpowers:subagent-driven-development（推荐）或 superpowers:executing-plans 逐任务实现此计划。步骤使用复选框（`- [ ]`）语法来跟踪进度。

**目标：** 让本地执行代理的授权工作区、最近任务、任务日志和审批审计通过 JSON 快照跨服务端重启恢复。

**架构：** 新增本地执行快照存储层，Spring 环境默认写入 `.matrixcode/local-execution.json`，手写单元测试继续使用内存存储。现有 `WorkspaceRegistry`、`LocalTaskStore` 和 `AuditService` 在构造时恢复各自分区，并在状态变化后写回同一个合并快照。

**技术栈：** Java 21、Spring Boot、Jackson、JUnit 5、AssertJ、MockMvc、React、TypeScript、Vitest、Vite。

---

## 文件结构

- 创建：`server/src/main/java/com/matrixcode/localexecution/application/LocalExecutionSnapshot.java`
  - 表达可写入 JSON 的本地执行快照。
- 创建：`server/src/main/java/com/matrixcode/localexecution/application/LocalExecutionStateStore.java`
  - 定义加载快照和分区保存接口。
- 创建：`server/src/main/java/com/matrixcode/localexecution/application/InMemoryLocalExecutionStateStore.java`
  - 给手写单元测试使用。
- 创建：`server/src/main/java/com/matrixcode/localexecution/application/LocalExecutionStorageProperties.java`
  - 暴露 `matrixcode.local-execution.storage-path` 配置。
- 创建：`server/src/main/java/com/matrixcode/localexecution/application/FileLocalExecutionStateStore.java`
  - 负责 JSON 快照读写、分区合并和原子替换。
- 修改：`server/src/main/java/com/matrixcode/localexecution/application/WorkspaceRegistry.java`
  - 启动恢复工作区，授权、撤销和访问后保存工作区分区。
- 修改：`server/src/main/java/com/matrixcode/localexecution/application/LocalTaskStore.java`
  - 启动恢复任务和日志，任务或日志变更后保存任务分区。
- 修改：`server/src/main/java/com/matrixcode/approval/application/AuditService.java`
  - 启动恢复审计记录，新增审计后保存审计分区。
- 创建：`server/src/test/java/com/matrixcode/localexecution/FileLocalExecutionStateStoreTest.java`
  - 覆盖文件存储、分区合并和损坏文件容错。
- 修改：`server/src/test/java/com/matrixcode/localexecution/WorkspaceRegistryTest.java`
  - 覆盖工作区恢复和撤销状态恢复。
- 修改：`server/src/test/java/com/matrixcode/approval/ApprovalPolicyTest.java`
  - 如果实际文件名不同，按现有审计测试文件调整；覆盖审计恢复。
- 修改：`server/src/test/java/com/matrixcode/localexecution/LocalTaskStoreTest.java`
  - 覆盖任务、日志和运行中任务重启取消恢复。
- 创建：`server/src/test/java/com/matrixcode/localexecution/LocalExecutionPersistenceSpringTest.java`
  - 用隔离存储路径启动两次 Spring 上下文，验证工作台重启恢复。
- 修改：`docs/development/local-run.md`
  - 增加第十五阶段重启恢复验证说明。

## 任务 1：本地执行快照存储

**文件：**
- 创建：`server/src/main/java/com/matrixcode/localexecution/application/LocalExecutionSnapshot.java`
- 创建：`server/src/main/java/com/matrixcode/localexecution/application/LocalExecutionStateStore.java`
- 创建：`server/src/main/java/com/matrixcode/localexecution/application/InMemoryLocalExecutionStateStore.java`
- 创建：`server/src/main/java/com/matrixcode/localexecution/application/LocalExecutionStorageProperties.java`
- 创建：`server/src/main/java/com/matrixcode/localexecution/application/FileLocalExecutionStateStore.java`
- 创建：`server/src/test/java/com/matrixcode/localexecution/FileLocalExecutionStateStoreTest.java`

- [x] **步骤 1：编写失败的文件存储测试**

创建 `FileLocalExecutionStateStoreTest`，覆盖空文件、分区合并和损坏文件容错：

```java
package com.matrixcode.localexecution;

import com.fasterxml.jackson.databind.json.JsonMapper;
import com.matrixcode.approval.domain.ApprovalDecision;
import com.matrixcode.approval.domain.AuditRecord;
import com.matrixcode.localexecution.application.FileLocalExecutionStateStore;
import com.matrixcode.localexecution.application.LocalExecutionSnapshot;
import com.matrixcode.localexecution.application.LocalExecutionStorageProperties;
import com.matrixcode.localexecution.domain.ExecutionTask;
import com.matrixcode.localexecution.domain.ExecutionTaskStatus;
import com.matrixcode.localexecution.domain.LocalTaskLog;
import com.matrixcode.localexecution.domain.LocalTaskLogStream;
import com.matrixcode.localexecution.domain.WorkspaceAuthorization;
import com.matrixcode.localexecution.domain.WorkspaceStatus;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class FileLocalExecutionStateStoreTest {

    @TempDir
    Path tempDir;

    @Test
    void 文件不存在时加载空快照() {
        var store = store(tempDir.resolve("missing/local-execution.json"));

        var snapshot = store.load();

        assertThat(snapshot.version()).isEqualTo(1);
        assertThat(snapshot.workspaces()).isEmpty();
        assertThat(snapshot.tasks()).isEmpty();
        assertThat(snapshot.taskLogs()).isEmpty();
        assertThat(snapshot.auditRecords()).isEmpty();
    }

    @Test
    void 分区更新会保留其他分区() {
        var path = tempDir.resolve("state/local-execution.json");
        var store = store(path);

        store.saveWorkspaces(List.of(workspace()));
        store.saveTasks(
                Map.of("demo", List.of(task("task-1", ExecutionTaskStatus.SUCCESS))),
                Map.of("demo", Map.of("task-1", List.of(log("task-1"))))
        );
        store.saveAuditRecords(List.of(audit("task-1")));

        var loaded = store(path).load();
        assertThat(loaded.workspaces()).hasSize(1);
        assertThat(loaded.tasks().get("demo")).hasSize(1);
        assertThat(loaded.taskLogs().get("demo").get("task-1")).hasSize(1);
        assertThat(loaded.auditRecords()).hasSize(1);
    }

    @Test
    void 文件损坏时加载空快照且保留原文件() throws Exception {
        var path = tempDir.resolve("state/local-execution.json");
        Files.createDirectories(path.getParent());
        Files.writeString(path, "{broken");

        var loaded = store(path).load();

        assertThat(loaded.workspaces()).isEmpty();
        assertThat(Files.readString(path)).isEqualTo("{broken");
    }

    private FileLocalExecutionStateStore store(Path path) {
        var properties = new LocalExecutionStorageProperties();
        properties.setStoragePath(path);
        var mapper = JsonMapper.builder().findAndAddModules().build();
        return new FileLocalExecutionStateStore(mapper, properties);
    }

    private WorkspaceAuthorization workspace() {
        return new WorkspaceAuthorization("workspace-1", "demo", "当前项目", tempDir.toString(),
                WorkspaceStatus.AUTHORIZED, Instant.parse("2026-06-25T08:00:00Z"),
                Instant.parse("2026-06-25T08:00:00Z"));
    }

    private ExecutionTask task(String taskId, ExecutionTaskStatus status) {
        return new ExecutionTask(taskId, "demo", "workspace-1", "user-ops", "SHELL", "git status",
                ApprovalDecision.ASK, status, null, "", "", 0,
                Instant.parse("2026-06-25T08:00:00Z"));
    }

    private LocalTaskLog log(String taskId) {
        return new LocalTaskLog("log-1", "demo", taskId, LocalTaskLogStream.SYSTEM,
                "任务运行完成，退出码：0", Instant.parse("2026-06-25T08:01:00Z"));
    }

    private AuditRecord audit(String taskId) {
        return new AuditRecord("audit-1", taskId, "user-ops", "SHELL", tempDir.toString(),
                "git status", ApprovalDecision.ASK, Instant.parse("2026-06-25T08:00:00Z"));
    }
}
```

- [x] **步骤 2：运行测试验证失败**

```bash
/Users/Masons/Ai/Maven/bin/mvn -Dmaven.repo.local=/Users/Masons/Ai/Maven_Ai_Store -q -pl server -Dtest=FileLocalExecutionStateStoreTest test
```

预期：编译失败，提示本地执行存储相关类不存在。

- [x] **步骤 3：实现快照和存储类**

创建 `LocalExecutionSnapshot`：

```java
package com.matrixcode.localexecution.application;

import com.matrixcode.approval.domain.AuditRecord;
import com.matrixcode.localexecution.domain.ExecutionTask;
import com.matrixcode.localexecution.domain.LocalTaskLog;
import com.matrixcode.localexecution.domain.WorkspaceAuthorization;

import java.util.List;
import java.util.Map;

public record LocalExecutionSnapshot(
        int version,
        List<WorkspaceAuthorization> workspaces,
        Map<String, List<ExecutionTask>> tasks,
        Map<String, Map<String, List<LocalTaskLog>>> taskLogs,
        List<AuditRecord> auditRecords
) {
    public LocalExecutionSnapshot {
        workspaces = List.copyOf(workspaces == null ? List.of() : workspaces);
        tasks = Map.copyOf(tasks == null ? Map.of() : tasks);
        taskLogs = Map.copyOf(taskLogs == null ? Map.of() : taskLogs);
        auditRecords = List.copyOf(auditRecords == null ? List.of() : auditRecords);
    }

    public static LocalExecutionSnapshot empty() {
        return new LocalExecutionSnapshot(1, List.of(), Map.of(), Map.of(), List.of());
    }
}
```

创建 `LocalExecutionStateStore`：

```java
package com.matrixcode.localexecution.application;

import com.matrixcode.approval.domain.AuditRecord;
import com.matrixcode.localexecution.domain.ExecutionTask;
import com.matrixcode.localexecution.domain.LocalTaskLog;
import com.matrixcode.localexecution.domain.WorkspaceAuthorization;

import java.util.List;
import java.util.Map;

public interface LocalExecutionStateStore {
    LocalExecutionSnapshot load();

    void saveWorkspaces(List<WorkspaceAuthorization> workspaces);

    void saveTasks(Map<String, List<ExecutionTask>> tasks, Map<String, Map<String, List<LocalTaskLog>>> taskLogs);

    void saveAuditRecords(List<AuditRecord> auditRecords);
}
```

创建 `InMemoryLocalExecutionStateStore`：

```java
package com.matrixcode.localexecution.application;

import com.matrixcode.approval.domain.AuditRecord;
import com.matrixcode.localexecution.domain.ExecutionTask;
import com.matrixcode.localexecution.domain.LocalTaskLog;
import com.matrixcode.localexecution.domain.WorkspaceAuthorization;

import java.util.List;
import java.util.Map;

public class InMemoryLocalExecutionStateStore implements LocalExecutionStateStore {
    private LocalExecutionSnapshot snapshot = LocalExecutionSnapshot.empty();

    @Override
    public synchronized LocalExecutionSnapshot load() {
        return snapshot;
    }

    @Override
    public synchronized void saveWorkspaces(List<WorkspaceAuthorization> workspaces) {
        snapshot = new LocalExecutionSnapshot(1, workspaces, snapshot.tasks(), snapshot.taskLogs(), snapshot.auditRecords());
    }

    @Override
    public synchronized void saveTasks(Map<String, List<ExecutionTask>> tasks, Map<String, Map<String, List<LocalTaskLog>>> taskLogs) {
        snapshot = new LocalExecutionSnapshot(1, snapshot.workspaces(), tasks, taskLogs, snapshot.auditRecords());
    }

    @Override
    public synchronized void saveAuditRecords(List<AuditRecord> auditRecords) {
        snapshot = new LocalExecutionSnapshot(1, snapshot.workspaces(), snapshot.tasks(), snapshot.taskLogs(), auditRecords);
    }
}
```

创建 `LocalExecutionStorageProperties`，默认路径为 `.matrixcode/local-execution.json`。

创建 `FileLocalExecutionStateStore`，结构参考第十四阶段的 `FileRuntimeNotificationStore`，但内部维护当前快照，并在 `saveWorkspaces`、`saveTasks`、`saveAuditRecords` 中合并其他分区后写入文件。写入失败抛出 `IllegalStateException("本地执行状态存储写入失败：" + exception.getMessage(), exception)`。

- [x] **步骤 4：运行测试验证通过**

```bash
/Users/Masons/Ai/Maven/bin/mvn -Dmaven.repo.local=/Users/Masons/Ai/Maven_Ai_Store -q -pl server -Dtest=FileLocalExecutionStateStoreTest test
```

预期：`FileLocalExecutionStateStoreTest` 全部通过。

- [x] **步骤 5：Commit**

```bash
git add server/src/main/java/com/matrixcode/localexecution/application/LocalExecutionSnapshot.java server/src/main/java/com/matrixcode/localexecution/application/LocalExecutionStateStore.java server/src/main/java/com/matrixcode/localexecution/application/InMemoryLocalExecutionStateStore.java server/src/main/java/com/matrixcode/localexecution/application/LocalExecutionStorageProperties.java server/src/main/java/com/matrixcode/localexecution/application/FileLocalExecutionStateStore.java server/src/test/java/com/matrixcode/localexecution/FileLocalExecutionStateStoreTest.java
git commit -m "feat(服务端): 添加本地执行状态文件存储"
```

## 任务 2：工作区和审计接入持久化

**文件：**
- 修改：`server/src/main/java/com/matrixcode/localexecution/application/WorkspaceRegistry.java`
- 修改：`server/src/main/java/com/matrixcode/approval/application/AuditService.java`
- 修改：`server/src/test/java/com/matrixcode/localexecution/WorkspaceRegistryTest.java`
- 修改：`server/src/test/java/com/matrixcode/approval/ApprovalPolicyTest.java` 或现有审计测试文件

- [x] **步骤 1：编写失败的工作区恢复测试**

在 `WorkspaceRegistryTest` 增加：

```java
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
```

如果测试类没有 `@TempDir Path tempDir`，先补上该字段。

- [x] **步骤 2：编写失败的审计恢复测试**

在现有审计测试文件增加：

```java
@Test
void 重建服务后恢复审计记录() {
    var store = new InMemoryLocalExecutionStateStore();
    var auditService = new AuditService(store);
    auditService.record(new ToolAction("task-1", "user-ops", "SHELL", "git status", "/repo", false), ApprovalDecision.ASK);

    var restored = new AuditService(store);

    assertThat(restored.records()).hasSize(1);
    assertThat(restored.records().getFirst().summary()).isEqualTo("git status");
}
```

- [x] **步骤 3：运行测试验证失败**

```bash
/Users/Masons/Ai/Maven/bin/mvn -Dmaven.repo.local=/Users/Masons/Ai/Maven_Ai_Store -q -pl server -Dtest=WorkspaceRegistryTest,ApprovalPolicyTest test
```

预期：编译失败，提示 `WorkspaceRegistry` 和 `AuditService` 还不支持传入 `LocalExecutionStateStore`。

- [x] **步骤 4：实现工作区持久化**

在 `WorkspaceRegistry` 增加 store 字段、无参构造和注入构造：

```java
private final LocalExecutionStateStore store;

public WorkspaceRegistry() {
    this(new InMemoryLocalExecutionStateStore());
}

@Autowired
public WorkspaceRegistry(LocalExecutionStateStore store) {
    this.store = store;
    restore(store.load().workspaces());
}
```

在 `authorize`、`revoke` 和 `requireAuthorized` 写回新状态后调用 `persist()`。`persist()` 保存 `List.copyOf(workspaces.values())`，`restore` 把快照里的工作区按 `id` 放回 map。

- [x] **步骤 5：实现审计持久化**

在 `AuditService` 增加 store 字段、无参构造和注入构造。构造时把 `store.load().auditRecords()` 放入 `records`。`record` 新增审计后调用 `store.saveAuditRecords(List.copyOf(records))`。

- [x] **步骤 6：运行测试验证通过**

```bash
/Users/Masons/Ai/Maven/bin/mvn -Dmaven.repo.local=/Users/Masons/Ai/Maven_Ai_Store -q -pl server -Dtest=WorkspaceRegistryTest,ApprovalPolicyTest test
```

预期：目标测试全部通过。

- [x] **步骤 7：Commit**

```bash
git add server/src/main/java/com/matrixcode/localexecution/application/WorkspaceRegistry.java server/src/main/java/com/matrixcode/approval/application/AuditService.java server/src/test/java/com/matrixcode/localexecution/WorkspaceRegistryTest.java server/src/test/java/com/matrixcode/approval/ApprovalPolicyTest.java
git commit -m "feat(服务端): 持久化工作区和审批审计"
```

## 任务 3：任务和日志接入持久化

**文件：**
- 修改：`server/src/main/java/com/matrixcode/localexecution/application/LocalTaskStore.java`
- 修改：`server/src/test/java/com/matrixcode/localexecution/LocalTaskStoreTest.java`

- [x] **步骤 1：编写失败的任务恢复测试**

在 `LocalTaskStoreTest` 增加：

```java
@Test
void 重建服务后恢复任务和日志() {
    var store = new InMemoryLocalExecutionStateStore();
    var taskStore = new LocalTaskStore(store);
    taskStore.record(task("task-1", ExecutionTaskStatus.SUCCESS));
    taskStore.appendLog("demo", "task-1", LocalTaskLogStream.SYSTEM, "任务运行完成，退出码：0");

    var restored = new LocalTaskStore(store);

    assertThat(restored.recentTasks("demo")).hasSize(1);
    assertThat(restored.recentLogs("demo")).hasSize(1);
    assertThat(restored.recentLogs("demo").getFirst().content()).contains("退出码：0");
}
```

- [x] **步骤 2：编写失败的运行中任务恢复测试**

继续增加：

```java
@Test
void 恢复运行中任务时转为已取消并追加系统日志() {
    var store = new InMemoryLocalExecutionStateStore();
    store.saveTasks(Map.of("demo", List.of(task("task-running", ExecutionTaskStatus.RUNNING))), Map.of());

    var restored = new LocalTaskStore(store);

    assertThat(restored.recentTasks("demo").getFirst().status()).isEqualTo(ExecutionTaskStatus.CANCELED);
    assertThat(restored.recentLogs("demo")).anySatisfy(log ->
            assertThat(log.content()).contains("服务重启后任务已停止")
    );
    assertThat(store.load().tasks().get("demo").getFirst().status()).isEqualTo(ExecutionTaskStatus.CANCELED);
}
```

测试辅助 `task` 使用现有 `ExecutionTask` 构造函数：

```java
private ExecutionTask task(String taskId, ExecutionTaskStatus status) {
    return new ExecutionTask(taskId, "demo", "workspace-1", "user-ops", "SHELL", "git status",
            ApprovalDecision.ASK, status, null, "", "", 0,
            Instant.parse("2026-06-25T08:00:00Z"));
}
```

- [x] **步骤 3：运行测试验证失败**

```bash
/Users/Masons/Ai/Maven/bin/mvn -Dmaven.repo.local=/Users/Masons/Ai/Maven_Ai_Store -q -pl server -Dtest=LocalTaskStoreTest test
```

预期：编译失败，提示 `LocalTaskStore` 不支持传入 `LocalExecutionStateStore`，或断言显示任务未恢复。

- [x] **步骤 4：实现任务和日志持久化**

在 `LocalTaskStore` 增加 store 字段、无参构造和注入构造：

```java
private final LocalExecutionStateStore store;

public LocalTaskStore() {
    this(new InMemoryLocalExecutionStateStore());
}

@Autowired
public LocalTaskStore(LocalExecutionStateStore store) {
    this.store = store;
    restore(store.load());
}
```

恢复逻辑：

- 按项目重建 `ProjectTaskState`。
- 恢复 `APPROVAL_PENDING`、`DENIED`、`SUCCESS`、`FAILED`、`CANCELED` 原状态。
- 将 `QUEUED` 和 `RUNNING` 转成 `CANCELED`，取消人写 `system`，取消备注写 `服务重启后任务已停止`，取消时间使用当前时间。
- 对被转成取消的任务追加一条 `SYSTEM` 日志。
- 如果发生状态转换，构造结束前保存一次任务分区。

在 `record`、`recordNew`、`replace` 和 `appendLog` 变更成功后调用 `persist()`。`persist()` 保存按项目的任务列表和按项目、任务分组的日志列表。

- [x] **步骤 5：运行测试验证通过**

```bash
/Users/Masons/Ai/Maven/bin/mvn -Dmaven.repo.local=/Users/Masons/Ai/Maven_Ai_Store -q -pl server -Dtest=LocalTaskStoreTest test
```

预期：`LocalTaskStoreTest` 全部通过。

- [x] **步骤 6：Commit**

```bash
git add server/src/main/java/com/matrixcode/localexecution/application/LocalTaskStore.java server/src/test/java/com/matrixcode/localexecution/LocalTaskStoreTest.java
git commit -m "feat(服务端): 持久化本地任务和日志"
```

## 任务 4：Spring 重启验证、文档和全量验证

**文件：**
- 创建：`server/src/test/java/com/matrixcode/localexecution/LocalExecutionPersistenceSpringTest.java`
- 修改：`docs/development/local-run.md`
- 修改：`docs/superpowers/plans/2026-06-25-matrixcode-local-execution-persistence.md`

- [x] **步骤 1：编写失败的 Spring 重启测试**

创建 `LocalExecutionPersistenceSpringTest`。测试流程：

1. 使用隔离的 `local-execution.json` 和 `runtime-notifications.json` 路径启动第一个 Spring 上下文。
2. 通过 MockMvc 授权工作区。
3. 提交一条 `git status` 待审批任务。
4. 提交一条 `sleep 1`，批准执行并轮询工作台直到成功。
5. 关闭第一个上下文。
6. 使用同一路径启动第二个 Spring 上下文。
7. 读取工作台，断言工作区、待审批任务、成功任务、日志和审计记录都恢复。
8. 对恢复后的待审批任务执行拒绝，断言返回 `DENIED`。

测试骨架：

```java
class LocalExecutionPersistenceSpringTest {

    @TempDir
    Path tempDir;

    @Test
    void 重启Spring上下文后工作台恢复本地执行状态() throws Exception {
        var localState = tempDir.resolve("local-execution.json");
        var runtimeState = tempDir.resolve("runtime-notifications.json");
        String pendingTaskId;

        try (var context = startContext(localState, runtimeState)) {
            var mockMvc = mockMvc(context);
            var workspaceId = authorizeWorkspace(mockMvc);
            pendingTaskId = submitCommand(mockMvc, workspaceId, "git status");
            var sleepTaskId = submitCommand(mockMvc, workspaceId, "sleep 1");
            approve(mockMvc, sleepTaskId);
            waitForTaskStatus(mockMvc, sleepTaskId, "SUCCESS");
        }

        try (var context = startContext(localState, runtimeState)) {
            var mockMvc = mockMvc(context);
            mockMvc.perform(get("/api/projects/demo/workbench"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.localExecution.workspaces[0].name").value("当前项目"))
                    .andExpect(jsonPath("$.localExecution.recentTasks[*].taskId").value(hasItems(pendingTaskId)))
                    .andExpect(jsonPath("$.localExecution.recentTaskLogs").isNotEmpty())
                    .andExpect(jsonPath("$.localExecution.recentAuditRecords").isNotEmpty());

            mockMvc.perform(post("/api/projects/demo/local-execution/commands/" + pendingTaskId + "/approval")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"actorId\":\"user-reviewer\",\"decision\":\"DENY\",\"note\":\"重启后拒绝\"}"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.status").value("DENIED"));
        }
    }
}
```

补齐辅助方法时使用 `SpringApplicationBuilder`、`MockMvcBuilders.webAppContextSetup` 和现有 JSON 解析方式。命令和路径都来自测试临时目录，避免污染默认 `.matrixcode`。

- [x] **步骤 2：运行测试验证失败**

```bash
/Users/Masons/Ai/Maven/bin/mvn -Dmaven.repo.local=/Users/Masons/Ai/Maven_Ai_Store -q -pl server -Dtest=LocalExecutionPersistenceSpringTest test
```

预期：重启后工作台断言失败，因为 Spring 链路尚未完整恢复本地执行状态。

- [x] **步骤 3：修复 Spring 链路缺口**

如果测试暴露构造注入冲突、快照路径绑定或恢复后审批失败，按以下原则修复：

- Spring 默认只注入一个 `FileLocalExecutionStateStore`。
- 手写单元测试继续可以用无参构造。
- 恢复后的待审批任务批准或拒绝时，继续使用恢复后的工作区路径。
- 不为恢复后的运行中任务重新排队。

- [x] **步骤 4：运行 Spring 重启测试验证通过**

```bash
/Users/Masons/Ai/Maven/bin/mvn -Dmaven.repo.local=/Users/Masons/Ai/Maven_Ai_Store -q -pl server -Dtest=LocalExecutionPersistenceSpringTest test
```

预期：`LocalExecutionPersistenceSpringTest` 全部通过。

- [x] **步骤 5：Commit Spring 重启测试**

```bash
git add server/src/test/java/com/matrixcode/localexecution/LocalExecutionPersistenceSpringTest.java
git commit -m "test(服务端): 验证本地执行状态重启恢复"
```

- [x] **步骤 6：更新本地运行文档**

在 `docs/development/local-run.md` 的第十四阶段后新增第十五阶段说明：

````markdown
## 第十五阶段本地执行状态轻量持久化验证

服务端默认把本地执行状态写入 `.matrixcode/local-execution.json`。如需指定路径，可设置：

```bash
MATRIXCODE_LOCAL_EXECUTION_STORAGE_PATH=/tmp/matrixcode-local-execution.json /Users/Masons/Ai/Maven/bin/mvn -Dmaven.repo.local=/Users/Masons/Ai/Maven_Ai_Store -pl server spring-boot:run
```

授权工作区后，提交一条待审批 `git status`，再批准一条 `sleep 3` 并等待成功。停止服务端，再用同一个本地执行状态路径重启。桌面端刷新后，「本地执行代理」应继续显示授权工作区、最近任务、最近日志和审批审计。恢复后的待审批任务仍可拒绝或批准，批准时会重新校验工作区和安全策略。
````

- [x] **步骤 7：运行全量服务端测试**

```bash
/Users/Masons/Ai/Maven/bin/mvn -Dmaven.repo.local=/Users/Masons/Ai/Maven_Ai_Store -q -pl server test
```

预期：服务端测试全部通过。

- [x] **步骤 8：运行全量桌面端测试**

```bash
cd desktop && npm test
```

预期：桌面端测试全部通过。

- [x] **步骤 9：运行桌面端构建和 Tauri 检查**

```bash
cd desktop && npm run build
cd desktop && npm run tauri:build -- --help
```

预期：两个命令退出码均为 0。

- [x] **步骤 10：运行文档和 diff 检查**

```bash
rg -n "T(O)DO|T[B]D|F[I]XME|待[定]|占[位]|place(holder)|S[u]mmary|G[o]als|Acceptance C[r]iteria" docs/superpowers/specs/2026-06-25-matrixcode-local-execution-persistence-design.md docs/superpowers/plans/2026-06-25-matrixcode-local-execution-persistence.md docs/development/local-run.md
rg --pcre2 -n "(?<!bin/)\\bm[v]n\\b" docs/superpowers/specs/2026-06-25-matrixcode-local-execution-persistence-design.md docs/superpowers/plans/2026-06-25-matrixcode-local-execution-persistence.md docs/development/local-run.md
git diff --check
```

预期：前两个搜索命令无匹配，`git diff --check` 退出码为 0。

- [x] **步骤 11：浏览器重启验证**

执行过程：

1. 使用临时本地执行状态文件路径和临时提醒状态文件路径启动服务端，启动 Vite。
2. 通过 API 授权当前工作区，提交待审批 `git status`，批准 `sleep 3` 并等待成功。
3. 打开桌面页，确认右侧「本地执行代理」显示工作区、待审批任务、最近日志和审计记录。
4. 停止服务端，使用同一本地执行状态路径重启。
5. 刷新桌面页，确认「本地执行代理」仍显示授权工作区、两条任务、最近日志和审计记录。
6. 对恢复后的 `git status` 执行拒绝，确认状态变为 `DENIED`。
7. 检查浏览器控制台无 error。
8. 停止服务端和 Vite，确认端口释放。

- [x] **步骤 12：记录验证证据并提交文档**

把命令、退出码、测试数量和浏览器重启验证现象写入本计划执行记录区域。然后提交：

```bash
git add docs/development/local-run.md docs/superpowers/plans/2026-06-25-matrixcode-local-execution-persistence.md
git commit -m "docs: 记录第十五阶段本地执行状态验证"
```

## 执行记录

执行阶段按任务逐项更新这里，记录每个验证命令的退出码和关键输出。

### 任务 1：本地执行快照存储

- 红灯命令：`/Users/Masons/Ai/Maven/bin/mvn -Dmaven.repo.local=/Users/Masons/Ai/Maven_Ai_Store -q -pl server -Dtest=FileLocalExecutionStateStoreTest test`，退出码 1，编译失败，提示 `FileLocalExecutionStateStore` 和 `LocalExecutionStorageProperties` 不存在。
- 绿灯命令：同上，退出码 0；Surefire：`FileLocalExecutionStateStoreTest` 3 条测试，0 失败，0 错误，0 跳过。
- 空白检查：`git diff --check` 通过。
- 提交：`9a722ff feat(服务端): 添加本地执行状态文件存储`。

### 任务 2：工作区和审批审计接入持久化

- 红灯命令：`/Users/Masons/Ai/Maven/bin/mvn -Dmaven.repo.local=/Users/Masons/Ai/Maven_Ai_Store -q -pl server -Dtest=WorkspaceRegistryTest,ApprovalPolicyTest test`，退出码 1，提示 `WorkspaceRegistry` 和 `AuditService` 尚不支持传入 `InMemoryLocalExecutionStateStore`。
- 绿灯命令：同上，退出码 0；`WorkspaceRegistryTest` 5 条、`ApprovalPolicyTest` 16 条均通过。
- 空白检查：`git diff --check` 通过。
- 提交：`ef33df7 feat(服务端): 持久化工作区和审批审计`。

### 任务 3：任务和日志接入持久化

- 红灯命令：`/Users/Masons/Ai/Maven/bin/mvn -Dmaven.repo.local=/Users/Masons/Ai/Maven_Ai_Store -q -pl server -Dtest=LocalTaskStoreTest test`，退出码 1，编译失败，提示 `LocalTaskStore` 只有无参构造。
- 绿灯命令：同上，退出码 0；Surefire：`LocalTaskStoreTest` 12 条测试，0 失败，0 错误，0 跳过。
- 空白检查：`git diff --check` 通过。
- 提交：`5ae5cf7 feat(服务端): 持久化本地任务和日志`。

### 任务 4：Spring 集成、文档和全量验证

- Spring 重启测试：`/Users/Masons/Ai/Maven/bin/mvn -Dmaven.repo.local=/Users/Masons/Ai/Maven_Ai_Store -q -pl server -Dtest=LocalExecutionPersistenceSpringTest test`，退出码 0；Surefire：1 条测试，0 失败，0 错误，0 跳过。该测试启动两次 Spring 上下文，验证工作区、待审批任务、任务日志和审计记录恢复，并能继续拒绝恢复后的待审批任务。
- 提交：`10a12e2 test(服务端): 验证本地执行状态重启恢复`。
- 全量服务端首次验证发现测试隔离问题：`LocalExecutionControllerTest` 在默认 `server/.matrixcode/local-execution.json` 读到其他 Spring 测试写入的工作区。根因是第十五阶段引入文件持久化后，未配置隔离路径的 Spring 测试会共享默认本地状态文件。
- 修复：为 `LocalExecutionControllerTest`、`WorkbenchControllerTest`、`ProjectOverviewControllerTest` 添加 `matrixcode.local-execution.storage-path` 和 `matrixcode.runtime-notifications.storage-path` 临时路径，并在 `@AfterEach` 删除。
- 回归命令：
  - `/Users/Masons/Ai/Maven/bin/mvn -Dmaven.repo.local=/Users/Masons/Ai/Maven_Ai_Store -q -pl server -Dtest=LocalExecutionControllerTest,WorkbenchControllerTest test`，退出码 0；分别 5 条和 12 条测试通过。
  - `/Users/Masons/Ai/Maven/bin/mvn -Dmaven.repo.local=/Users/Masons/Ai/Maven_Ai_Store -q -pl server -Dtest=ProjectOverviewControllerTest test`，退出码 0；3 条测试通过。
  - 最终服务端全量：`/Users/Masons/Ai/Maven/bin/mvn -Dmaven.repo.local=/Users/Masons/Ai/Maven_Ai_Store -q -pl server test`，退出码 0；Surefire 汇总 37 个报告、226 条测试，0 失败，0 错误，0 跳过；`server/.matrixcode` 无残留文件。
- 提交：`ff85288 test(服务端): 隔离本地执行持久化测试状态`。
- 桌面端验证：
  - `cd desktop && npm test`，退出码 0；3 个测试文件、69 条测试通过。
  - `cd desktop && npm run build`，退出码 0；`tsc --noEmit` 和 `vite build` 通过。
  - `cd desktop && npm run tauri:build -- --help`，退出码 0；Tauri CLI 帮助正常输出。
- 文档和 diff 检查：
  - 文档红旗搜索无匹配。
  - 未限定 Maven 命令搜索无匹配。
  - `git diff --check` 通过。
- 运行态重启验证：
  - 使用 `MATRIXCODE_LOCAL_EXECUTION_STORAGE_PATH=/tmp/matrixcode-local-execution-stage15-browser.json` 和 `MATRIXCODE_RUNTIME_NOTIFICATIONS_STORAGE_PATH=/tmp/matrixcode-runtime-notifications-stage15-browser.json` 启动服务端，启动 Vite。
  - API 准备 1 个授权工作区、1 条待审批 `git status`、1 条已成功 `sleep 1`；工作台 API 显示工作区 1 个，最近任务包含 `SUCCESS sleep 1` 和 `APPROVAL_PENDING git status`，任务日志包含 `任务运行完成，退出码：0`，审计包含 `ASK` 和 `ALLOW`。
  - Playwright 打开 `http://127.0.0.1:5173/`，页面显示 `MatrixCode 持久化验证工作区`、`git status`、`sleep 1`、`需要审批本地命令` 和 `本地命令执行成功`。
  - 停止服务端后使用同一状态路径重启，刷新页面后仍显示工作区、待审批 `git status`、成功任务和运行态提醒。工作台 API 确认 `recentTaskLogs` 仍包含 `任务运行完成，退出码：0`。
  - 在页面点击「拒绝」后，页面显示 `已拒绝 · DENY · git status` 和 `审计 DENY · git status`；本地执行快照文件中 `git status` 持久化为 `DENIED`，`sleep 1` 保持 `SUCCESS`，日志和 ASK/ALLOW/DENY 审计均存在。
  - 浏览器自动化未发现接口失败或脚本错误；Chrome 仅记录 `favicon.ico` 的 404 资源请求。
  - 服务端和 Vite 均已停止，8080 和 5173 端口无监听。

### 2026-06-25 回溯验收

- 结论：第十五阶段已完成并验证；历史 checklist 已按执行记录回填为完成状态。
- 初始需求对齐：本阶段补齐本地执行代理的工作区、任务、日志和审批审计恢复能力，直接支撑编码智能体和人工审批闭环，未偏离安全边界。
- 后续对齐：第十七阶段已把该切片纳入 JDBC 快照，第十八阶段已建立本地执行任务和审计领域表；正式表仓储切换仍需后续阶段处理。
