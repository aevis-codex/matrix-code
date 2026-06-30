# MatrixCode 第十四阶段运行态提醒轻量持久化实现计划

> **面向 AI 代理的工作者：** 必需子技能：使用 superpowers:subagent-driven-development（推荐）或 superpowers:executing-plans 逐任务实现此计划。步骤使用复选框（`- [ ]`）语法来跟踪进度。

**目标：** 让运行态提醒记录和已读状态通过可配置 JSON 文件跨服务端重启恢复。

**架构：** 在运行态提醒服务下新增存储抽象和文件存储实现，服务启动时加载快照，提醒收件箱变化后写回快照。Spring 环境默认使用文件存储，手写单元测试可继续使用内存存储，桌面端沿用第十三阶段的工作台字段。

**技术栈：** Java 21、Spring Boot、Jackson、JUnit 5、AssertJ、MockMvc、React、TypeScript、Vitest、Vite。

---

## 文件结构

- 修改：`.gitignore`
  - 忽略默认本地运行数据目录 `.matrixcode/`。
- 创建：`server/src/main/java/com/matrixcode/runtime/application/RuntimeNotificationSnapshot.java`
  - 表达可写入文件的提醒快照。
- 创建：`server/src/main/java/com/matrixcode/runtime/application/RuntimeNotificationStore.java`
  - 定义快照加载和保存接口。
- 创建：`server/src/main/java/com/matrixcode/runtime/application/InMemoryRuntimeNotificationStore.java`
  - 给手写单元测试使用，不触碰磁盘。
- 创建：`server/src/main/java/com/matrixcode/runtime/application/RuntimeNotificationStorageProperties.java`
  - 暴露 `matrixcode.runtime-notifications.storage-path` 配置。
- 创建：`server/src/main/java/com/matrixcode/runtime/application/FileRuntimeNotificationStore.java`
  - 负责 JSON 文件读写和原子替换。
- 修改：`server/src/main/java/com/matrixcode/runtime/application/RuntimeNotificationService.java`
  - 构造时加载快照，`sync`、单条已读和全部已读后保存快照。
- 创建：`server/src/test/java/com/matrixcode/runtime/FileRuntimeNotificationStoreTest.java`
  - 覆盖文件存储的加载、保存和损坏文件容错。
- 修改：`server/src/test/java/com/matrixcode/runtime/RuntimeNotificationServiceTest.java`
  - 覆盖服务重建后恢复提醒和已读状态。
- 修改：`server/src/test/java/com/matrixcode/workbench/WorkbenchControllerTest.java`
  - 覆盖批量已读后存储文件被写入。
- 修改：`server/src/test/java/com/matrixcode/workbench/WorkbenchServiceTest.java`
  - 如构造函数变化，显式传入内存存储。
- 修改：`docs/development/local-run.md`
  - 增加第十四阶段重启恢复验证说明。

## 任务 1：文件存储抽象和 JSON 存储

**文件：**
- 修改：`.gitignore`
- 创建：`server/src/main/java/com/matrixcode/runtime/application/RuntimeNotificationSnapshot.java`
- 创建：`server/src/main/java/com/matrixcode/runtime/application/RuntimeNotificationStore.java`
- 创建：`server/src/main/java/com/matrixcode/runtime/application/InMemoryRuntimeNotificationStore.java`
- 创建：`server/src/main/java/com/matrixcode/runtime/application/RuntimeNotificationStorageProperties.java`
- 创建：`server/src/main/java/com/matrixcode/runtime/application/FileRuntimeNotificationStore.java`
- 创建：`server/src/test/java/com/matrixcode/runtime/FileRuntimeNotificationStoreTest.java`

- [x] **步骤 1：编写失败的文件存储测试**

创建 `FileRuntimeNotificationStoreTest`：

```java
package com.matrixcode.runtime;

import com.fasterxml.jackson.databind.json.JsonMapper;
import com.matrixcode.runtime.application.FileRuntimeNotificationStore;
import com.matrixcode.runtime.application.RuntimeNotificationSnapshot;
import com.matrixcode.runtime.application.RuntimeNotificationStorageProperties;
import com.matrixcode.runtime.domain.RuntimeNotification;
import com.matrixcode.runtime.domain.RuntimeNotificationLevel;
import com.matrixcode.runtime.domain.RuntimeNotificationSourceType;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class FileRuntimeNotificationStoreTest {

    @TempDir
    Path tempDir;

    @Test
    void 文件不存在时加载空快照() {
        var store = store(tempDir.resolve("missing/runtime-notifications.json"));

        var snapshot = store.load();

        assertThat(snapshot.version()).isEqualTo(1);
        assertThat(snapshot.projects()).isEmpty();
    }

    @Test
    void 保存后可以重新加载提醒和已读时间() {
        var path = tempDir.resolve("runtime/runtime-notifications.json");
        var store = store(path);
        var readAt = Instant.parse("2026-06-25T08:00:00Z");
        var notification = notification("approval:task-1", readAt);

        store.save(new RuntimeNotificationSnapshot(1, Map.of("demo", List.of(notification))));

        var loaded = store(path).load();
        assertThat(Files.exists(path)).isTrue();
        assertThat(loaded.projects()).containsKey("demo");
        assertThat(loaded.projects().get("demo").getFirst().id()).isEqualTo("approval:task-1");
        assertThat(loaded.projects().get("demo").getFirst().readAt()).isEqualTo(readAt);
    }

    @Test
    void 文件损坏时加载空快照且保留原文件() throws Exception {
        var path = tempDir.resolve("runtime/runtime-notifications.json");
        Files.createDirectories(path.getParent());
        Files.writeString(path, "{broken");

        var loaded = store(path).load();

        assertThat(loaded.projects()).isEmpty();
        assertThat(Files.readString(path)).isEqualTo("{broken");
    }

    private FileRuntimeNotificationStore store(Path path) {
        var properties = new RuntimeNotificationStorageProperties();
        properties.setStoragePath(path);
        var mapper = JsonMapper.builder()
                .findAndAddModules()
                .build();
        return new FileRuntimeNotificationStore(mapper, properties);
    }

    private RuntimeNotification notification(String id, Instant readAt) {
        return new RuntimeNotification(
                id,
                "demo",
                RuntimeNotificationLevel.ACTION,
                "需要审批本地命令",
                "git status",
                RuntimeNotificationSourceType.APPROVAL,
                "task-1",
                Instant.parse("2026-06-25T07:59:00Z"),
                readAt
        );
    }
}
```

- [x] **步骤 2：运行测试验证失败**

运行：

```bash
/Users/Masons/Ai/Maven/bin/mvn -Dmaven.repo.local=/Users/Masons/Ai/Maven_Ai_Store -q -pl server -Dtest=FileRuntimeNotificationStoreTest test
```

预期：编译失败，提示存储相关类不存在。

- [x] **步骤 3：实现存储类**

创建 `RuntimeNotificationSnapshot`：

```java
package com.matrixcode.runtime.application;

import com.matrixcode.runtime.domain.RuntimeNotification;

import java.util.List;
import java.util.Map;

public record RuntimeNotificationSnapshot(
        int version,
        Map<String, List<RuntimeNotification>> projects
) {
    public static RuntimeNotificationSnapshot empty() {
        return new RuntimeNotificationSnapshot(1, Map.of());
    }
}
```

创建 `RuntimeNotificationStore`：

```java
package com.matrixcode.runtime.application;

public interface RuntimeNotificationStore {
    RuntimeNotificationSnapshot load();

    void save(RuntimeNotificationSnapshot snapshot);
}
```

创建 `InMemoryRuntimeNotificationStore`：

```java
package com.matrixcode.runtime.application;

public class InMemoryRuntimeNotificationStore implements RuntimeNotificationStore {
    @Override
    public RuntimeNotificationSnapshot load() {
        return RuntimeNotificationSnapshot.empty();
    }

    @Override
    public void save(RuntimeNotificationSnapshot snapshot) {
    }
}
```

创建 `RuntimeNotificationStorageProperties`：

```java
package com.matrixcode.runtime.application;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.nio.file.Path;

@Component
@ConfigurationProperties(prefix = "matrixcode.runtime-notifications")
public class RuntimeNotificationStorageProperties {

    private Path storagePath = Path.of(".matrixcode/runtime-notifications.json");

    public Path getStoragePath() {
        return storagePath;
    }

    public void setStoragePath(Path storagePath) {
        if (storagePath != null) {
            this.storagePath = storagePath;
        }
    }
}
```

创建 `FileRuntimeNotificationStore`：

```java
package com.matrixcode.runtime.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

@Service
public class FileRuntimeNotificationStore implements RuntimeNotificationStore {

    private final ObjectMapper objectMapper;
    private final RuntimeNotificationStorageProperties properties;

    public FileRuntimeNotificationStore(ObjectMapper objectMapper, RuntimeNotificationStorageProperties properties) {
        this.objectMapper = objectMapper;
        this.properties = properties;
    }

    @Override
    public RuntimeNotificationSnapshot load() {
        var path = storagePath();
        if (!Files.exists(path)) {
            return RuntimeNotificationSnapshot.empty();
        }
        try {
            var snapshot = objectMapper.readValue(path.toFile(), RuntimeNotificationSnapshot.class);
            if (snapshot.version() != 1 || snapshot.projects() == null) {
                return RuntimeNotificationSnapshot.empty();
            }
            return snapshot;
        } catch (IOException | RuntimeException ignored) {
            return RuntimeNotificationSnapshot.empty();
        }
    }

    @Override
    public void save(RuntimeNotificationSnapshot snapshot) {
        var path = storagePath();
        try {
            var parent = path.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            var tempFile = parent == null
                    ? Files.createTempFile("runtime-notifications-", ".tmp")
                    : Files.createTempFile(parent, "runtime-notifications-", ".tmp");
            objectMapper.writeValue(tempFile.toFile(), snapshot);
            try {
                Files.move(tempFile, path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (IOException atomicMoveFailure) {
                Files.move(tempFile, path, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException exception) {
            throw new IllegalStateException("运行态提醒存储写入失败：" + exception.getMessage(), exception);
        }
    }

    private Path storagePath() {
        return properties.getStoragePath().toAbsolutePath().normalize();
    }
}
```

修改 `.gitignore` 增加：

```gitignore
.matrixcode/
```

- [x] **步骤 4：运行测试验证通过**

运行：

```bash
/Users/Masons/Ai/Maven/bin/mvn -Dmaven.repo.local=/Users/Masons/Ai/Maven_Ai_Store -q -pl server -Dtest=FileRuntimeNotificationStoreTest test
```

预期：`FileRuntimeNotificationStoreTest` 全部通过。

- [x] **步骤 5：Commit**

```bash
git add .gitignore server/src/main/java/com/matrixcode/runtime/application/RuntimeNotificationSnapshot.java server/src/main/java/com/matrixcode/runtime/application/RuntimeNotificationStore.java server/src/main/java/com/matrixcode/runtime/application/InMemoryRuntimeNotificationStore.java server/src/main/java/com/matrixcode/runtime/application/RuntimeNotificationStorageProperties.java server/src/main/java/com/matrixcode/runtime/application/FileRuntimeNotificationStore.java server/src/test/java/com/matrixcode/runtime/FileRuntimeNotificationStoreTest.java
git commit -m "feat(服务端): 添加运行态提醒文件存储"
```

## 任务 2：提醒服务集成持久化

**文件：**
- 修改：`server/src/main/java/com/matrixcode/runtime/application/RuntimeNotificationService.java`
- 修改：`server/src/test/java/com/matrixcode/runtime/RuntimeNotificationServiceTest.java`
- 修改：`server/src/test/java/com/matrixcode/workbench/WorkbenchServiceTest.java`

- [x] **步骤 1：编写失败的服务测试**

在 `RuntimeNotificationServiceTest` 增加测试辅助类和两个测试：

```java
@Test
void 重建服务后恢复提醒和已读状态() {
    var store = new CapturingRuntimeNotificationStore();
    var service = new RuntimeNotificationService(store);
    service.sync(
            "demo",
            localExecution(List.of(task("task-1", "git status", ExecutionTaskStatus.APPROVAL_PENDING, null, "2026-06-25T06:00:00Z"))),
            List.of()
    );
    service.markRead("demo", "approval:task-1");

    var restored = new RuntimeNotificationService(store);

    assertThat(restored.recent("demo")).hasSize(1);
    assertThat(restored.recent("demo").getFirst().readAt()).isNotNull();
}

@Test
void 恢复快照后重复同步保留已读状态并写回存储() {
    var store = new CapturingRuntimeNotificationStore();
    var service = new RuntimeNotificationService(store);
    service.sync(
            "demo",
            localExecution(List.of(task("task-1", "git status", ExecutionTaskStatus.APPROVAL_PENDING, null, "2026-06-25T06:00:00Z"))),
            List.of()
    );
    var readAt = service.markAllRead("demo").getFirst().readAt();

    var restored = new RuntimeNotificationService(store);
    var afterSync = restored.sync(
            "demo",
            localExecution(List.of(task("task-1", "git status", ExecutionTaskStatus.APPROVAL_PENDING, null, "2026-06-25T06:00:00Z"))),
            List.of()
    );

    assertThat(afterSync.getFirst().readAt()).isEqualTo(readAt);
    assertThat(store.saved.projects().get("demo").getFirst().readAt()).isEqualTo(readAt);
}

private static class CapturingRuntimeNotificationStore implements RuntimeNotificationStore {
    private RuntimeNotificationSnapshot saved = RuntimeNotificationSnapshot.empty();

    @Override
    public RuntimeNotificationSnapshot load() {
        return saved;
    }

    @Override
    public void save(RuntimeNotificationSnapshot snapshot) {
        saved = snapshot;
    }
}
```

在 `WorkbenchServiceTest` 里把 `new RuntimeNotificationService()` 改为继续可编译的构造方式，如：

```java
private final RuntimeNotificationService runtimeNotificationService = new RuntimeNotificationService();
```

如果服务移除无参构造，则改为：

```java
private final RuntimeNotificationService runtimeNotificationService =
        new RuntimeNotificationService(new InMemoryRuntimeNotificationStore());
```

- [x] **步骤 2：运行测试验证失败**

运行：

```bash
/Users/Masons/Ai/Maven/bin/mvn -Dmaven.repo.local=/Users/Masons/Ai/Maven_Ai_Store -q -pl server -Dtest=RuntimeNotificationServiceTest test
```

预期：编译失败或断言失败，提示服务尚未从存储恢复和写回。

- [x] **步骤 3：实现服务持久化**

在 `RuntimeNotificationService` 增加 store 字段和构造：

```java
private final RuntimeNotificationStore store;

public RuntimeNotificationService() {
    this(new InMemoryRuntimeNotificationStore());
}

@Autowired
public RuntimeNotificationService(RuntimeNotificationStore store) {
    this.store = store;
    restore(store.load());
}
```

在 `sync`、`markRead`、`markAllRead` 修改收件箱后调用：

```java
persist();
```

增加辅助方法：

```java
private void restore(RuntimeNotificationSnapshot snapshot) {
    snapshot.projects().forEach((projectId, projectNotifications) -> {
        var restored = new ConcurrentHashMap<String, RuntimeNotification>();
        projectNotifications.forEach(notification -> restored.put(notification.id(), notification));
        trim(restored);
        notifications.put(projectId, restored);
    });
}

private void persist() {
    store.save(new RuntimeNotificationSnapshot(
            1,
            notifications.keySet().stream()
                    .collect(Collectors.toMap(projectId -> projectId, this::sorted))
    ));
}
```

补充 `Collectors` 和 `Autowired` import。

- [x] **步骤 4：运行测试验证通过**

运行：

```bash
/Users/Masons/Ai/Maven/bin/mvn -Dmaven.repo.local=/Users/Masons/Ai/Maven_Ai_Store -q -pl server -Dtest=RuntimeNotificationServiceTest test
```

预期：`RuntimeNotificationServiceTest` 全部通过。

- [x] **步骤 5：Commit**

```bash
git add server/src/main/java/com/matrixcode/runtime/application/RuntimeNotificationService.java server/src/test/java/com/matrixcode/runtime/RuntimeNotificationServiceTest.java server/src/test/java/com/matrixcode/workbench/WorkbenchServiceTest.java
git commit -m "feat(服务端): 持久化运行态提醒收件箱"
```

## 任务 3：Spring 集成验证

**文件：**
- 修改：`server/src/test/java/com/matrixcode/workbench/WorkbenchControllerTest.java`

- [x] **步骤 1：编写失败的控制器持久化测试**

在 `WorkbenchControllerTest` 增加：

```java
private static final Path RUNTIME_NOTIFICATION_STORAGE =
        Path.of(System.getProperty("java.io.tmpdir"), "matrixcode-test-runtime-notifications-" + System.nanoTime() + ".json");

@DynamicPropertySource
static void runtimeNotificationProperties(DynamicPropertyRegistry registry) {
    registry.add("matrixcode.runtime-notifications.storage-path", RUNTIME_NOTIFICATION_STORAGE::toString);
}

@AfterEach
void cleanRuntimeNotificationStorage() throws Exception {
    Files.deleteIfExists(RUNTIME_NOTIFICATION_STORAGE);
}

@Test
void 批量已读会写入运行态提醒存储文件() throws Exception {
    var workspaceResponse = mockMvc.perform(post("/api/projects/demo/local-execution/workspaces")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""
                            {"name":"当前项目","rootPath":"%s"}
                            """.formatted(workspace.toString())))
            .andExpect(status().isOk())
            .andReturn();
    var workspaceId = JsonPath.read(workspaceResponse.getResponse().getContentAsString(), "$.id").toString();
    mockMvc.perform(post("/api/projects/demo/local-execution/commands")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""
                            {"workspaceId":"%s","actorId":"user-ops","command":"git status"}
                            """.formatted(workspaceId)))
            .andExpect(status().isOk());

    mockMvc.perform(get("/api/projects/demo/workbench"))
            .andExpect(status().isOk());
    mockMvc.perform(post("/api/projects/demo/runtime-notifications/read-all"))
            .andExpect(status().isOk());

    assertThat(Files.exists(RUNTIME_NOTIFICATION_STORAGE)).isTrue();
    assertThat(Files.readString(RUNTIME_NOTIFICATION_STORAGE)).contains("approval:").contains("readAt");
}
```

- [x] **步骤 2：运行测试验证失败**

运行：

```bash
/Users/Masons/Ai/Maven/bin/mvn -Dmaven.repo.local=/Users/Masons/Ai/Maven_Ai_Store -q -pl server -Dtest=WorkbenchControllerTest test
```

预期：文件断言失败，说明 Spring 环境尚未把运行态提醒写入配置的存储文件。

- [x] **步骤 3：实现缺口**

确保 Spring 默认注入 `FileRuntimeNotificationStore`，并让 `RuntimeNotificationService` 在工作台同步、单条已读和批量已读后写入配置的存储路径。测试断言读取 `RUNTIME_NOTIFICATION_STORAGE`，不共享默认 `.matrixcode` 目录。

- [x] **步骤 4：运行测试验证通过**

运行：

```bash
/Users/Masons/Ai/Maven/bin/mvn -Dmaven.repo.local=/Users/Masons/Ai/Maven_Ai_Store -q -pl server -Dtest=WorkbenchControllerTest test
```

预期：`WorkbenchControllerTest` 全部通过。

- [x] **步骤 5：Commit**

```bash
git add server/src/test/java/com/matrixcode/workbench/WorkbenchControllerTest.java
git commit -m "test(服务端): 验证运行态提醒文件持久化"
```

## 任务 4：文档、全量验证和浏览器重启验证

**文件：**
- 修改：`docs/development/local-run.md`
- 修改：`docs/superpowers/plans/2026-06-25-matrixcode-runtime-notification-persistence.md`

- [x] **步骤 1：更新本地运行文档**

在 `docs/development/local-run.md` 增加：

````markdown
## 第十四阶段运行态提醒轻量持久化验证

服务端默认把运行态提醒快照写入 `.matrixcode/runtime-notifications.json`。如需指定路径，可设置：

```bash
MATRIXCODE_RUNTIME_NOTIFICATIONS_STORAGE_PATH=/tmp/matrixcode-runtime-notifications.json /Users/Masons/Ai/Maven/bin/mvn -Dmaven.repo.local=/Users/Masons/Ai/Maven_Ai_Store -pl server spring-boot:run
```

生成提醒并标记全部已读后，停止服务端，再使用同一存储路径启动。桌面端刷新后应保留提醒记录和已读状态，顶部不应重新出现已读提醒。
````

实现后用同一环境变量实际启动服务端完成浏览器重启验证；如果绑定失败，改用可验证通过的 JVM 属性写法并同步更新文档。

- [x] **步骤 2：运行全量服务端测试**

运行：

```bash
/Users/Masons/Ai/Maven/bin/mvn -Dmaven.repo.local=/Users/Masons/Ai/Maven_Ai_Store -q -pl server test
```

预期：服务端测试全部通过。

- [x] **步骤 3：运行全量桌面端测试**

运行：

```bash
cd desktop && npm test
```

预期：桌面端测试全部通过。

- [x] **步骤 4：运行桌面端构建和 Tauri 检查**

运行：

```bash
cd desktop && npm run build
cd desktop && npm run tauri:build -- --help
```

预期：两个命令退出码均为 0。

- [x] **步骤 5：运行文档和 diff 检查**

运行：

```bash
rg -n "T(O)DO|T[B]D|F[I]XME|待[定]|占[位]|place(holder)|S[u]mmary|G[o]als|Acceptance C[r]iteria" docs/superpowers/specs/2026-06-25-matrixcode-runtime-notification-persistence-design.md docs/superpowers/plans/2026-06-25-matrixcode-runtime-notification-persistence.md docs/development/local-run.md
rg --pcre2 -n "(?<!bin/)\\bm[v]n\\b" docs/superpowers/specs/2026-06-25-matrixcode-runtime-notification-persistence-design.md docs/superpowers/plans/2026-06-25-matrixcode-runtime-notification-persistence.md docs/development/local-run.md
git diff --check
```

预期：前两个搜索命令无匹配，`git diff --check` 退出码为 0。

- [x] **步骤 6：浏览器重启验证**

执行过程：

1. 使用临时存储文件路径启动服务端，启动 Vite。
2. 通过 API 准备一条待审批 `git status` 和一条已完成 `sleep 3`。
3. 打开桌面页，确认右侧「未读 2」。
4. 点击「全部已读」，确认未读数量变为 0。
5. 停止服务端，使用同一存储路径重启。
6. 刷新桌面页，确认顶部无提醒，右侧两条记录保留并标记为「已读」。
7. 检查浏览器控制台无 error。
8. 停止服务端和 Vite，确认端口释放。

- [x] **步骤 7：记录验证证据并提交文档**

把命令、退出码、测试数量和浏览器重启验证现象写入本计划执行记录区域。然后提交：

```bash
git add docs/development/local-run.md docs/superpowers/plans/2026-06-25-matrixcode-runtime-notification-persistence.md
git commit -m "docs: 记录第十四阶段运行态提醒持久化验证"
```

## 执行记录

### 任务 1：文件存储抽象和 JSON 存储

- 红灯：`/Users/Masons/Ai/Maven/bin/mvn -Dmaven.repo.local=/Users/Masons/Ai/Maven_Ai_Store -q -pl server -Dtest=FileRuntimeNotificationStoreTest test`，退出码 1。编译错误提示 `FileRuntimeNotificationStore`、`RuntimeNotificationSnapshot`、`RuntimeNotificationStorageProperties` 不存在。
- 绿灯：同一命令退出码 0；`FileRuntimeNotificationStoreTest` 3 条测试，0 失败，0 错误，0 跳过。
- 提交：`51b506c feat(服务端): 添加运行态提醒文件存储`。

### 任务 2：提醒服务集成持久化

- 红灯：`/Users/Masons/Ai/Maven/bin/mvn -Dmaven.repo.local=/Users/Masons/Ai/Maven_Ai_Store -q -pl server -Dtest=RuntimeNotificationServiceTest test`，退出码 1。编译错误提示 `RuntimeNotificationService` 不支持传入 `RuntimeNotificationStore` 的构造方式。
- 绿灯：同一命令退出码 0；`RuntimeNotificationServiceTest` 8 条测试，0 失败，0 错误，0 跳过。
- 提交：`68744d1 feat(服务端): 持久化运行态提醒收件箱`。

### 任务 3：Spring 集成验证

- 集成测试：`/Users/Masons/Ai/Maven/bin/mvn -Dmaven.repo.local=/Users/Masons/Ai/Maven_Ai_Store -q -pl server -Dtest=WorkbenchControllerTest test`，退出码 0；`WorkbenchControllerTest` 12 条测试，0 失败，0 错误，0 跳过。
- 说明：新增测试首次运行即通过，因为任务 2 已经完成 Spring 默认注入 `FileRuntimeNotificationStore` 和配置路径写入。该测试保留为控制器级验收测试，锁定批量已读写入文件的真实链路。
- 提交：`a5c0edc test(服务端): 验证运行态提醒文件持久化`。

### 任务 4：文档、全量验证和浏览器重启验证

- 服务端全量测试：`/Users/Masons/Ai/Maven/bin/mvn -Dmaven.repo.local=/Users/Masons/Ai/Maven_Ai_Store -q -pl server test`，退出码 0；Surefire 汇总 35 个测试文件、218 条测试，0 失败，0 错误，0 跳过。
- 桌面端测试：`cd desktop && npm test`，退出码 0；3 个测试文件、69 条测试通过。
- 桌面端构建：`cd desktop && npm run build`，退出码 0；`tsc --noEmit` 和 `vite build` 完成。
- Tauri 入口检查：`cd desktop && npm run tauri:build -- --help`，退出码 0。
- 浏览器重启验证：
  - 使用 `MATRIXCODE_RUNTIME_NOTIFICATIONS_STORAGE_PATH=/tmp/matrixcode-runtime-notifications-stage14.json` 启动服务端，Vite 运行在 `http://127.0.0.1:5173/`。
  - API 准备一条待审批 `git status` 和一条已完成 `sleep 3`。桌面页初始显示「未读 2」，列表包含「本地命令执行成功 sleep 3」和「需要审批本地命令 git status」。
  - 点击「全部已读」后，顶部提醒消失，右侧显示「未读 0」，两条记录均为「已读」。切换到「未读」视图后显示「暂无未读提醒」。
  - 存储文件 `/tmp/matrixcode-runtime-notifications-stage14.json` 写入 `version: 1`、`demo` 项目 2 条提醒，且两条 `readAt` 均存在。
  - 停止服务端后使用同一路径重启，刷新桌面页后顶部无提醒，右侧仍保留两条提醒并显示「已读」，「未读 0」保持不变。
  - 浏览器控制台 error 日志为 `[]`。
  - 服务端和 Vite 已停止；`lsof -nP -iTCP:8080 -sTCP:LISTEN`、`lsof -nP -iTCP:5173 -sTCP:LISTEN` 和 `pgrep -fl 'vite --host 127.0.0.1|spring-boot:run|docker compose|docker-credential-desktop'` 均无输出。

### 2026-06-25 回溯验收

- 结论：第十四阶段已完成并验证；历史 checklist 已按执行记录回填为完成状态。
- 初始需求对齐：本阶段解决服务端重启后提醒历史和已读状态丢失的问题，保持本地 MVP 可恢复，未扩大到数据库和用户级已读。
- 后续对齐：第十七阶段已把该切片纳入 JDBC 快照，第十八阶段已建立 MySQL/Flyway 领域表骨架；正式表读写仍是后续拆分任务。
