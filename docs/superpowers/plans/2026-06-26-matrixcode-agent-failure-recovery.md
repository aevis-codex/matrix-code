# MatrixCode Agent 失败恢复与工具 trace 实现计划

> **面向 AI 代理的工作者：** 必需子技能：使用 superpowers:subagent-driven-development（推荐）或 superpowers:executing-plans 逐任务实现此计划。步骤使用复选框（`- [x]`）语法来跟踪进度。

**目标：** 为 Agent Runtime 增加失败摘要、可重试标记、重试来源和失败事件展示，让角色智能体运行具备上线所需的恢复与审计基础。

**架构：** 扩展 `AgentRunRecord` 作为失败恢复元数据承载对象；`AgentRuntimeService` 提供 `markFailed` 统一入口并追加 `RUN_FAILED` 事件；MySQL 通过 Flyway 扩展 `matrixcode_agent_runs`；桌面端复用右侧 Agent 运行卡片展示恢复策略。

**技术栈：** Java 21、Spring Boot、Flyway、MyBatis-Plus、JUnit 5、React、TypeScript、Vitest。

---

## 文件结构

- 修改：`server/src/main/java/com/matrixcode/agentruntime/domain/AgentRunRecord.java`，新增失败恢复字段、兼容构造器和归一化。
- 修改：`server/src/main/java/com/matrixcode/agentruntime/application/AgentRuntimeService.java`，新增带失败字段的保存入口和 `markFailed(...)`。
- 修改：`server/src/main/java/com/matrixcode/persistence/mybatis/entity/AgentRunEntity.java`，映射新增字段。
- 新增：`server/src/main/resources/db/migration/V53_1__extend_agent_run_failure_recovery.sql`，扩展 Agent 运行表并写字段注释。
- 修改：`server/src/test/java/com/matrixcode/agentruntime/AgentRuntimeServiceTest.java`，覆盖失败标记行为。
- 修改：`server/src/test/java/com/matrixcode/persistence/MybatisPlusAgentRuntimeRepositoryTest.java`，覆盖 MyBatis-Plus 新增字段保存恢复。
- 修改：`desktop/src/api/client.ts`，扩展 `AgentRunRecord` 类型。
- 修改：`desktop/src/components/InspectorPanel.tsx`，展示失败摘要、恢复策略和重试来源。
- 修改：`desktop/src/test/App.test.tsx`，覆盖右侧 Agent 运行卡片与运行中心失败事件。
- 新增：`/Users/Masons/Documents/Obsidian/Aevis/MatrixCode/阶段成果/53 Agent 失败恢复与工具 trace.md`，更新项目图谱。

### 任务 1：Agent Runtime 服务层失败恢复字段

- [x] **步骤 1：编写失败的服务测试**

在 `server/src/test/java/com/matrixcode/agentruntime/AgentRuntimeServiceTest.java` 新增测试：

```java
@Test
void 标记失败会保存可恢复主记录并追加失败事件() {
    var repository = new RecordingAgentRuntimeRepository();
    var service = new AgentRuntimeService(Optional.of(repository), new ObjectMapper(), fixedClock);

    var run = service.markFailed(
            "run-1",
            "demo",
            ModelRole.DEVELOPER,
            "coding",
            "user-dev",
            "deepseek",
            "deepseek-chat",
            "修复支付失败重试",
            "测试命令超时，未产生交接文档",
            true,
            "run-0"
    );

    assertThat(run.status()).isEqualTo(AgentRunStatus.FAILED);
    assertThat(run.failureSummary()).isEqualTo("测试命令超时，未产生交接文档");
    assertThat(run.retryable()).isTrue();
    assertThat(run.retryOfRunId()).isEqualTo("run-0");
    assertThat(run.finishedAt()).isEqualTo(fixedClock.instant());
    assertThat(repository.savedRuns).containsExactly(run);
    assertThat(repository.events).hasSize(1);
    assertThat(repository.events.getFirst().eventType()).isEqualTo("RUN_FAILED");
    assertThat(repository.events.getFirst().eventPayload()).contains("\"retryable\":true");
}
```

- [x] **步骤 2：运行测试验证失败**

运行：

```bash
/Users/Masons/Ai/Maven/bin/mvn -Dmaven.repo.local=/Users/Masons/Ai/Maven_Ai_Store -q -pl server -Dtest=AgentRuntimeServiceTest test
```

预期：编译失败，`AgentRuntimeService.markFailed(...)`、`AgentRunRecord.failureSummary()`、`retryable()`、`retryOfRunId()` 尚不存在。

实际：编译失败，错误集中在 `AgentRuntimeService` 找不到 `markFailed(...)`。

- [x] **步骤 3：实现最少服务代码**

`AgentRunRecord` 新增 17 参数主构造器字段，并保留旧 14 参数构造器：

```java
public AgentRunRecord(
        String id,
        String projectId,
        String roleKey,
        String agentKind,
        String actorUserId,
        String providerId,
        String modelName,
        AgentRunStatus status,
        String goal,
        String summary,
        Instant createdAt,
        Instant startedAt,
        Instant finishedAt,
        Instant updatedAt
) {
    this(id, projectId, roleKey, agentKind, actorUserId, providerId, modelName, status,
            goal, summary, "", false, "", createdAt, startedAt, finishedAt, updatedAt);
}
```

`AgentRuntimeService.markFailed(...)` 调用带失败字段的 `saveRun(...)`，并追加 `RUN_FAILED` 事件。

- [x] **步骤 4：运行服务测试验证通过**

运行步骤 2 命令，预期退出码 0。

实际：退出码 0。

### 任务 2：MySQL 正式仓储持久化失败恢复字段

- [x] **步骤 1：编写失败的仓储测试**

在 `MybatisPlusAgentRuntimeRepositoryTest` 的运行记录构造中传入：

```java
"测试命令超时",
true,
"run-0",
```

并断言：

```java
assertThat(repository.recentRuns("demo", 10).getFirst().failureSummary()).isEqualTo("测试命令超时");
assertThat(repository.recentRuns("demo", 10).getFirst().retryable()).isTrue();
assertThat(repository.recentRuns("demo", 10).getFirst().retryOfRunId()).isEqualTo("run-0");
```

- [x] **步骤 2：运行测试验证失败**

运行：

```bash
/Users/Masons/Ai/Maven/bin/mvn -Dmaven.repo.local=/Users/Masons/Ai/Maven_Ai_Store -q -pl server -Dtest=MybatisPlusAgentRuntimeRepositoryTest,DatabaseMigrationServiceTest test
```

预期：新增字段未迁移或实体未映射，导致字段丢失或 SQL 失败。

实际：保存恢复后 `failureSummary/retryable/retryOfRunId` 被读回默认值，断言失败。

- [x] **步骤 3：新增 Flyway 迁移和实体映射**

创建 `V53_1__extend_agent_run_failure_recovery.sql`：

```sql
alter table matrixcode_agent_runs
    add column failure_summary text comment '智能体运行失败摘要，保存可展示的短错误结论，不保存完整异常堆栈、完整 prompt 或工具输出全文。';

alter table matrixcode_agent_runs
    add column retryable boolean not null default false comment '本次失败是否允许基于相同目标和上下文进行恢复重试。';

alter table matrixcode_agent_runs
    add column retry_of_run_id varchar(64) not null default '' comment '如果本次运行是恢复重试，记录来源智能体运行 ID；原始运行为空字符串。';

create index idx_mc_agent_runs_failure_recovery on matrixcode_agent_runs (project_id, status, retryable, updated_at);
```

更新 `AgentRunEntity` 的字段、`fromDomain` 和 `toDomain`。

- [x] **步骤 4：运行仓储测试验证通过**

运行步骤 2 命令，预期退出码 0。

实际：退出码 0，Flyway 应用 14 个迁移到 `v53.1`。

### 任务 3：桌面端展示失败恢复状态

- [x] **步骤 1：编写失败的 UI 测试**

在 `desktop/src/test/App.test.tsx` 把 Agent fixture 设为失败运行，并断言：

```ts
expect(Agent运行.getByText(/失败/)).toBeTruthy();
expect(Agent运行.getByText(/恢复策略 可重试/)).toBeTruthy();
expect(Agent运行.getByText(/失败摘要 测试命令超时/)).toBeTruthy();
expect(Agent运行.getByText(/重试来源 run-0/)).toBeTruthy();
```

运行中心断言：

```ts
expect(Agent运行时间线.getByText(/运行失败/)).toBeTruthy();
expect(Agent运行时间线.getByText(/测试命令超时/)).toBeTruthy();
```

- [x] **步骤 2：运行测试验证失败**

运行：

```bash
npm --prefix desktop test -- --run src/test/App.test.tsx
```

预期：页面尚未展示恢复策略、失败摘要和重试来源。

实际：收紧状态断言后，目标测试失败在缺少 `恢复策略 可重试`。

- [x] **步骤 3：实现最少前端代码**

`desktop/src/api/client.ts` 的 `AgentRunRecord` 增加可选字段：

```ts
failureSummary?: string;
retryable?: boolean;
retryOfRunId?: string;
```

`InspectorPanel` 在 `latestAgentRun.status === 'FAILED'` 时展示恢复策略、失败摘要和重试来源。

- [x] **步骤 4：运行 UI 测试验证通过**

运行步骤 2 命令，预期退出码 0。

实际：`src/test/App.test.tsx` 46 条测试通过。

### 任务 4：回归、真实联调、第二大脑和提交

- [x] **步骤 1：运行服务端关联回归**

```bash
/Users/Masons/Ai/Maven/bin/mvn -Dmaven.repo.local=/Users/Masons/Ai/Maven_Ai_Store -q -pl server -Dtest=AgentRuntimeServiceTest,MybatisPlusAgentRuntimeRepositoryTest,DatabaseMigrationCommentPolicyTest,DatabaseMigrationServiceTest test
```

实际：退出码 0。

- [x] **步骤 2：运行服务端全量测试**

```bash
/Users/Masons/Ai/Maven/bin/mvn -Dmaven.repo.local=/Users/Masons/Ai/Maven_Ai_Store -q -pl server test
```

实际：退出码 0，Surefire 统计 `files=78 tests=362 failures=0 errors=0 skipped=0`。

- [x] **步骤 3：运行桌面端全量测试和构建**

```bash
npm --prefix desktop test
npm --prefix desktop run build
```

实际：桌面端全量 `Tests 93 passed`；生产构建退出码 0。

- [x] **步骤 4：运行真实集成**

```bash
set -a; source .env.local; set +a
/Users/Masons/Ai/Maven/bin/mvn -Dmaven.repo.local=/Users/Masons/Ai/Maven_Ai_Store -q -pl server -Dmatrixcode.real-runtime-test=true -Dtest=RealRuntimeIntegrationTest test
```

实际：退出码 0，真实 MySQL `matrix_code` 从 Flyway `52.1` 迁移到 `53.1`；`RealRuntimeIntegrationTest tests=7 failures=0 errors=0 skipped=0`。

- [x] **步骤 5：静态和安全检查**

```bash
git diff --check
```

同时精确扫描真实 API Key 和数据库密码字面值，确认仓库与 Obsidian 文档无泄漏。

实际：`git diff --check` 无输出；真实 API Key 和数据库密码精确扫描无命中。

- [x] **步骤 6：更新第二大脑并回溯对齐**

新增第 53 阶段成果，更新首页、总览、阶段索引、模块地图、验证与风险、Agent Runtime 专题或运行态专题，记录对最初需求和第 51、52 阶段模型缓存目标的回溯结论。

实际：新增 `阶段成果/53 Agent 失败恢复与工具 trace.md`，并更新项目首页、总览、阶段索引、模块地图、验证与风险、模型网关专题和持久化专题。

- [x] **步骤 7：提交并推送**

```bash
git add .
git commit -m "feat(Agent运行): 增加失败恢复 trace"
git push origin HEAD:master
```

## 2026-06-26 计划状态回填

- 本次回溯确认：该计划对应能力已在后续阶段完成，并已进入 Obsidian 阶段成果、验证与风险、模块地图和真实验证记录。
- 原未勾选项属于历史计划状态未回填，不代表当前功能缺失；已统一回填为完成，后续验收以对应阶段成果页和最新验证命令为准。
