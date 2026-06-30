# MatrixCode Agent Runtime Worker 租约治理实现计划

> **面向 AI 代理的工作者：** 必需子技能：使用 superpowers:subagent-driven-development（推荐）或 superpowers:executing-plans 逐任务实现此计划。步骤使用复选框（`- [ ]`）语法来跟踪进度。

**目标：** 为 Agent Runtime 增加 Worker 租约续期、过期回收和单次可控 tick。

**架构：** 服务层负责租约业务语义、事件审计和失败恢复归一；仓储层使用 MyBatis-Plus 条件更新保证续租与过期回收并发安全；Worker 服务只执行单次 tick，不启用后台自动线程。

**技术栈：** Java 21、Spring Boot、MyBatis-Plus、JUnit 5、MockMvc、React/Vitest 既有回归。

---

## 文件结构

- 修改：`server/src/main/java/com/matrixcode/agentruntime/application/AgentRuntimeRepository.java`，新增续租和过期回收接口。
- 修改：`server/src/main/java/com/matrixcode/agentruntime/application/AgentRuntimeService.java`，新增租约续期和过期回收业务方法。
- 创建：`server/src/main/java/com/matrixcode/agentruntime/application/AgentRuntimeWorkerService.java`，封装单次 Worker tick。
- 创建：`server/src/main/java/com/matrixcode/agentruntime/domain/AgentRuntimeWorkerTickResult.java`，返回 tick 摘要。
- 修改：`server/src/main/java/com/matrixcode/agentruntime/api/AgentRuntimeController.java`，新增 `renew-lease` 和 `worker-tick` 端点。
- 修改：`server/src/main/java/com/matrixcode/persistence/application/MybatisPlusAgentRuntimeRepository.java`，实现 MyBatis-Plus 条件更新。
- 修改：`server/src/test/java/com/matrixcode/agentruntime/AgentRuntimeServiceTest.java`，新增服务层红绿测试。
- 修改：`server/src/test/java/com/matrixcode/agentruntime/AgentRuntimeControllerTest.java`，新增 HTTP 红绿测试。
- 修改：`server/src/test/java/com/matrixcode/persistence/MybatisPlusAgentRuntimeRepositoryTest.java`，新增仓储红绿测试。
- 修改：`server/src/test/java/com/matrixcode/persistence/application/RealRuntimeIntegrationTest.java`，新增真实 MySQL 验证。
- 修改：Obsidian `MatrixCode` 项目图谱，记录第 70 阶段和回溯结论。

## 任务 1：服务层租约续期红绿

- [x] **步骤 1：编写失败测试**

在 `AgentRuntimeServiceTest` 新增：

```java
@Test
void 当前认领人可以续期运行租约并写入事件() {
    var repository = new RecordingAgentRuntimeRepository();
    var service = new AgentRuntimeService(Optional.of(repository), new ObjectMapper(), fixedClock);
    service.saveRun("run-1", "demo", ModelRole.DEVELOPER, "coding", "user-dev",
            "deepseek", "deepseek-chat", AgentRunStatus.QUEUED, "修复问题", "等待执行", null, null);
    var claimed = service.claimNextQueuedRun("demo", "worker-1").orElseThrow();

    var renewed = service.renewClaimLease("demo", claimed.id(), "worker-1");

    assertThat(renewed).hasValueSatisfying(run -> {
        assertThat(run.status()).isEqualTo(AgentRunStatus.RUNNING);
        assertThat(run.claimedByUserId()).isEqualTo("worker-1");
        assertThat(run.claimExpiresAt()).isEqualTo(fixedClock.instant().plusSeconds(900));
    });
    assertThat(repository.events).extracting(AgentRunEventRecord::eventType)
            .contains("RUN_LEASE_RENEWED");
}
```

- [x] **步骤 2：运行测试验证失败**

运行：`/Users/Masons/Ai/Maven/bin/mvn -Dmaven.repo.local=/Users/Masons/Ai/Maven_Ai_Store -q -pl server -Dtest=AgentRuntimeServiceTest test`

预期：FAIL，原因是 `renewClaimLease(...)` 尚不存在。

- [x] **步骤 3：实现最少服务层续租**

新增仓储默认方法和服务层 `renewClaimLease(...)`，测试内存仓储按 `projectId`、`runId`、`RUNNING` 和 `claimedByUserId` 匹配后刷新 `claimExpiresAt`。

- [x] **步骤 4：运行服务层续租测试通过**

运行同上命令，预期 PASS。

## 任务 2：过期租约回收红绿

- [x] **步骤 1：编写失败测试**

在 `AgentRuntimeServiceTest` 新增：

```java
@Test
void 可以把过期运行回收为可重试失败并写入审计事件() {
    var repository = new RecordingAgentRuntimeRepository();
    var service = new AgentRuntimeService(Optional.of(repository), new ObjectMapper(), fixedClock);
    service.saveRun("run-1", "demo", ModelRole.DEVELOPER, "coding", "user-dev",
            "deepseek", "deepseek-chat", AgentRunStatus.QUEUED, "修复问题", "等待执行", null, null);
    var claimed = service.claimNextQueuedRun("demo", "worker-1").orElseThrow();
    repository.forceLeaseExpiry(claimed.id(), fixedClock.instant().minusSeconds(1));

    var expired = service.expireRunningLeases("demo", 10);

    assertThat(expired).hasSize(1);
    assertThat(expired.get(0).status()).isEqualTo(AgentRunStatus.FAILED);
    assertThat(expired.get(0).retryable()).isTrue();
    assertThat(repository.events).extracting(AgentRunEventRecord::eventType)
            .contains("RUN_LEASE_EXPIRED", "RUN_FAILED");
}
```

- [x] **步骤 2：运行测试验证失败**

运行：`/Users/Masons/Ai/Maven/bin/mvn -Dmaven.repo.local=/Users/Masons/Ai/Maven_Ai_Store -q -pl server -Dtest=AgentRuntimeServiceTest test`

预期：FAIL，原因是 `expireRunningLeases(...)` 尚不存在。

- [x] **步骤 3：实现过期回收服务层**

服务层调用仓储过期回收，回收结果写 `RUN_LEASE_EXPIRED` 和 `RUN_FAILED`；失败摘要固定为“Worker 租约已过期，运行被系统回收”，并标记 `retryable=true`。

- [x] **步骤 4：运行服务层回收测试通过**

运行同上命令，预期 PASS。

## 任务 3：MyBatis-Plus 条件更新

- [x] **步骤 1：编写失败测试**

在 `MybatisPlusAgentRuntimeRepositoryTest` 新增续租和过期回收测试：非认领人续租返回空；过期回收只更新 `claim_expires_at <= now` 的 `RUNNING` 运行。

- [x] **步骤 2：运行仓储测试验证失败**

运行：`/Users/Masons/Ai/Maven/bin/mvn -Dmaven.repo.local=/Users/Masons/Ai/Maven_Ai_Store -q -pl server -Dtest=MybatisPlusAgentRuntimeRepositoryTest test`

预期：FAIL，原因是仓储方法尚未实现。

- [x] **步骤 3：实现仓储条件更新**

`renewClaimLease(...)` 使用条件更新刷新 `claim_expires_at`；`expireRunningLeases(...)` 先取候选，再逐条 `WHERE id AND status=RUNNING AND claim_expires_at <= now` 更新为 `FAILED`。

- [x] **步骤 4：运行仓储测试通过**

运行同上命令，预期 PASS。

## 任务 4：Worker tick 和 HTTP

- [x] **步骤 1：编写失败测试**

在 `AgentRuntimeControllerTest` 覆盖：

- `POST /api/projects/demo/agent-runs/run-1/renew-lease?actorUserId=worker-1` 成功返回 `RUNNING`。
- `POST /api/projects/demo/agent-runs/worker-tick?workerId=worker-1` 返回过期数量和认领运行。

- [x] **步骤 2：运行控制器测试验证失败**

运行：`/Users/Masons/Ai/Maven/bin/mvn -Dmaven.repo.local=/Users/Masons/Ai/Maven_Ai_Store -q -pl server -Dtest=AgentRuntimeControllerTest test`

预期：FAIL，原因是 Controller 和 Worker 服务尚不存在。

- [x] **步骤 3：实现 Worker 服务和 HTTP**

新增 `AgentRuntimeWorkerService.tick(...)`，按顺序调用 `expireRunningLeases(projectId, 20)` 和 `claimNextQueuedRun(projectId, workerId)`；Controller 暴露续租和 tick 接口。

- [x] **步骤 4：运行控制器测试通过**

运行同上命令，预期 PASS。

## 任务 5：真实集成、图谱和提交

- [x] **步骤 1：运行完整验证**

运行：

```bash
/Users/Masons/Ai/Maven/bin/mvn -Dmaven.repo.local=/Users/Masons/Ai/Maven_Ai_Store -q -pl server test
npm --prefix desktop test
npm --prefix desktop run build
./scripts/check-real-runtime.sh .env.local
set -a; source .env.local; set +a; /Users/Masons/Ai/Maven/bin/mvn -Dmaven.repo.local=/Users/Masons/Ai/Maven_Ai_Store -Dmatrixcode.real-runtime-test=true -q -pl server -Dtest=RealRuntimeIntegrationTest test
git diff --check
```

结果：测试、构建、真实运行检查、真实集成和静态检查通过。

实际证据：

- 服务端全量：`files=79 tests=396 failures=0 errors=0 skipped=0`。
- 桌面端全量：`Tests 102 passed`。
- 桌面端构建：`vite build` 退出码 0。
- 真实运行检查：MySQL、Milvus、Redis、RocketMQ 连通性通过。
- 真实集成：`RealRuntimeIntegrationTest` 7 条测试通过，真实 MySQL `matrix_code` 保持 Flyway `v69.1`。
- `git diff --check`：无输出。
- 调试补强：真实 MySQL JDBC URL 增加 `connectTimeout=5000` 与 `socketTimeout=30000`，避免真实环境网络读无限等待。

- [x] **步骤 2：安全扫描**

运行：

```bash
rg -n "<旧地址>|<旧向量集合名>" server/src desktop/src scripts .env.example
git diff -- . ':!desktop/package-lock.json' | rg -n "<真实密钥或密码特征>"
```

结果：无输出。

- [x] **步骤 3：更新第二大脑**

新增 `阶段成果/70 Agent Runtime Worker 租约治理.md`，并更新首页、阶段索引、模块地图、验证与风险、模型网关与上下文门禁、状态持久化与数据库迁移页。

- [x] **步骤 4：提交并推送**

提交：`feat(agent-runtime): 增加 Worker 租约治理`

推送：`git push origin HEAD:master`

## 自检

- 规格覆盖度：续租、过期回收、仓储条件更新、Worker tick、HTTP、真实集成和第二大脑均有任务。
- 占位符扫描：计划不使用“待定”“TODO”“后续实现”作为执行占位。
- 类型一致性：统一使用 `renewClaimLease(...)`、`expireRunningLeases(...)`、`AgentRuntimeWorkerService.tick(...)`、`RUN_LEASE_RENEWED` 和 `RUN_LEASE_EXPIRED`。
