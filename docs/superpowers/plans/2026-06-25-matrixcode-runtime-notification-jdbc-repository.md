# 运行态提醒正式 MySQL 仓储实现计划

> **面向 AI 代理的工作者：** 必需子技能：使用 superpowers:subagent-driven-development（推荐）或 superpowers:executing-plans 逐任务实现此计划。步骤使用复选框（`- [ ]`）语法来跟踪进度。

**目标：** 让 JDBC 模式下的运行态提醒优先读写正式 `matrixcode_runtime_notifications` 表，并兼容从旧 `runtime-notifications` 快照回填。

**架构：** 复用 `RuntimeNotificationStore` 接口，改造 `JdbcRuntimeNotificationStore` 为正式表实现。正式表为空时读取旧快照并回填；正式表有数据时只使用正式表。

**技术栈：** Java 21、Spring Boot、Flyway、H2 PostgreSQL 模式、JUnit 5。

---

## 文件结构

- 修改 `server/src/main/java/com/matrixcode/persistence/application/JdbcRuntimeNotificationStore.java`
- 修改 `server/src/test/java/com/matrixcode/persistence/JdbcRuntimeNotificationStoreTest.java`
- 修改 `server/src/test/java/com/matrixcode/persistence/JdbcPersistenceSpringTest.java`
- 创建 `docs/superpowers/specs/2026-06-25-matrixcode-runtime-notification-jdbc-repository-design.md`
- 创建 `docs/superpowers/plans/2026-06-25-matrixcode-runtime-notification-jdbc-repository.md`

## 任务 1：正式提醒表仓储红绿

- [x] **步骤 1：编写失败的正式表仓储测试**

在 `JdbcRuntimeNotificationStoreTest` 中改造保存恢复测试：

```java
@Test
void 保存后从正式运行态提醒表恢复且不写旧快照() throws Exception {
    var jdbcUrl = jdbcUrl("runtime_notifications_domain_");
    migrate(jdbcUrl);
    var repository = repository(jdbcUrl);
    var store = new JdbcRuntimeNotificationStore(repository, JsonTestSupport.objectMapper());

    store.save(new RuntimeNotificationSnapshot(1, Map.of("demo", List.of(notification("notice-1", READ_AT)))));

    var restored = new JdbcRuntimeNotificationStore(repository, JsonTestSupport.objectMapper()).load();

    assertThat(restored.projects()).containsKey("demo");
    assertThat(restored.projects().get("demo")).extracting(RuntimeNotification::id).containsExactly("notice-1");
    assertThat(restored.projects().get("demo").getFirst().readAt()).isEqualTo(READ_AT);
    assertThat(tableCount(jdbcUrl, "matrixcode_runtime_notifications")).isEqualTo(1);
    assertThat(snapshotCount(jdbcUrl, "runtime-notifications")).isZero();
}
```

- [x] **步骤 2：运行测试验证失败**

运行：`/Users/Masons/Ai/Maven/bin/mvn -Dmaven.repo.local=/Users/Masons/Ai/Maven_Ai_Store -q -pl server -Dtest=JdbcRuntimeNotificationStoreTest test`

预期：FAIL，当前实现仍写旧 `runtime-notifications` 快照，正式表计数为 0。

- [x] **步骤 3：实现正式表读写**

改造 `JdbcRuntimeNotificationStore`：

```java
public RuntimeNotificationSnapshot load() {
    var formal = readFormalSnapshot();
    if (!formal.projects().isEmpty()) {
        return formal;
    }
    var legacy = readLegacySnapshot();
    if (!legacy.projects().isEmpty()) {
        writeFormalSnapshot(legacy);
        return legacy;
    }
    return RuntimeNotificationSnapshot.empty();
}
```

`save()` 调用事务化 `writeFormalSnapshot()`，写入 `matrixcode_runtime_notifications`，不再调用旧快照仓储的 `save` 方法。

- [x] **步骤 4：运行测试验证通过**

运行同上命令，预期 PASS。

## 任务 2：旧快照回填兼容

- [x] **步骤 1：编写失败的旧快照回填测试**

在 `JdbcRuntimeNotificationStoreTest` 中新增：

```java
@Test
void 正式表为空时从旧快照恢复并回填正式表() throws Exception {
    var jdbcUrl = jdbcUrl("runtime_notifications_backfill_");
    migrate(jdbcUrl);
    var repository = repository(jdbcUrl);
    repository.save("runtime-notifications", 1, JsonTestSupport.objectMapper().writeValueAsString(
            new RuntimeNotificationSnapshot(1, Map.of("demo", List.of(notification("notice-legacy", READ_AT))))
    ));

    var restored = new JdbcRuntimeNotificationStore(repository, JsonTestSupport.objectMapper()).load();

    assertThat(restored.projects().get("demo")).extracting(RuntimeNotification::id).containsExactly("notice-legacy");
    assertThat(tableCount(jdbcUrl, "matrixcode_runtime_notifications")).isEqualTo(1);
}
```

- [x] **步骤 2：运行回填测试验证失败**

运行：`/Users/Masons/Ai/Maven/bin/mvn -Dmaven.repo.local=/Users/Masons/Ai/Maven_Ai_Store -q -pl server -Dtest=JdbcRuntimeNotificationStoreTest test`

预期：FAIL，当前实现不会把旧快照回填到正式表。

- [x] **步骤 3：实现旧快照回填和损坏快照兼容**

保留 `readLegacySnapshot()` 的 JSON 解析兼容：

```java
private RuntimeNotificationSnapshot readLegacySnapshot() {
    return repository.load(SLICE_KEY)
            .filter(snapshot -> snapshot.version() == 1)
            .map(snapshot -> read(snapshot.payload()))
            .orElseGet(RuntimeNotificationSnapshot::empty);
}
```

损坏 payload 返回空快照，不抛出异常。

- [x] **步骤 4：运行回填测试验证通过**

运行同上命令，预期 PASS。

## 任务 3：Spring 回归、全量验证和图谱

- [x] **步骤 1：补 Spring JDBC 回归断言**

在 `JdbcPersistenceSpringTest` 中：

- 第一轮上下文触发 `workbench.get("demo")` 生成审批提醒。
- 断言 `matrixcode_state_snapshots` 只包含 `workbench-state`，不包含 `runtime-notifications` 和 `local-execution`。
- 第二轮上下文断言 `restored.runtimeNotifications()` 包含 `approval:` 提醒。
- 末尾断言 `matrixcode_runtime_notifications` 计数大于 0。

- [x] **步骤 2：运行 Spring 回归验证通过**

运行：`/Users/Masons/Ai/Maven/bin/mvn -Dmaven.repo.local=/Users/Masons/Ai/Maven_Ai_Store -q -pl server -Dtest=JdbcPersistenceSpringTest test`

- [x] **步骤 3：运行关联测试**

运行：

```bash
/Users/Masons/Ai/Maven/bin/mvn -Dmaven.repo.local=/Users/Masons/Ai/Maven_Ai_Store -q -pl server -Dtest=JdbcRuntimeNotificationStoreTest,RuntimeNotificationServiceTest,JdbcPersistenceSpringTest test
```

- [x] **步骤 4：运行服务端全量测试**

运行：`/Users/Masons/Ai/Maven/bin/mvn -Dmaven.repo.local=/Users/Masons/Ai/Maven_Ai_Store -q -pl server test`

- [x] **步骤 5：提交前检查**

运行：`git diff --check`，并对真实密钥片段做精确扫描。

- [x] **步骤 6：更新 Obsidian**

新增第 31 阶段成果页，并更新首页、总览、阶段索引、模块地图、验证风险、运行态同步与提醒中心、状态持久化模块。

- [x] **步骤 7：提交**

运行：`git commit -m "feat: 增加运行态提醒 JDBC 仓储"`

---

## 执行记录

- 红灯 1：运行 `JdbcRuntimeNotificationStoreTest`，正式表计数断言失败，`matrixcode_runtime_notifications` 仍为 0，证明旧实现只写 `runtime-notifications` 快照。
- 绿灯 1：实现正式表优先读写后，`JdbcRuntimeNotificationStoreTest` 通过。
- 红灯 2：新增旧快照回填测试后，正式表回填计数仍为 0，证明兼容路径尚未写回正式表。
- 编译红灯：移除 `JsonProcessingException` import 后测试编译失败，恢复 import 后继续验证。
- 绿灯 2：实现旧快照回填与损坏 payload 兼容后，`JdbcRuntimeNotificationStoreTest` 通过。
- Spring 回归：`JdbcPersistenceSpringTest` 通过，确认 JDBC 模式重启后运行态提醒可从正式表恢复，`matrixcode_state_snapshots` 不再包含 `runtime-notifications` 与 `local-execution`。
- 关联测试：`JdbcRuntimeNotificationStoreTest,RuntimeNotificationServiceTest,JdbcPersistenceSpringTest` 通过。
- 服务端全量测试：`/Users/Masons/Ai/Maven/bin/mvn -Dmaven.repo.local=/Users/Masons/Ai/Maven_Ai_Store -q -pl server test` 完成，Surefire 汇总 `Tests run: 295, Failures: 0, Errors: 0, Skipped: 0`。
- 提交前检查：`git diff --check` 通过；计划/规格占位扫描无命中；敏感片段精确扫描无命中。
- Obsidian 图谱：新增 `MatrixCode/阶段成果/31 运行态提醒正式 MySQL 仓储.md`，并更新首页、总览、阶段索引、模块地图、验证风险、运行态同步与提醒中心、状态持久化与数据库迁移。
- 提交：`feat: 增加运行态提醒 JDBC 仓储`。
