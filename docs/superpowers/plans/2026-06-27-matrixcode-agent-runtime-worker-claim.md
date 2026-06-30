# MatrixCode Agent Runtime 受控 Worker 认领实现计划

> **面向 AI 代理的工作者：** 必需子技能：使用 superpowers:subagent-driven-development（推荐）或 superpowers:executing-plans 逐任务实现此计划。步骤使用复选框（`- [ ]`）语法来跟踪进度。

**目标：** 为 Agent Runtime 增加项目级“认领下一条排队运行”能力，并写入认领租约元数据。

**架构：** 服务层负责队列消费语义、租约时间和审计事件；仓储层负责 MySQL 条件更新，保证并发下同一条 `QUEUED` 运行只会被一个 Worker 认领。桌面端只提供触发入口和刷新，不执行模型、命令、文件写入或 Patch。

**技术栈：** Java 21、Spring Boot、Flyway、MyBatis-Plus、JUnit 5、React、TypeScript、Vitest。

---

## 文件结构

- 修改：`server/src/main/java/com/matrixcode/agentruntime/domain/AgentRunRecord.java`，新增认领租约字段并保留兼容构造器。
- 修改：`server/src/main/java/com/matrixcode/agentruntime/application/AgentRuntimeRepository.java`，新增 `claimNextQueuedRun(...)`。
- 修改：`server/src/main/java/com/matrixcode/agentruntime/application/AgentRuntimeService.java`，新增服务层下一条认领入口。
- 修改：`server/src/main/java/com/matrixcode/agentruntime/api/AgentRuntimeController.java`，新增 `POST /claim-next`。
- 修改：`server/src/main/java/com/matrixcode/persistence/mybatis/entity/AgentRunEntity.java`，映射认领字段。
- 修改：`server/src/main/java/com/matrixcode/persistence/application/MybatisPlusAgentRuntimeRepository.java`，实现条件更新认领。
- 修改：`server/src/main/java/com/matrixcode/persistence/mybatis/mapper/AgentRunMapper.java`，保留 MyBatis-Plus 基础 Mapper。
- 创建：`server/src/main/resources/db/migration/V69_1__extend_agent_run_claim_lease.sql`，补字段、注释和索引。
- 修改：`server/src/test/java/com/matrixcode/agentruntime/AgentRuntimeServiceTest.java`，新增服务层红绿测试。
- 修改：`server/src/test/java/com/matrixcode/agentruntime/AgentRuntimeControllerTest.java`，新增 HTTP 红绿测试。
- 修改：`server/src/test/java/com/matrixcode/persistence/MybatisPlusAgentRuntimeRepositoryTest.java`，新增仓储和迁移验证。
- 修改：`server/src/test/java/com/matrixcode/persistence/application/RealRuntimeIntegrationTest.java`，加入真实 MySQL 下一条认领验证。
- 修改：`desktop/src/api/client.ts`、`desktop/src/api/client.test.ts`、`desktop/src/App.tsx`、`desktop/src/components/OperationsCenterDialog.tsx`、`desktop/src/test/App.test.tsx`，增加桌面端入口。
- 修改：Obsidian `MatrixCode` 项目图谱，记录第 69 阶段。

## 任务 1：服务层红灯

- [x] **步骤 1：编写失败测试**

在 `AgentRuntimeServiceTest` 新增：

```java
@Test
void 可以从项目队列认领最早的排队运行并写入租约事件() {
    var repository = new RecordingAgentRuntimeRepository();
    var service = new AgentRuntimeService(Optional.of(repository), new ObjectMapper(), fixedClock);
    var first = service.saveRun("run-1", "demo", ModelRole.DEVELOPER, "coding", "user-dev",
            "deepseek", "deepseek-chat", AgentRunStatus.QUEUED, "第一个任务", "等待执行", null, null);
    service.saveRun("run-2", "demo", ModelRole.DEVELOPER, "coding", "user-dev",
            "deepseek", "deepseek-chat", AgentRunStatus.QUEUED, "第二个任务", "等待执行", null, null);

    var claimed = service.claimNextQueuedRun("demo", "worker-1");

    assertThat(claimed).containsInstanceOf(AgentRunRecord.class);
    assertThat(claimed.get().id()).isEqualTo(first.id());
    assertThat(claimed.get().status()).isEqualTo(AgentRunStatus.RUNNING);
    assertThat(claimed.get().claimedByUserId()).isEqualTo("worker-1");
    assertThat(claimed.get().claimExpiresAt()).isEqualTo(fixedClock.instant().plusSeconds(900));
    assertThat(repository.events).extracting(AgentRunEventRecord::eventType)
            .contains("RUN_CLAIMED", "RUN_STARTED", "RUN_LEASED");
}
```

- [x] **步骤 2：运行测试验证失败**

运行：`/Users/Masons/Ai/Maven/bin/mvn -Dmaven.repo.local=/Users/Masons/Ai/Maven_Ai_Store -q -pl server -Dtest=AgentRuntimeServiceTest test`

预期：FAIL，原因是 `claimNextQueuedRun(...)` 和认领字段尚不存在。

- [x] **步骤 3：编写最少服务层实现**

新增 `AgentRunRecord` 字段、仓储接口默认实现和服务层 `claimNextQueuedRun(...)`。测试仓储按创建时间选最早 `QUEUED` 记录并更新为 `RUNNING`。

- [x] **步骤 4：运行服务层测试通过**

运行：`/Users/Masons/Ai/Maven/bin/mvn -Dmaven.repo.local=/Users/Masons/Ai/Maven_Ai_Store -q -pl server -Dtest=AgentRuntimeServiceTest test`

预期：PASS。

## 任务 2：MyBatis-Plus 原子认领和 DDL

- [x] **步骤 1：编写失败测试**

在 `MybatisPlusAgentRuntimeRepositoryTest` 新增：

```java
@Test
void 可以按项目原子认领下一条排队运行并写入租约字段() {
    var first = queuedRun("run-1", createdAt);
    var second = queuedRun("run-2", createdAt.plusSeconds(1));
    repository.saveRun(first);
    repository.saveRun(second);

    var claimed = repository.claimNextQueuedRun("demo", "worker-1",
            createdAt.plusSeconds(10), createdAt.plusSeconds(910));
    var next = repository.claimNextQueuedRun("demo", "worker-2",
            createdAt.plusSeconds(11), createdAt.plusSeconds(911));

    assertThat(claimed).hasValueSatisfying(run -> assertThat(run.id()).isEqualTo("run-1"));
    assertThat(next).hasValueSatisfying(run -> assertThat(run.id()).isEqualTo("run-2"));
}
```

- [x] **步骤 2：运行仓储测试验证失败**

运行：`/Users/Masons/Ai/Maven/bin/mvn -Dmaven.repo.local=/Users/Masons/Ai/Maven_Ai_Store -q -pl server -Dtest=MybatisPlusAgentRuntimeRepositoryTest test`

预期：FAIL，原因是仓储方法和迁移字段不存在。

- [x] **步骤 3：新增 DDL 和实体映射**

创建 `V69_1__extend_agent_run_claim_lease.sql`，增加：

```sql
alter table matrixcode_agent_runs
    add column claimed_by_user_id varchar(64) comment '认领本次运行的用户或 Worker ID，用于队列消费责任归属。',
    add column claimed_at timestamp(6) comment '运行被认领为 RUNNING 的时间。',
    add column claim_expires_at timestamp(6) comment '运行认领租约到期时间，后续用于恢复卡住的 RUNNING 运行。';
```

同时增加外键和 `(project_id, status, created_at, id)` 队列索引。

- [x] **步骤 4：实现 MyBatis-Plus 条件更新**

`claimNextQueuedRun(...)` 先读取最早 `QUEUED` 候选，再用 `WHERE id=? AND project_id=? AND status='QUEUED'` 更新；更新成功后按 ID 读回领域记录。

- [x] **步骤 5：运行仓储测试通过**

运行：`/Users/Masons/Ai/Maven/bin/mvn -Dmaven.repo.local=/Users/Masons/Ai/Maven_Ai_Store -q -pl server -Dtest=MybatisPlusAgentRuntimeRepositoryTest,DatabaseMigrationServiceTest,DatabaseMigrationCommentPolicyTest test`

预期：PASS。

## 任务 3：HTTP 和桌面端入口

- [x] **步骤 1：编写失败测试**

在 `AgentRuntimeControllerTest` 覆盖 `POST /api/projects/demo/agent-runs/claim-next`；在 `client.test.ts` 覆盖 `claimNextAgentRun(...)`；在 `App.test.tsx` 覆盖运行中心点击“认领下一条”后刷新。

- [x] **步骤 2：运行目标测试验证失败**

运行：

```bash
/Users/Masons/Ai/Maven/bin/mvn -Dmaven.repo.local=/Users/Masons/Ai/Maven_Ai_Store -q -pl server -Dtest=AgentRuntimeControllerTest test
npm --prefix desktop test -- src/api/client.test.ts src/test/App.test.tsx
```

预期：FAIL，原因是 HTTP 和桌面端入口尚不存在。

- [x] **步骤 3：实现 HTTP 和桌面端最少代码**

Controller 返回 `ResponseEntity<AgentRunRecord>`；队列为空返回 `204`。桌面端增加 API、App handler、busy 状态和运行中心按钮。

- [x] **步骤 4：运行目标测试通过**

运行同上命令，预期 PASS。

## 任务 4：真实集成、图谱和提交

- [x] **步骤 1：运行完整验证**

运行：

```bash
npm --prefix desktop test
npm --prefix desktop run build
/Users/Masons/Ai/Maven/bin/mvn -Dmaven.repo.local=/Users/Masons/Ai/Maven_Ai_Store -q -pl server test
./scripts/check-real-runtime.sh .env.local
set -a; source .env.local; set +a; /Users/Masons/Ai/Maven/bin/mvn -Dmaven.repo.local=/Users/Masons/Ai/Maven_Ai_Store -Dmatrixcode.real-runtime-test=true -q -pl server -Dtest=RealRuntimeIntegrationTest test
git diff --check
```

预期：测试、构建、真实运行检查、真实集成和静态检查通过。

- [x] **步骤 2：安全扫描**

运行：

```bash
rg -n "<旧地址>|<旧向量集合名>" server/src desktop/src scripts .env.example
git diff -- . ':!desktop/package-lock.json' | rg -n "<项目敏感信息模式>"
```

预期：无输出。

- [x] **步骤 3：更新第二大脑**

新增 `阶段成果/69 Agent Runtime 受控 Worker 认领.md`，并更新首页、阶段索引、模块地图、验证与风险、桌面端和数据库迁移页。

- [x] **步骤 4：提交并推送**

提交：`feat(AgentRuntime): 增加受控 Worker 认领`

推送：`git push origin HEAD:master`

## 自检

- 规格覆盖度：服务层、仓储、DDL、HTTP、桌面端、真实集成和第二大脑均有任务。
- 占位符扫描：计划不使用“待定”“TODO”“后续实现”作为执行占位。
- 类型一致性：统一使用 `claimNextQueuedRun(...)`、`claimNextAgentRun(...)`、`RUN_LEASED`、`claimedByUserId`、`claimedAt` 和 `claimExpiresAt`。
