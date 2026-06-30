# MatrixCode Agent Runtime 受控队列认领实现计划

> **面向 AI 代理的工作者：** 必需子技能：使用 superpowers:subagent-driven-development（推荐）或 superpowers:executing-plans 逐任务实现此计划。步骤使用复选框（`- [ ]`）语法来跟踪进度。

**目标：** 为 `QUEUED` Agent Run 增加受控认领入口，使恢复排队运行可以进入 `RUNNING` 并留下审计事件。

**架构：** 后端在 `AgentRuntimeService` 中集中校验和转换运行状态，Controller 只暴露 HTTP 入口并保留冲突状态码。桌面端复用运行中心的 Agent 审计详情卡片，在排队运行上展示“认领运行”按钮并刷新 Agent Runtime 快照。

**技术栈：** Java 21、Spring Boot、MyBatis-Plus、JUnit 5、React、TypeScript、Vitest。

---

## 文件结构

- 修改：`server/src/test/java/com/matrixcode/agentruntime/AgentRuntimeServiceTest.java`，新增认领红绿测试。
- 修改：`server/src/test/java/com/matrixcode/agentruntime/AgentRuntimeControllerTest.java`，覆盖 HTTP 认领入口。
- 修改：`server/src/main/java/com/matrixcode/agentruntime/application/AgentRuntimeService.java`，新增 `claimQueuedRun(...)`。
- 修改：`server/src/main/java/com/matrixcode/agentruntime/api/AgentRuntimeController.java`，新增 `POST /claim`。
- 修改：`desktop/src/api/client.test.ts`，覆盖 `claimAgentRun(...)`。
- 修改：`desktop/src/api/client.ts`，新增 `claimAgentRun(...)`。
- 修改：`desktop/src/App.tsx`，增加认领处理器和 busy 状态。
- 修改：`desktop/src/components/InspectorPanel.tsx`，在排队运行上展示“认领运行”按钮。
- 修改：`desktop/src/test/App.test.tsx`，覆盖运行中心认领流程。
- 修改：Obsidian `MatrixCode` 项目图谱，记录第 68 阶段。

## 任务 1：后端服务层认领红绿

- [x] **步骤 1：编写失败测试**

在 `AgentRuntimeServiceTest` 增加：

```java
@Test
void 排队运行认领后进入运行中并保留恢复来源() {
    var repository = new RecordingAgentRuntimeRepository();
    var service = new AgentRuntimeService(Optional.of(repository), new ObjectMapper(), fixedClock);
    var failedRun = service.markFailed(... retryable true ...);
    var retryRun = service.queueRetry("demo", failedRun.id(), "user-reviewer");

    var claimed = service.claimQueuedRun("demo", retryRun.id(), "worker-1");

    assertThat(claimed.status()).isEqualTo(AgentRunStatus.RUNNING);
    assertThat(claimed.retryOfRunId()).isEqualTo(failedRun.id());
    assertThat(claimed.createdAt()).isEqualTo(retryRun.createdAt());
    assertThat(repository.events).extracting(AgentRunEventRecord::eventType)
            .contains("RUN_CLAIMED", "RUN_STARTED");
}
```

- [x] **步骤 2：运行测试验证失败**

运行：`/Users/Masons/Ai/Maven/bin/mvn -Dmaven.repo.local=/Users/Masons/Ai/Maven_Ai_Store -q -pl server -Dtest=AgentRuntimeServiceTest test`

预期：FAIL，原因是 `claimQueuedRun(...)` 尚不存在。

- [x] **步骤 3：编写最少实现代码**

在 `AgentRuntimeService` 中新增 `claimQueuedRun(...)`，只允许 `QUEUED` 状态，保留原运行字段并追加 `RUN_CLAIMED` / `RUN_STARTED`。

- [x] **步骤 4：运行测试验证通过**

运行：`/Users/Masons/Ai/Maven/bin/mvn -Dmaven.repo.local=/Users/Masons/Ai/Maven_Ai_Store -q -pl server -Dtest=AgentRuntimeServiceTest test`

预期：PASS。

## 任务 2：HTTP 认领入口

- [x] **步骤 1：编写失败测试**

在 `AgentRuntimeControllerTest` 增加：

```java
@Test
void 排队运行可以通过认领接口进入运行中() throws Exception {
    var retryRun = service.queueRetry("demo", failedRun.id(), "user-reviewer");

    mockMvc.perform(post("/api/projects/demo/agent-runs/" + retryRun.id() + "/claim")
            .param("actorUserId", "worker-1"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.status").value("RUNNING"))
            .andExpect(jsonPath("$.retryOfRunId").value(failedRun.id()));
}
```

- [x] **步骤 2：运行测试验证失败**

运行：`/Users/Masons/Ai/Maven/bin/mvn -Dmaven.repo.local=/Users/Masons/Ai/Maven_Ai_Store -q -pl server -Dtest=AgentRuntimeControllerTest test`

预期：FAIL，原因是 `/claim` 路由尚不存在。

- [x] **步骤 3：编写最少实现代码**

在 `AgentRuntimeController` 增加 `POST /{runId}/claim`，不可认领时返回 409。

- [x] **步骤 4：运行测试验证通过**

运行：`/Users/Masons/Ai/Maven/bin/mvn -Dmaven.repo.local=/Users/Masons/Ai/Maven_Ai_Store -q -pl server -Dtest=AgentRuntimeControllerTest test`

预期：PASS。

## 任务 3：桌面端运行中心认领入口

- [x] **步骤 1：编写失败测试**

在 `client.test.ts` 覆盖 `claimAgentRun(...)`；在 `App.test.tsx` 覆盖点击“认领运行”后调用 API 并刷新运行列表。

- [x] **步骤 2：运行测试验证失败**

运行：`npm --prefix desktop test -- src/api/client.test.ts src/test/App.test.tsx`

预期：FAIL，原因是 `claimAgentRun(...)` 和按钮尚不存在。

- [x] **步骤 3：编写最少实现代码**

新增前端 API、App 处理器、`claimBusyRunId` 状态和 Agent 审计详情按钮。

- [x] **步骤 4：运行测试验证通过**

运行：`npm --prefix desktop test -- src/api/client.test.ts src/test/App.test.tsx`

预期：PASS。

## 任务 4：完整验证、第二大脑与提交

- [x] **步骤 1：运行完整验证**

运行：

```bash
npm --prefix desktop test
npm --prefix desktop run build
/Users/Masons/Ai/Maven/bin/mvn -Dmaven.repo.local=/Users/Masons/Ai/Maven_Ai_Store -q -pl server test
set -a; source .env.local; set +a; /Users/Masons/Ai/Maven/bin/mvn -Dmaven.repo.local=/Users/Masons/Ai/Maven_Ai_Store -Dmatrixcode.real-runtime-test=true -q -pl server -Dtest=RealRuntimeIntegrationTest test
git diff --check
```

预期：测试、构建、真实集成和静态检查通过。

- [x] **步骤 2：更新第二大脑**

新增 `阶段成果/68 Agent Runtime 受控队列认领.md`，并更新首页、阶段索引、模块地图、验证与风险、桌面端和数据库迁移页。

- [x] **步骤 3：提交并推送**

提交：`feat(AgentRuntime): 增加受控队列认领`

推送：`git push origin HEAD:master`

## 自检

- 规格覆盖度：服务层、HTTP、桌面端、验证和第二大脑均有任务。
- 占位符扫描：计划正文未留下执行占位。
- 类型一致性：统一使用 `claimQueuedRun(...)`、`claimAgentRun(...)`、`claimBusyRunId` 和 `RUN_CLAIMED`。
