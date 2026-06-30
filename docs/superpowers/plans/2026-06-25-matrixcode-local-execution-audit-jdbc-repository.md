# 本地执行与审批审计正式 MySQL 仓储实现计划

> **面向 AI 代理的工作者：** 必需子技能：使用 superpowers:subagent-driven-development（推荐）或 superpowers:executing-plans 逐任务实现此计划。步骤使用复选框（`- [ ]`）语法来跟踪进度。

**目标：** 让 JDBC 模式下的本地执行任务、任务日志、工作区授权和审批审计优先读写正式 MySQL 领域表，并兼容从旧快照回填。

**架构：** 复用 `LocalExecutionStateStore` 接口，改造 `JdbcLocalExecutionStateStore` 为正式表实现；保留 `JdbcSnapshotRepository` 作为旧 `local-execution` 切片的只读回填来源；新增 Flyway 迁移补齐本地执行与审计表结构。

**技术栈：** Java 21、Spring Boot、Flyway、H2 MySQL/PostgreSQL 模式、JUnit 5。

---

## 文件结构

- 修改 `server/src/main/java/com/matrixcode/persistence/application/JdbcLocalExecutionStateStore.java`
- 创建 `server/src/main/resources/db/migration/V30_1__extend_local_execution_for_domain_store.sql`
- 修改 `server/src/test/java/com/matrixcode/persistence/JdbcLocalExecutionStateStoreTest.java`
- 修改 `server/src/test/java/com/matrixcode/persistence/JdbcPersistenceSpringTest.java`
- 创建 `docs/superpowers/specs/2026-06-25-matrixcode-local-execution-audit-jdbc-repository-design.md`
- 创建 `docs/superpowers/plans/2026-06-25-matrixcode-local-execution-audit-jdbc-repository.md`

## 任务 1：正式本地执行仓储红绿

- [x] **步骤 1：编写失败的正式表仓储测试**

在 `JdbcLocalExecutionStateStoreTest` 中新增测试：

```java
@Test
void 保存后从正式本地执行表恢复状态且不写旧快照() throws Exception {
    var jdbcUrl = jdbcUrl("local_execution_domain_");
    migrate(jdbcUrl);
    var store = new JdbcLocalExecutionStateStore(repository(jdbcUrl), JsonTestSupport.objectMapper());

    store.saveWorkspaces(List.of(workspace()));
    store.saveTasks(Map.of("demo", List.of(task())), Map.of("demo", Map.of("task-1", List.of(log()))));
    store.saveAuditRecords(List.of(audit()));

    var restored = new JdbcLocalExecutionStateStore(repository(jdbcUrl), JsonTestSupport.objectMapper()).load();

    assertThat(restored.workspaces()).extracting(WorkspaceAuthorization::id).containsExactly("workspace-1");
    assertThat(restored.tasks().get("demo")).extracting(ExecutionTask::taskId).containsExactly("task-1");
    assertThat(restored.taskLogs().get("demo").get("task-1")).extracting(LocalTaskLog::content).containsExactly("任务完成");
    assertThat(restored.auditRecords()).extracting(AuditRecord::id).containsExactly("audit-1");
    assertThat(tableCount(jdbcUrl, "matrixcode_local_workspaces")).isEqualTo(1);
    assertThat(tableCount(jdbcUrl, "matrixcode_local_execution_tasks")).isEqualTo(1);
    assertThat(tableCount(jdbcUrl, "matrixcode_local_task_logs")).isEqualTo(1);
    assertThat(tableCount(jdbcUrl, "matrixcode_audit_records")).isEqualTo(1);
    assertThat(snapshotCount(jdbcUrl, "local-execution")).isZero();
}
```

- [x] **步骤 2：运行仓储测试验证失败**

运行：`/Users/Masons/Ai/Maven/bin/mvn -Dmaven.repo.local=/Users/Masons/Ai/Maven_Ai_Store -q -pl server -Dtest=JdbcLocalExecutionStateStoreTest test`

预期：FAIL，缺少正式表或仓储仍写入旧快照。

- [x] **步骤 3：新增迁移和最少仓储实现**

新增 `V30_1__extend_local_execution_for_domain_store.sql`：

```sql
create table matrixcode_local_workspaces (
    id varchar(64) not null,
    project_id varchar(64) not null,
    name varchar(160) not null,
    root_path varchar(1000) not null,
    status varchar(32) not null,
    created_at timestamp not null,
    last_accessed_at timestamp not null,
    updated_at timestamp not null,
    primary key (id)
);

create table matrixcode_local_task_logs (
    id varchar(64) not null,
    project_id varchar(64) not null,
    task_id varchar(64) not null,
    stream varchar(32) not null,
    content text,
    sort_order int not null,
    created_at timestamp not null,
    updated_at timestamp not null,
    primary key (id)
);

alter table matrixcode_local_execution_tasks add column tool_type varchar(64);
alter table matrixcode_local_execution_tasks add column approval_decision varchar(32);
alter table matrixcode_local_execution_tasks add column stdout_summary text;
alter table matrixcode_local_execution_tasks add column stderr_summary text;
alter table matrixcode_local_execution_tasks add column duration_millis bigint;
alter table matrixcode_local_execution_tasks add column sort_order int;
alter table matrixcode_audit_records add column task_id varchar(64);
alter table matrixcode_audit_records add column tool_type varchar(64);
alter table matrixcode_audit_records add column workspace_path varchar(1024);
alter table matrixcode_audit_records add column occurred_at timestamp;
alter table matrixcode_audit_records add column sort_order int;
```

改造 `JdbcLocalExecutionStateStore`：

- `load()` 优先读取正式表。
- `saveWorkspaces()` upsert `matrixcode_local_workspaces`。
- `saveTasks()` upsert `matrixcode_local_execution_tasks` 和 `matrixcode_local_task_logs`。
- `saveAuditRecords()` upsert `matrixcode_audit_records`。

- [x] **步骤 4：运行仓储测试验证通过**

运行同上命令，预期 PASS。

## 任务 2：旧快照回填兼容

- [x] **步骤 1：编写失败的旧快照回填测试**

在 `JdbcLocalExecutionStateStoreTest` 中新增测试：

```java
@Test
void 正式表为空时从旧快照恢复并回填正式表() throws Exception {
    var jdbcUrl = jdbcUrl("local_execution_backfill_");
    migrate(jdbcUrl);
    var repository = repository(jdbcUrl);
    repository.save("local-execution", 1, JsonTestSupport.objectMapper().writeValueAsString(
            new LocalExecutionSnapshot(1, List.of(workspace()), Map.of("demo", List.of(task())),
                    Map.of("demo", Map.of("task-1", List.of(log()))), List.of(audit()))
    ));

    var restored = new JdbcLocalExecutionStateStore(repository, JsonTestSupport.objectMapper()).load();

    assertThat(restored.workspaces()).extracting(WorkspaceAuthorization::id).containsExactly("workspace-1");
    assertThat(tableCount(jdbcUrl, "matrixcode_local_execution_tasks")).isEqualTo(1);
    assertThat(tableCount(jdbcUrl, "matrixcode_audit_records")).isEqualTo(1);
}
```

- [x] **步骤 2：运行回填测试验证失败**

运行：`/Users/Masons/Ai/Maven/bin/mvn -Dmaven.repo.local=/Users/Masons/Ai/Maven_Ai_Store -q -pl server -Dtest=JdbcLocalExecutionStateStoreTest test`

预期：FAIL，正式表为空时不会回填。

- [x] **步骤 3：实现旧快照回填**

在 `JdbcLocalExecutionStateStore.readSnapshot()` 中：

- 正式表有任意工作区、任务、日志或审计时返回正式表快照。
- 正式表为空时读取旧 `local-execution` 快照。
- 旧快照有效且非空时，调用正式表保存逻辑回填。

- [x] **步骤 4：运行回填测试验证通过**

运行同上命令，预期 PASS。

## 任务 3：Spring 回归、全量验证和图谱

- [x] **步骤 1：补 Spring JDBC 回归断言**

在 `JdbcPersistenceSpringTest` 中提交一条本地命令任务或复用现有工作区操作，重启后断言：

- `restored.localExecution().authorizedWorkspaces()` 包含 JDBC 工作区。
- 正式 `matrixcode_local_workspaces` 有记录。
- 正式 `matrixcode_local_execution_tasks` 有记录。
- 正式 `matrixcode_audit_records` 有记录。

- [x] **步骤 2：运行 Spring 回归验证通过**

运行：`/Users/Masons/Ai/Maven/bin/mvn -Dmaven.repo.local=/Users/Masons/Ai/Maven_Ai_Store -q -pl server -Dtest=JdbcPersistenceSpringTest test`

- [x] **步骤 3：运行关联测试**

运行：

```bash
/Users/Masons/Ai/Maven/bin/mvn -Dmaven.repo.local=/Users/Masons/Ai/Maven_Ai_Store -q -pl server -Dtest=JdbcLocalExecutionStateStoreTest,LocalTaskStoreTest,ApprovalPolicyTest test
```

- [x] **步骤 4：运行服务端全量测试**

运行：`/Users/Masons/Ai/Maven/bin/mvn -Dmaven.repo.local=/Users/Masons/Ai/Maven_Ai_Store -q -pl server test`

- [x] **步骤 5：提交前检查**

运行：`git diff --check`，并对真实密钥片段做精确扫描。

- [x] **步骤 6：更新 Obsidian**

新增第 30 阶段成果页，并更新首页、总览、阶段索引、模块地图、验证风险、状态持久化、本地执行与审批安全。

- [x] **步骤 7：提交**

运行：`git commit -m "feat: 增加本地执行审计 JDBC 仓储"`

---

## 执行记录

- 2026-06-25：先运行 `JdbcLocalExecutionStateStoreTest`，红灯失败，错误为缺少 `matrixcode_local_workspaces`，验证新增测试能暴露正式表缺口。
- 2026-06-25：实现过程中曾触发一次 Java 编译红灯，`JdbcLocalExecutionStateStore.java` 末尾多余右花括号导致 `需要 class、interface、enum 或 record`；已修复并重跑。
- 2026-06-25：`JdbcLocalExecutionStateStoreTest` 通过，Flyway 应用到 `v30.1`，正式表保存、恢复和旧快照回填均通过。
- 2026-06-25：`JdbcPersistenceSpringTest` 通过，Spring JDBC 模式重启后可从正式表恢复工作区、任务和审计记录，并确认 `local-execution` 不再写入旧快照。
- 2026-06-25：关联测试 `JdbcLocalExecutionStateStoreTest,LocalTaskStoreTest,ApprovalPolicyTest` 通过，确认审批策略和本地任务存储未回归。
- 2026-06-25：服务端全量测试 `/Users/Masons/Ai/Maven/bin/mvn -Dmaven.repo.local=/Users/Masons/Ai/Maven_Ai_Store -q -pl server test` 通过，Surefire 报告共 `294` 项测试。
- 2026-06-25：提交前执行 `git diff --check` 通过；对用户提供的真实密钥片段和数据库密码做精确扫描，无命中。
- 2026-06-25：已更新 Obsidian `MatrixCode` 图谱，新增 [[30 本地执行与审批审计正式 MySQL 仓储]]，并同步首页、总览、阶段索引、模块地图、验证风险、本地执行与状态持久化模块。
- 2026-06-25：准备提交 `feat: 增加本地执行审计 JDBC 仓储`。
