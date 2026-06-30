# MatrixCode 第十七阶段 JDBC 快照持久化实现计划

> **面向 AI 代理的工作者：** 必需子技能：使用 superpowers:executing-plans 逐任务实现此计划；每个实现任务先写失败测试，再写实现，再运行定向测试。步骤使用复选框（`- [ ]`）语法跟踪进度。

**目标：** 增加可选 JDBC 快照持久化模式，让运行态提醒、本地执行状态和工作台状态可以写入 PostgreSQL 兼容数据库，并保持默认文件模式不变。

**架构：** 新增 `persistence` 应用包，提供统一持久化模式配置、JDBC 快照仓储和三类现有 Store 接口的 JDBC 实现。文件 Store 通过条件 Bean 保持默认启用；`matrixcode.persistence.mode=jdbc` 时切换到 JDBC Store。

**技术栈：** Java 21、Spring Boot 条件 Bean、JDK JDBC `DriverManager`、Jackson、PostgreSQL JDBC Driver、H2 测试数据库、JUnit 5、AssertJ、MockMvc。

---

## 文件结构

- 修改：`server/pom.xml`
- 修改：`server/src/main/java/com/matrixcode/runtime/application/FileRuntimeNotificationStore.java`
- 修改：`server/src/main/java/com/matrixcode/localexecution/application/FileLocalExecutionStateStore.java`
- 修改：`server/src/main/java/com/matrixcode/workbench/application/FileWorkbenchStateStore.java`
- 创建：`server/src/main/java/com/matrixcode/persistence/application/PersistenceModeProperties.java`
- 创建：`server/src/main/java/com/matrixcode/persistence/application/JdbcSnapshotRepository.java`
- 创建：`server/src/main/java/com/matrixcode/persistence/application/JdbcRuntimeNotificationStore.java`
- 创建：`server/src/main/java/com/matrixcode/persistence/application/JdbcLocalExecutionStateStore.java`
- 创建：`server/src/main/java/com/matrixcode/persistence/application/JdbcWorkbenchStateStore.java`
- 创建：`server/src/test/java/com/matrixcode/persistence/JdbcSnapshotRepositoryTest.java`
- 创建：`server/src/test/java/com/matrixcode/persistence/JdbcRuntimeNotificationStoreTest.java`
- 创建：`server/src/test/java/com/matrixcode/persistence/JdbcLocalExecutionStateStoreTest.java`
- 创建：`server/src/test/java/com/matrixcode/persistence/JdbcWorkbenchStateStoreTest.java`
- 创建：`server/src/test/java/com/matrixcode/persistence/JdbcPersistenceSpringTest.java`
- 修改：`docs/development/local-run.md`
- 修改：`docs/superpowers/plans/2026-06-25-matrixcode-jdbc-snapshot-persistence.md`

## 任务 1：依赖、配置和文件模式条件 Bean

**文件：**
- 修改：`server/pom.xml`
- 修改：`server/src/main/java/com/matrixcode/runtime/application/FileRuntimeNotificationStore.java`
- 修改：`server/src/main/java/com/matrixcode/localexecution/application/FileLocalExecutionStateStore.java`
- 修改：`server/src/main/java/com/matrixcode/workbench/application/FileWorkbenchStateStore.java`
- 创建：`server/src/main/java/com/matrixcode/persistence/application/PersistenceModeProperties.java`
- 创建：`server/src/test/java/com/matrixcode/persistence/JdbcSnapshotRepositoryTest.java`

- [x] **步骤 1：编写失败测试**

创建 `JdbcSnapshotRepositoryTest`，先写表名校验测试，引用尚不存在的 `PersistenceModeProperties`：

```java
@Test
void 拒绝不安全的快照表名() {
    var properties = new PersistenceModeProperties();
    properties.getJdbc().setTableName("matrixcode_state_snapshots;drop table x");

    assertThatThrownBy(properties::validatedTableName)
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("快照表名只能包含字母、数字和下划线");
}
```

- [x] **步骤 2：运行测试验证失败**

```bash
/Users/Masons/Ai/Maven/bin/mvn -Dmaven.repo.local=/Users/Masons/Ai/Maven_Ai_Store -q -pl server -Dtest=JdbcSnapshotRepositoryTest test
```

预期：编译失败，提示 `PersistenceModeProperties` 不存在。

- [x] **步骤 3：实现配置和条件 Bean**

`server/pom.xml` 增加依赖：

```xml
<dependency>
    <groupId>org.postgresql</groupId>
    <artifactId>postgresql</artifactId>
    <scope>runtime</scope>
</dependency>
<dependency>
    <groupId>com.h2database</groupId>
    <artifactId>h2</artifactId>
    <scope>test</scope>
</dependency>
```

`PersistenceModeProperties` 使用 `@Component` 和 `@ConfigurationProperties(prefix = "matrixcode.persistence")`，字段包括 `mode` 和嵌套 `Jdbc` 配置。`validatedTableName()` 仅允许 `[A-Za-z0-9_]+`。

三个文件 Store 增加：

```java
@ConditionalOnProperty(prefix = "matrixcode.persistence", name = "mode", havingValue = "file", matchIfMissing = true)
```

- [x] **步骤 4：运行定向测试验证通过**

```bash
/Users/Masons/Ai/Maven/bin/mvn -Dmaven.repo.local=/Users/Masons/Ai/Maven_Ai_Store -q -pl server -Dtest=JdbcSnapshotRepositoryTest test
```

- [x] **步骤 5：Commit**

```bash
git add server/pom.xml \
  server/src/main/java/com/matrixcode/runtime/application/FileRuntimeNotificationStore.java \
  server/src/main/java/com/matrixcode/localexecution/application/FileLocalExecutionStateStore.java \
  server/src/main/java/com/matrixcode/workbench/application/FileWorkbenchStateStore.java \
  server/src/main/java/com/matrixcode/persistence/application/PersistenceModeProperties.java \
  server/src/test/java/com/matrixcode/persistence/JdbcSnapshotRepositoryTest.java
git commit -m "feat(服务端): 添加 JDBC 快照持久化配置"
```

## 任务 2：JDBC 快照仓储

**文件：**
- 创建：`server/src/main/java/com/matrixcode/persistence/application/JdbcSnapshotRepository.java`
- 修改：`server/src/test/java/com/matrixcode/persistence/JdbcSnapshotRepositoryTest.java`

- [x] **步骤 1：补充失败测试**

在 `JdbcSnapshotRepositoryTest` 增加 H2 用例：使用 `jdbc:h2:mem:matrixcode-state;MODE=PostgreSQL;DB_CLOSE_DELAY=-1`，验证首次 `save("runtime-notifications", 1, "{\"version\":1}")` 自动建表，第二次保存同一 `slice_key` 覆盖 payload，`load("runtime-notifications")` 返回最新版本和内容。

- [x] **步骤 2：运行测试验证失败**

```bash
/Users/Masons/Ai/Maven/bin/mvn -Dmaven.repo.local=/Users/Masons/Ai/Maven_Ai_Store -q -pl server -Dtest=JdbcSnapshotRepositoryTest test
```

预期：编译失败，提示 `JdbcSnapshotRepository` 不存在。

- [x] **步骤 3：实现仓储**

`JdbcSnapshotRepository` 职责：

- 构造时接收 `PersistenceModeProperties`。
- 每次操作通过 `DriverManager.getConnection(url, username, password)` 获取连接。
- `ensureTable(Connection)` 执行 `create table if not exists ...`。
- `load(String sliceKey)` 返回 `Optional<StoredSnapshot>`，包含 `sliceKey`、`version`、`payload`。
- `save(String sliceKey, int version, String payload)` 先尝试标准 `update`，影响行数为 0 时执行 `insert`；避免 H2 和 PostgreSQL 方言差异。

- [x] **步骤 4：运行定向测试验证通过**

```bash
/Users/Masons/Ai/Maven/bin/mvn -Dmaven.repo.local=/Users/Masons/Ai/Maven_Ai_Store -q -pl server -Dtest=JdbcSnapshotRepositoryTest test
```

- [x] **步骤 5：Commit**

```bash
git add server/src/main/java/com/matrixcode/persistence/application/JdbcSnapshotRepository.java \
  server/src/test/java/com/matrixcode/persistence/JdbcSnapshotRepositoryTest.java
git commit -m "feat(服务端): 添加 JDBC 快照仓储"
```

## 任务 3：运行态提醒 JDBC Store

**文件：**
- 创建：`server/src/main/java/com/matrixcode/persistence/application/JdbcRuntimeNotificationStore.java`
- 创建：`server/src/test/java/com/matrixcode/persistence/JdbcRuntimeNotificationStoreTest.java`

- [x] **步骤 1：编写失败测试**

`JdbcRuntimeNotificationStoreTest` 使用 H2 仓储保存包含一条 `RuntimeNotification` 的 `RuntimeNotificationSnapshot`，重建 Store 后 `load()` 应恢复提醒；再手动向仓储写入版本 1 但 payload 为 `{broken` 的记录，`load()` 应返回空快照。

- [x] **步骤 2：运行测试验证失败**

```bash
/Users/Masons/Ai/Maven/bin/mvn -Dmaven.repo.local=/Users/Masons/Ai/Maven_Ai_Store -q -pl server -Dtest=JdbcRuntimeNotificationStoreTest test
```

预期：编译失败，提示 `JdbcRuntimeNotificationStore` 不存在。

- [x] **步骤 3：实现 Store**

`JdbcRuntimeNotificationStore` 实现 `RuntimeNotificationStore`，使用切片键 `runtime-notifications`。`save()` 将快照写为 JSON 字符串；`load()` 读取版本 1 并反序列化，版本不兼容或 JSON 损坏返回 `RuntimeNotificationSnapshot.empty()`。

- [x] **步骤 4：运行定向测试验证通过**

```bash
/Users/Masons/Ai/Maven/bin/mvn -Dmaven.repo.local=/Users/Masons/Ai/Maven_Ai_Store -q -pl server -Dtest=JdbcRuntimeNotificationStoreTest test
```

- [x] **步骤 5：Commit**

```bash
git add server/src/main/java/com/matrixcode/persistence/application/JdbcRuntimeNotificationStore.java \
  server/src/test/java/com/matrixcode/persistence/JdbcRuntimeNotificationStoreTest.java
git commit -m "feat(服务端): 支持 JDBC 运行态提醒快照"
```

## 任务 4：本地执行 JDBC Store

**文件：**
- 创建：`server/src/main/java/com/matrixcode/persistence/application/JdbcLocalExecutionStateStore.java`
- 创建：`server/src/test/java/com/matrixcode/persistence/JdbcLocalExecutionStateStoreTest.java`

- [x] **步骤 1：编写失败测试**

`JdbcLocalExecutionStateStoreTest` 使用 H2 仓储：

- `saveWorkspaces()` 保存工作区，随后 `saveAuditRecords()` 保存审计，重建 Store 后两类数据都存在。
- `saveTasks()` 保存任务和日志，重建 Store 后任务、日志、工作区和审计都存在，证明分区更新不丢其他分区。
- 仓储中写入损坏 payload 后 `load()` 返回 `LocalExecutionSnapshot.empty()`。

- [x] **步骤 2：运行测试验证失败**

```bash
/Users/Masons/Ai/Maven/bin/mvn -Dmaven.repo.local=/Users/Masons/Ai/Maven_Ai_Store -q -pl server -Dtest=JdbcLocalExecutionStateStoreTest test
```

预期：编译失败，提示 `JdbcLocalExecutionStateStore` 不存在。

- [x] **步骤 3：实现 Store**

`JdbcLocalExecutionStateStore` 实现 `LocalExecutionStateStore`，使用切片键 `local-execution`。构造时读取当前快照，分区保存方法沿用文件 Store 的合并逻辑，每次更新后写回仓储。

- [x] **步骤 4：运行定向测试验证通过**

```bash
/Users/Masons/Ai/Maven/bin/mvn -Dmaven.repo.local=/Users/Masons/Ai/Maven_Ai_Store -q -pl server -Dtest=JdbcLocalExecutionStateStoreTest test
```

- [x] **步骤 5：Commit**

```bash
git add server/src/main/java/com/matrixcode/persistence/application/JdbcLocalExecutionStateStore.java \
  server/src/test/java/com/matrixcode/persistence/JdbcLocalExecutionStateStoreTest.java
git commit -m "feat(服务端): 支持 JDBC 本地执行快照"
```

## 任务 5：工作台 JDBC Store

**文件：**
- 创建：`server/src/main/java/com/matrixcode/persistence/application/JdbcWorkbenchStateStore.java`
- 创建：`server/src/test/java/com/matrixcode/persistence/JdbcWorkbenchStateStoreTest.java`

- [x] **步骤 1：编写失败测试**

`JdbcWorkbenchStateStoreTest` 使用 H2 仓储：

- 保存文档、Bug、部署目标、Compose 环境、模型请求、事件、文件操作、Git diff、工作流和验收投影。
- 重建 Store 后上述分区都存在。
- 先保存文档再保存 Bug，重建后两者都存在，证明分区更新不丢其他分区。
- 仓储中写入损坏 payload 后 `load()` 返回 `WorkbenchStateSnapshot.empty()`。

- [x] **步骤 2：运行测试验证失败**

```bash
/Users/Masons/Ai/Maven/bin/mvn -Dmaven.repo.local=/Users/Masons/Ai/Maven_Ai_Store -q -pl server -Dtest=JdbcWorkbenchStateStoreTest test
```

预期：编译失败，提示 `JdbcWorkbenchStateStore` 不存在。

- [x] **步骤 3：实现 Store**

`JdbcWorkbenchStateStore` 实现 `WorkbenchStateStore`，使用切片键 `workbench-state`。构造时读取当前快照，所有分区保存方法沿用 `FileWorkbenchStateStore` 的快照合并语义。

- [x] **步骤 4：运行定向测试验证通过**

```bash
/Users/Masons/Ai/Maven/bin/mvn -Dmaven.repo.local=/Users/Masons/Ai/Maven_Ai_Store -q -pl server -Dtest=JdbcWorkbenchStateStoreTest test
```

- [x] **步骤 5：Commit**

```bash
git add server/src/main/java/com/matrixcode/persistence/application/JdbcWorkbenchStateStore.java \
  server/src/test/java/com/matrixcode/persistence/JdbcWorkbenchStateStoreTest.java
git commit -m "feat(服务端): 支持 JDBC 工作台状态快照"
```

## 任务 6：Spring JDBC 模式集成、文档和最终验证

**文件：**
- 创建：`server/src/test/java/com/matrixcode/persistence/JdbcPersistenceSpringTest.java`
- 修改：`docs/development/local-run.md`
- 修改：`docs/superpowers/plans/2026-06-25-matrixcode-jdbc-snapshot-persistence.md`

- [x] **步骤 1：编写 Spring 集成测试**

`JdbcPersistenceSpringTest` 配置：

- `matrixcode.persistence.mode=jdbc`
- `matrixcode.persistence.jdbc.url=jdbc:h2:mem:matrixcode-jdbc-spring;MODE=PostgreSQL;DB_CLOSE_DELAY=-1`
- `matrixcode.persistence.jdbc.username=sa`
- `matrixcode.persistence.jdbc.password=`

第一次 Spring 上下文通过 API 创建工作区、文件操作、产品文档、冻结 PRD、Bug、部署目标、Compose 环境、模型请求和验收退回；第二次上下文使用同一个 H2 数据库，验证工作台恢复同一状态，并查询数据库确认存在三类 `slice_key`。

- [x] **步骤 2：运行 Spring 集成测试**

```bash
/Users/Masons/Ai/Maven/bin/mvn -Dmaven.repo.local=/Users/Masons/Ai/Maven_Ai_Store -q -pl server -Dtest=JdbcPersistenceSpringTest test
```

- [x] **步骤 3：运行服务端全量测试**

```bash
/Users/Masons/Ai/Maven/bin/mvn -Dmaven.repo.local=/Users/Masons/Ai/Maven_Ai_Store -q -pl server test
```

- [x] **步骤 4：运行桌面端验证**

```bash
cd desktop
npm test
npm run build
npm run tauri:build -- --help
```

- [x] **步骤 5：文档更新**

在 `docs/development/local-run.md` 增加第十七阶段验证说明：默认文件模式不变、JDBC 模式环境变量、PostgreSQL `docker compose up -d postgres` 验证命令、三行快照记录说明和当前限制。

- [x] **步骤 6：文档和空白检查**

```bash
rg "mv[n] -q|mv[n] test|mv[n] spring-boot:run" docs/superpowers/plans/2026-06-25-matrixcode-jdbc-snapshot-persistence.md docs/development/local-run.md -n
git diff --check
```

- [x] **步骤 7：Commit**

```bash
git add server/src/test/java/com/matrixcode/persistence/JdbcPersistenceSpringTest.java \
  docs/development/local-run.md \
  docs/superpowers/plans/2026-06-25-matrixcode-jdbc-snapshot-persistence.md
git commit -m "test(服务端): 验证 JDBC 快照重启恢复"
```

## 执行记录

- 已提交 `7d42c64 docs: 规划第十七阶段 JDBC 快照持久化`，明确选择 JDBC 快照模式而不是一次性拆领域关系表。
- 已提交 `cd36c9e docs: 规划第十七阶段 JDBC 快照实现`，按 TDD 拆分配置、仓储、三类 Store、Spring 集成和文档验证任务。
- 已提交 `5886237 feat(服务端): 添加 JDBC 快照持久化配置`，新增 PostgreSQL runtime 依赖、H2 test 依赖、`PersistenceModeProperties` 和文件 Store 条件 Bean。红灯为 `PersistenceModeProperties` 缺失；定向测试 `JdbcSnapshotRepositoryTest` 通过。
- 已提交 `1009d4d feat(服务端): 添加 JDBC 快照仓储`，新增 `JdbcSnapshotRepository`，使用标准 update/insert 兼容 H2 和 PostgreSQL。红灯为 `JdbcSnapshotRepository` 缺失；定向测试 `JdbcSnapshotRepositoryTest` 通过。
- 已提交 `cbb9d1f feat(服务端): 支持 JDBC 运行态提醒快照`，新增 `JdbcRuntimeNotificationStore`，覆盖正常恢复和损坏 payload 空快照。红灯为 `JdbcRuntimeNotificationStore` 缺失；定向测试 `JdbcRuntimeNotificationStoreTest` 通过。
- 已提交 `e30a324 feat(服务端): 支持 JDBC 本地执行快照`，新增 `JdbcLocalExecutionStateStore`，覆盖工作区、任务日志、审批审计分区合并恢复和损坏 payload 容错。红灯为 `JdbcLocalExecutionStateStore` 缺失；定向测试 `JdbcLocalExecutionStateStoreTest` 通过。
- 已提交 `d523d17 feat(服务端): 支持 JDBC 工作台状态快照`，新增 `JdbcWorkbenchStateStore`，覆盖文档、Bug、部署、健康检查、Compose、模型请求、事件、文件操作、Git diff、工作流和验收投影恢复。红灯为 `JdbcWorkbenchStateStore` 缺失；定向测试 `JdbcWorkbenchStateStoreTest` 通过。
- Spring JDBC 模式重启测试：`/Users/Masons/Ai/Maven/bin/mvn -Dmaven.repo.local=/Users/Masons/Ai/Maven_Ai_Store -q -pl server -Dtest=JdbcPersistenceSpringTest test` 通过。该测试用同一个 H2 数据库启动两次 Spring 上下文，验证三类 JDBC 切片恢复，并确认文件快照路径未写入。
- 服务端最终全量测试：`/Users/Masons/Ai/Maven/bin/mvn -Dmaven.repo.local=/Users/Masons/Ai/Maven_Ai_Store -q -pl server test` 通过；Surefire 汇总 44 份报告、252 条测试，0 失败、0 错误、0 跳过；`server/.matrixcode` 无残留文件。
- 桌面端验证：`npm test` 通过，3 个测试文件、69 条测试；`npm run build` 通过；`npm run tauri:build -- --help` 通过。
