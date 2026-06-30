# MatrixCode Agent Runtime 恢复重试队列实现计划

> **面向 AI 代理的工作者：** 必需子技能：使用 superpowers:subagent-driven-development（推荐）或 superpowers:executing-plans 逐任务实现此计划。步骤使用复选框（`- [ ]`）语法来跟踪进度。

**目标：** 为失败且可重试的 Agent Run 增加恢复计划和重试排队入口，并在桌面端运行中心提供重试按钮。

**架构：** 后端在 Agent Runtime 服务层集中判断恢复资格，只创建新的 `QUEUED` 运行和审计事件，不触发执行。桌面端通过运行中心调用重试接口，成功后刷新 Agent Runtime 快照。

**技术栈：** Java 21、Spring Boot、MyBatis-Plus、JUnit 5、Vitest、React、TypeScript。

---

## 文件结构

- 创建：`server/src/main/java/com/matrixcode/agentruntime/domain/AgentRunRecoveryPlan.java`，表达恢复计划。
- 修改：`server/src/main/java/com/matrixcode/agentruntime/application/AgentRuntimeRepository.java`，增加单次运行查询。
- 修改：`server/src/main/java/com/matrixcode/persistence/application/MybatisPlusAgentRuntimeRepository.java`，实现 `findRun(...)`。
- 修改：`server/src/main/java/com/matrixcode/agentruntime/application/AgentRuntimeService.java`，增加恢复计划和重试排队逻辑。
- 修改：`server/src/main/java/com/matrixcode/agentruntime/api/AgentRuntimeController.java`，暴露恢复计划和重试接口。
- 修改：`server/src/test/java/com/matrixcode/agentruntime/AgentRuntimeServiceTest.java`，覆盖恢复资格、排队和拒绝场景。
- 修改：`server/src/test/java/com/matrixcode/persistence/MybatisPlusAgentRuntimeRepositoryTest.java`，覆盖正式仓储单次读取。
- 修改：`server/src/test/java/com/matrixcode/agentruntime/AgentRuntimeControllerTest.java`，覆盖 HTTP 恢复入口。
- 修改：`desktop/src/api/client.ts`，增加恢复计划类型和重试 API。
- 修改：`desktop/src/api/client.test.ts`，覆盖前端 API 请求。
- 修改：`desktop/src/App.tsx`，增加运行中心重试处理。
- 修改：`desktop/src/components/InspectorPanel.tsx`，展示失败运行重试按钮。
- 修改：`desktop/src/test/App.test.tsx`，覆盖按钮展示、调用和刷新。
- 修改：Obsidian `MatrixCode` 项目图谱，记录第 67 阶段。

## 任务 1：后端领域服务红灯

**文件：**
- 修改：`server/src/test/java/com/matrixcode/agentruntime/AgentRuntimeServiceTest.java`

- [x] **步骤 1：编写失败测试**

```java
@Test
void 可重试失败运行会生成恢复计划并排队新运行() {
    var repository = new RecordingAgentRuntimeRepository();
    var service = new AgentRuntimeService(Optional.of(repository), new ObjectMapper(), fixedClock);
    var failedRun = service.markFailed(
            "run-failed",
            "demo",
            ModelRole.DEVELOPER,
            "coding-agent",
            "u-1",
            "deepseek",
            "deepseek-chat",
            "生成交接文档",
            "工具执行超时",
            true,
            null,
            null
    );

    var plan = service.recoveryPlan("demo", failedRun.id());
    var retryRun = service.queueRetry("demo", failedRun.id(), "u-2");

    assertThat(plan.canRetry()).isTrue();
    assertThat(retryRun.status()).isEqualTo(AgentRunStatus.QUEUED);
    assertThat(retryRun.retryOfRunId()).isEqualTo(failedRun.id());
    assertThat(repository.events).extracting(AgentRunEventRecord::eventType)
            .contains("RUN_RETRY_REQUESTED", "RUN_RETRY_QUEUED");
}
```

- [x] **步骤 2：运行测试验证失败**

运行：`/Users/Masons/Ai/Maven/bin/mvn -Dmaven.repo.local=/Users/Masons/Ai/Maven_Ai_Store -q -pl server -Dtest=AgentRuntimeServiceTest test`

预期：FAIL，原因是 `recoveryPlan(...)`、`queueRetry(...)` 或 `AgentRunRecoveryPlan` 尚不存在。

- [x] **步骤 3：编写最少实现代码**

新增 `AgentRunRecoveryPlan`、`findRun(...)` 接口和服务层方法。`queueRetry(...)` 只复制来源运行的角色、供应商、模型、目标和失败摘要，生成新 `QUEUED` 运行。

- [x] **步骤 4：运行测试验证通过**

运行：`/Users/Masons/Ai/Maven/bin/mvn -Dmaven.repo.local=/Users/Masons/Ai/Maven_Ai_Store -q -pl server -Dtest=AgentRuntimeServiceTest test`

预期：PASS。

## 任务 2：后端仓储与 HTTP 入口

**文件：**
- 修改：`server/src/test/java/com/matrixcode/persistence/MybatisPlusAgentRuntimeRepositoryTest.java`
- 修改：`server/src/test/java/com/matrixcode/agentruntime/AgentRuntimeControllerTest.java`
- 修改：`server/src/main/java/com/matrixcode/persistence/application/MybatisPlusAgentRuntimeRepository.java`
- 修改：`server/src/main/java/com/matrixcode/agentruntime/api/AgentRuntimeController.java`

- [x] **步骤 1：编写失败测试**

```java
@Test
void 按运行编号可从正式仓储读回运行() {
    var saved = repository.saveRun(new AgentRunRecord(...));
    assertThat(repository.findRun(saved.id())).contains(saved);
}
```

```java
@Test
void 重试接口会为可重试失败运行创建排队运行() throws Exception {
    mockMvc.perform(post("/api/projects/demo/agent-runs/run-failed/retry")
            .param("actorUserId", "u-2"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.status").value("QUEUED"))
            .andExpect(jsonPath("$.retryOfRunId").value("run-failed"));
}
```

- [x] **步骤 2：运行测试验证失败**

运行：`/Users/Masons/Ai/Maven/bin/mvn -Dmaven.repo.local=/Users/Masons/Ai/Maven_Ai_Store -q -pl server -Dtest=MybatisPlusAgentRuntimeRepositoryTest,AgentRuntimeControllerTest test`

预期：FAIL，原因是仓储和 Controller 入口未实现。

- [x] **步骤 3：编写最少实现代码**

`MybatisPlusAgentRuntimeRepository.findRun(...)` 使用 `runMapper.selectById(runId)`；Controller 增加 `GET /recovery-plan` 和 `POST /retry`，不可重试时返回 `409 CONFLICT`。

- [x] **步骤 4：运行测试验证通过**

运行：`/Users/Masons/Ai/Maven/bin/mvn -Dmaven.repo.local=/Users/Masons/Ai/Maven_Ai_Store -q -pl server -Dtest=MybatisPlusAgentRuntimeRepositoryTest,AgentRuntimeControllerTest test`

预期：PASS。

## 任务 3：桌面端运行中心入口

**文件：**
- 修改：`desktop/src/api/client.test.ts`
- 修改：`desktop/src/test/App.test.tsx`
- 修改：`desktop/src/api/client.ts`
- 修改：`desktop/src/App.tsx`
- 修改：`desktop/src/components/InspectorPanel.tsx`

- [x] **步骤 1：编写失败测试**

```ts
it('重试 Agent 运行时调用项目运行恢复地址', async () => {
  fetchMock.mockResolvedValueOnce(jsonResponse({ id: 'run-retry', status: 'QUEUED' }));
  await retryAgentRun('demo', 'run-failed', 'u-2', 'http://localhost:8080');
  expect(fetchMock).toHaveBeenCalledWith(
    'http://localhost:8080/api/projects/demo/agent-runs/run-failed/retry?actorUserId=u-2',
    expect.objectContaining({ method: 'POST' })
  );
});
```

```ts
it('失败且可重试的 Agent 运行可以在运行中心排队重试', async () => {
  render(<App />);
  await user.click(await screen.findByRole('button', { name: '运行' }));
  await user.click(await screen.findByRole('button', { name: '重试运行' }));
  expect(fetch).toHaveBeenCalledWith(
    expect.stringContaining('/api/projects/demo/agent-runs/run-failed/retry'),
    expect.objectContaining({ method: 'POST' })
  );
});
```

- [x] **步骤 2：运行测试验证失败**

运行：`npm --prefix desktop test -- src/api/client.test.ts src/test/App.test.tsx`

预期：FAIL，原因是 `retryAgentRun(...)` 和按钮尚不存在。

- [x] **步骤 3：编写最少实现代码**

前端 API 增加 `retryAgentRun(...)`；`App.tsx` 传入重试处理器；`InspectorPanel.tsx` 在选中运行失败且 `retryable=true` 时展示按钮。

- [x] **步骤 4：运行测试验证通过**

运行：`npm --prefix desktop test -- src/api/client.test.ts src/test/App.test.tsx`

预期：PASS。

## 任务 4：阶段验证、第二大脑与提交

**文件：**
- 修改：`/Users/Masons/Documents/Obsidian/Aevis/MatrixCode/1 项目首页.md`
- 修改：`/Users/Masons/Documents/Obsidian/Aevis/MatrixCode/3 阶段索引.md`
- 修改：`/Users/Masons/Documents/Obsidian/Aevis/MatrixCode/4 模块地图.md`
- 修改：`/Users/Masons/Documents/Obsidian/Aevis/MatrixCode/6 验证与风险.md`
- 创建：`/Users/Masons/Documents/Obsidian/Aevis/MatrixCode/阶段成果/67 Agent Runtime 恢复重试队列.md`

- [x] **步骤 1：运行完整验证**

运行：

```bash
npm --prefix desktop test
npm --prefix desktop run build
/Users/Masons/Ai/Maven/bin/mvn -Dmaven.repo.local=/Users/Masons/Ai/Maven_Ai_Store -q -pl server test
/Users/Masons/Ai/Maven/bin/mvn -Dmaven.repo.local=/Users/Masons/Ai/Maven_Ai_Store -q -pl server -Dtest=RealRuntimeIntegrationTest test
curl -s http://127.0.0.1:8080/api/projects/demo/runtime-diagnostics
git diff --check
```

预期：测试、构建和静态检查通过；运行诊断返回 `status: "PASS"`。

- [x] **步骤 2：更新第二大脑**

记录第 67 阶段目标、改动、验证证据、回溯结论和下一阶段建议。

- [x] **步骤 3：提交并推送**

```bash
git add .
git add /Users/Masons/Documents/Obsidian/Aevis/MatrixCode
git commit -m "feat(AgentRuntime): 增加失败运行恢复重试队列"
git push origin HEAD:master
```

预期：远程 `master` 包含第 67 阶段代码和第二大脑更新。

## 自检

- 规格覆盖度：恢复计划、重试排队、审计事件、桌面端按钮、验证和第二大脑记录均有对应任务。
- 占位符扫描：计划未使用「待定」「TODO」「后续实现」作为执行占位。
- 类型一致性：`AgentRunRecoveryPlan`、`recoveryPlan(...)`、`queueRetry(...)`、`retryAgentRun(...)` 名称在测试与实现中保持一致。
