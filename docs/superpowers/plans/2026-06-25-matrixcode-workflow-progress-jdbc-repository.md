# MatrixCode 工作流与验收投影正式 MySQL 仓储实现计划

> **面向 AI 代理的工作者：** 必需子技能：使用 superpowers:subagent-driven-development（推荐）或 superpowers:executing-plans 逐任务实现此计划。步骤使用复选框（`- [ ]`）语法来跟踪进度。

**目标：** 将工作流状态与产品验收投影迁移到正式 MySQL 表，并保留旧 `workbench-state` 空表回填路径。

**架构：** 新增应用层 `WorkbenchProgressRepository`，JDBC 模式由 `JdbcWorkbenchProgressRepository` 实现。`WorkflowService` 和 `WorkbenchService` 在存在正式仓储时优先读写正式表，旧快照只作为迁移回填源。

**技术栈：** Java 21、Spring Boot、Flyway、MySQL/H2、JUnit 5、AssertJ。

---

## 文件结构

- 创建：`server/src/main/java/com/matrixcode/workbench/application/WorkbenchProgressRepository.java`
  - 定义工作流项、工作流事件、验收投影的加载和保存接口。
- 创建：`server/src/main/java/com/matrixcode/persistence/application/JdbcWorkbenchProgressRepository.java`
  - JDBC 正式表实现，负责 schema 确保、项目记录补齐、读写三类状态。
- 创建：`server/src/main/resources/db/migration/V37_1__create_workflow_progress_tables.sql`
  - 创建 `matrixcode_workflow_items`、`matrixcode_workflow_events`、`matrixcode_acceptance_states`。
- 创建：`server/src/test/java/com/matrixcode/persistence/JdbcWorkbenchProgressRepositoryTest.java`
  - 验证 JDBC 仓储端到端保存和恢复。
- 创建：`server/src/test/java/com/matrixcode/workbench/WorkbenchProgressRepositoryServiceTest.java`
  - 验证 `WorkflowService` 和 `WorkbenchService` 的正式仓储优先与回填行为。
- 修改：`server/src/main/java/com/matrixcode/workflow/application/WorkflowService.java`
  - 增加 `Optional<WorkbenchProgressRepository>` 注入和读写路由。
- 修改：`server/src/main/java/com/matrixcode/workbench/application/WorkbenchService.java`
  - 增加 `Optional<WorkbenchProgressRepository>` 注入和验收读写路由。
- 修改：`server/src/test/java/com/matrixcode/workbench/WorkbenchServiceTest.java`
  - 如构造器变更影响现有 harness，补齐兼容构造。
- 修改：`server/src/test/java/com/matrixcode/localexecution/WorkbenchLocalExecutionTest.java`
  - 如构造器变更影响现有测试，补齐兼容构造。

## 任务 1：红灯测试

- [x] **步骤 1：新增 JDBC 仓储测试**

在 `JdbcWorkbenchProgressRepositoryTest` 中写测试：

```java
@Test
void 保存后从正式进度表恢复工作流和验收投影() throws Exception {
    var jdbcUrl = "jdbc:h2:mem:workbench_progress_" + System.nanoTime()
            + ";MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1";
    migrate(jdbcUrl);
    var repository = new JdbcWorkbenchProgressRepository(properties(jdbcUrl));

    repository.saveWorkflowItems(List.of(workflowItem()));
    repository.saveWorkflowEvents(Map.of("workflow-1", List.of(workflowEvent())));
    repository.saveAcceptances(Map.of("demo", new WorkbenchStateSnapshot.AcceptanceState("doc-1", false, "测试")));

    assertThat(repository.loadWorkflowItems()).containsExactly(workflowItem());
    assertThat(repository.loadWorkflowEvents().get("workflow-1")).containsExactly(workflowEvent());
    assertThat(repository.loadAcceptances().get("demo").returnToRole()).isEqualTo("测试");
}
```

- [x] **步骤 2：新增服务接入测试**

在 `WorkbenchProgressRepositoryServiceTest` 中写测试：

```java
@Test
void 工作流服务优先读取正式仓储并写回正式仓储() {
    var store = new InMemoryWorkbenchStateStore();
    var repository = new RecordingWorkbenchProgressRepository();
    repository.workflowItems = List.of(new WorkflowItem("repo-item", "demo", "正式仓储", WorkflowState.REVIEW_PENDING));
    repository.workflowEvents = Map.of("repo-item", List.of(workflowEvent("repo-event", "repo-item")));

    var service = new WorkflowService(store, repository);

    var frozen = service.apply("repo-item", WorkflowEventType.FREEZE, "user-product");

    assertThat(frozen.state()).isEqualTo(WorkflowState.FROZEN);
    assertThat(repository.workflowEvents.get("repo-item")).hasSize(2);
    assertThat(store.load().workflowItems()).isEmpty();
}
```

- [x] **步骤 3：运行红灯**

运行：

```bash
/Users/Masons/Ai/Maven/bin/mvn -Dmaven.repo.local=/Users/Masons/Ai/Maven_Ai_Store -q -pl server -Dtest=JdbcWorkbenchProgressRepositoryTest,WorkbenchProgressRepositoryServiceTest test
```

预期：编译失败，缺少 `WorkbenchProgressRepository`、`JdbcWorkbenchProgressRepository` 和新的构造器。

## 任务 2：实现仓储接口和 JDBC 表

- [x] **步骤 1：创建接口**

`WorkbenchProgressRepository` 方法：

```java
List<WorkflowItem> loadWorkflowItems();
void saveWorkflowItems(List<WorkflowItem> items);
Map<String, List<WorkflowEvent>> loadWorkflowEvents();
void saveWorkflowEvents(Map<String, List<WorkflowEvent>> events);
Map<String, WorkbenchStateSnapshot.AcceptanceState> loadAcceptances();
void saveAcceptances(Map<String, WorkbenchStateSnapshot.AcceptanceState> acceptances);
```

- [x] **步骤 2：创建 Flyway 迁移**

创建三张表并添加项目、工作项外键和常用索引。

- [x] **步骤 3：创建 JDBC 实现**

实现读写三类状态；写工作流事件时通过 `matrixcode_workflow_items` 查 `project_id`，找不到工作项则跳过该事件。

- [x] **步骤 4：运行仓储测试**

运行目标测试，预期仓储测试通过，服务接入测试仍失败或待实现。

## 任务 3：接入 WorkflowService

- [x] **步骤 1：增加构造器**

保留现有构造器，新增：

```java
public WorkflowService(WorkbenchStateStore stateStore, WorkbenchProgressRepository progressRepository)
```

Spring 构造器使用 `Optional<WorkbenchProgressRepository>`。

- [x] **步骤 2：加载策略**

正式仓储非空则加载正式仓储；正式仓储为空则加载旧快照并回填正式仓储。

- [x] **步骤 3：保存策略**

正式仓储存在时只保存正式仓储；否则保存旧快照。

- [x] **步骤 4：运行工作流服务测试**

运行：

```bash
/Users/Masons/Ai/Maven/bin/mvn -Dmaven.repo.local=/Users/Masons/Ai/Maven_Ai_Store -q -pl server -Dtest=WorkflowServiceTest,WorkbenchProgressRepositoryServiceTest test
```

预期：工作流相关测试通过。

## 任务 4：接入 WorkbenchService 验收投影

- [x] **步骤 1：增加构造器兼容路径**

保留现有构造器，新增正式仓储参数，Spring 构造器接收 `Optional<WorkbenchProgressRepository>`。

- [x] **步骤 2：加载验收策略**

正式仓储非空则加载正式仓储；正式仓储为空则加载旧快照并回填正式仓储。

- [x] **步骤 3：提交验收保存策略**

正式仓储存在时只保存正式仓储；否则保存旧快照。

- [x] **步骤 4：运行工作台测试**

运行：

```bash
/Users/Masons/Ai/Maven/bin/mvn -Dmaven.repo.local=/Users/Masons/Ai/Maven_Ai_Store -q -pl server -Dtest=WorkbenchServiceTest,WorkbenchLocalExecutionTest,WorkbenchProgressRepositoryServiceTest test
```

预期：工作台相关测试通过。

## 任务 5：验证与文档

- [x] **步骤 1：运行局部回归**

```bash
/Users/Masons/Ai/Maven/bin/mvn -Dmaven.repo.local=/Users/Masons/Ai/Maven_Ai_Store -q -pl server -Dtest='JdbcWorkbenchProgressRepositoryTest,WorkbenchProgressRepositoryServiceTest,WorkflowServiceTest,WorkbenchServiceTest,WorkbenchLocalExecutionTest' test
```

- [x] **步骤 2：运行服务端全量测试**

```bash
/Users/Masons/Ai/Maven/bin/mvn -Dmaven.repo.local=/Users/Masons/Ai/Maven_Ai_Store -q -pl server test
```

- [x] **步骤 3：运行真实基础设施预检和真实集成测试**

```bash
set -a; source .env.local; set +a; ./scripts/check-real-runtime.sh
set -a; source .env.local; set +a; /Users/Masons/Ai/Maven/bin/mvn -Dmaven.repo.local=/Users/Masons/Ai/Maven_Ai_Store -q -pl server -Dmatrixcode.real-runtime-test=true -Dtest=RealRuntimeIntegrationTest test
```

- [x] **步骤 4：重启真实后端并验证 health**

```bash
./scripts/run-real-local.sh
curl -s http://localhost:18080/actuator/health
```

- [x] **步骤 5：更新 Obsidian 图谱**

更新 `MatrixCode`：

- `1 项目首页.md`
- `2 项目总览.md`
- `3 阶段索引.md`
- `4 模块地图.md`
- `6 验证与风险.md`
- `14 状态持久化与数据库迁移.md`
- 新增 `阶段成果/37 工作流与验收投影正式 MySQL 仓储.md`

- [x] **步骤 6：最终检查**

运行 `git diff --check`、敏感信息扫描、旧 schema 扫描，确保 `matrix_code` 约定未回退。
