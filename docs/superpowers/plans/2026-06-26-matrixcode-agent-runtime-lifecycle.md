# MatrixCode Agent Runtime 生命周期事件实现计划

> **面向 AI 代理的工作者：** 必需子技能：使用 superpowers:subagent-driven-development（推荐）或 superpowers:executing-plans 逐任务实现此计划。步骤使用复选框（`- [ ]`）语法来跟踪进度。

**目标：** 为 Agent Runtime 增加运行开始和运行成功的统一生命周期入口，并让编码智能体链路产生可审计时间线。

**架构：** 在 `AgentRuntimeService` 新增 `markRunning(...)` 与 `markSucceeded(...)`，内部复用 `saveRun(...)` 和 `appendEvent(...)`。编码智能体执行准备、Patch 应用、交接回溯只负责传入业务上下文，由 Runtime 服务统一写主记录和生命周期事件。

**技术栈：** Java 21、Spring Boot、JUnit 5、AssertJ、React、Vitest、MySQL/MyBatis-Plus 既有仓储。

---

## 文件职责

- `server/src/main/java/com/matrixcode/agentruntime/application/AgentRuntimeService.java`：新增生命周期方法与注释。
- `server/src/main/java/com/matrixcode/codingagent/application/CodingAgentExecutionService.java`：执行准备改用 `markRunning(...)`。
- `server/src/main/java/com/matrixcode/codingagent/application/CodingAgentPatchService.java`：Patch 成功改用 `markSucceeded(...)`。
- `server/src/main/java/com/matrixcode/codingagent/application/CodingAgentHandoffService.java`：交接成功改用 `markSucceeded(...)`。
- `server/src/test/java/com/matrixcode/agentruntime/AgentRuntimeServiceTest.java`：增加服务层红绿测试。
- `server/src/test/java/com/matrixcode/codingagent/CodingAgentTaskServiceTest.java`：更新编码智能体运行事件顺序断言。
- `desktop/src/test/App.test.tsx`：补充生命周期事件 mock 与展示断言。
- `docs/superpowers/specs/2026-06-26-matrixcode-agent-runtime-lifecycle-design.md`：阶段设计。
- `docs/superpowers/plans/2026-06-26-matrixcode-agent-runtime-lifecycle.md`：阶段计划。
- Obsidian MatrixCode 项目图谱：阶段完成后更新项目首页、索引、模块地图、验证风险和阶段成果页。

## 任务 1：AgentRuntimeService 生命周期红测

**文件：**
- 修改：`server/src/test/java/com/matrixcode/agentruntime/AgentRuntimeServiceTest.java`
- 修改：`server/src/main/java/com/matrixcode/agentruntime/application/AgentRuntimeService.java`

- [x] **步骤 1：编写失败的测试**

在 `AgentRuntimeServiceTest` 增加：

```java
@Test
void 标记运行中会保存主记录并追加开始事件() {
    var repository = new RecordingAgentRuntimeRepository();
    var service = new AgentRuntimeService(Optional.of(repository), new ObjectMapper(), fixedClock);

    var run = service.markRunning(
            "run-1",
            "demo",
            ModelRole.DEVELOPER,
            "coding",
            "user-dev",
            "qwen",
            "qwen-plus",
            "实现登录接口",
            "执行准备已生成"
    );

    assertThat(run.status()).isEqualTo(AgentRunStatus.RUNNING);
    assertThat(run.startedAt()).isEqualTo(fixedClock.instant());
    assertThat(run.finishedAt()).isNull();
    assertThat(repository.savedRuns).containsExactly(run);
    assertThat(repository.events).singleElement().satisfies(event -> {
        assertThat(event.eventType()).isEqualTo("RUN_STARTED");
        assertThat(event.eventTitle()).isEqualTo("运行开始");
        assertThat(event.eventPayload()).contains("\"summary\":\"执行准备已生成\"");
        assertThat(event.eventPayload()).contains("\"providerId\":\"qwen\"");
        assertThat(event.eventPayload()).contains("\"modelName\":\"qwen-plus\"");
        assertThat(event.eventPayload()).contains("\"role\":\"DEVELOPER\"");
        assertThat(event.eventPayload()).contains("\"agentKind\":\"coding\"");
    });
}

@Test
void 标记成功会保存主记录并追加成功事件() {
    var repository = new RecordingAgentRuntimeRepository();
    var service = new AgentRuntimeService(Optional.of(repository), new ObjectMapper(), fixedClock);

    var run = service.markSucceeded(
            "run-1",
            "demo",
            ModelRole.DEVELOPER,
            "coding",
            "user-dev",
            "deepseek",
            "deepseek-chat",
            "实现登录接口",
            "编码交付已完成"
    );

    assertThat(run.status()).isEqualTo(AgentRunStatus.SUCCEEDED);
    assertThat(run.startedAt()).isEqualTo(fixedClock.instant());
    assertThat(run.finishedAt()).isEqualTo(fixedClock.instant());
    assertThat(repository.savedRuns).containsExactly(run);
    assertThat(repository.events).singleElement().satisfies(event -> {
        assertThat(event.eventType()).isEqualTo("RUN_SUCCEEDED");
        assertThat(event.eventTitle()).isEqualTo("运行成功");
        assertThat(event.eventPayload()).contains("\"summary\":\"编码交付已完成\"");
        assertThat(event.eventPayload()).contains("\"providerId\":\"deepseek\"");
        assertThat(event.eventPayload()).contains("\"modelName\":\"deepseek-chat\"");
    });
}
```

- [x] **步骤 2：运行测试验证失败**

运行：

```bash
/Users/Masons/Ai/Maven/bin/mvn -Dmaven.repo.local=/Users/Masons/Ai/Maven_Ai_Store -q -pl server -Dtest=AgentRuntimeServiceTest test
```

预期：编译失败，提示 `cannot find symbol`，因为 `markRunning` 和 `markSucceeded` 尚未实现。

- [x] **步骤 3：编写最少实现代码**

在 `AgentRuntimeService` 添加两个 public 方法：

```java
public AgentRunRecord markRunning(...) {
    var run = saveRun(... AgentRunStatus.RUNNING ...);
    appendEvent(run.id(), run.projectId(), "RUN_STARTED", "运行开始", Map.of(...));
    return run;
}

public AgentRunRecord markSucceeded(...) {
    var run = saveRun(... AgentRunStatus.SUCCEEDED ...);
    appendEvent(run.id(), run.projectId(), "RUN_SUCCEEDED", "运行成功", Map.of(...));
    return run;
}
```

- [x] **步骤 4：运行测试验证通过**

运行：

```bash
/Users/Masons/Ai/Maven/bin/mvn -Dmaven.repo.local=/Users/Masons/Ai/Maven_Ai_Store -q -pl server -Dtest=AgentRuntimeServiceTest test
```

预期：`AgentRuntimeServiceTest` 通过。

## 任务 2：编码智能体链路接入生命周期入口

**文件：**
- 修改：`server/src/test/java/com/matrixcode/codingagent/CodingAgentTaskServiceTest.java`
- 修改：`server/src/main/java/com/matrixcode/codingagent/application/CodingAgentExecutionService.java`
- 修改：`server/src/main/java/com/matrixcode/codingagent/application/CodingAgentPatchService.java`
- 修改：`server/src/main/java/com/matrixcode/codingagent/application/CodingAgentHandoffService.java`

- [x] **步骤 1：编写失败的测试**

更新事件顺序断言：

```java
.containsExactly("TASK_PLANNED", "RUN_STARTED", "EXECUTION_PREPARED", "TOOL_TRACE", "TOOL_TRACE");
```

```java
.containsExactly("RUN_SUCCEEDED", "PATCH_APPLIED", "TOOL_TRACE");
```

```java
.containsExactly("RUN_SUCCEEDED", "HANDOFF_RECORDED", "TOOL_TRACE");
```

- [x] **步骤 2：运行测试验证失败**

运行：

```bash
/Users/Masons/Ai/Maven/bin/mvn -Dmaven.repo.local=/Users/Masons/Ai/Maven_Ai_Store -q -pl server -Dtest=AgentRuntimeServiceTest,CodingAgentTaskServiceTest test
```

预期：`CodingAgentTaskServiceTest` 事件顺序断言失败，因为生产代码尚未写生命周期事件。

- [x] **步骤 3：编写最少实现代码**

把编码智能体服务中的 `agentRuntimeService.saveRun(... RUNNING ...)` 改为 `markRunning(...)`，把 `saveRun(... SUCCEEDED ...)` 改为 `markSucceeded(...)`，其他业务事件和工具 trace 保持原顺序。

- [x] **步骤 4：运行测试验证通过**

运行：

```bash
/Users/Masons/Ai/Maven/bin/mvn -Dmaven.repo.local=/Users/Masons/Ai/Maven_Ai_Store -q -pl server -Dtest=AgentRuntimeServiceTest,CodingAgentTaskServiceTest test
```

预期：两个测试类通过。

## 任务 3：前端运行中心生命周期展示回归

**文件：**
- 修改：`desktop/src/test/App.test.tsx`

- [x] **步骤 1：编写失败的测试**

在 `Agent运行事件Run1` mock 中加入 `RUN_STARTED`，在 `Agent运行事件Run2` mock 中加入 `RUN_SUCCEEDED`，并在运行中心测试中断言：

```ts
expect(screen.getByText('运行开始')).toBeInTheDocument();
expect(screen.getByText('运行成功')).toBeInTheDocument();
```

- [x] **步骤 2：运行测试验证失败或确认当前 UI 已支持**

运行：

```bash
npm --prefix desktop test -- --run src/test/App.test.tsx
```

预期：如果 UI 已能显示通用事件标题，则测试通过；如果事件数量断言仍按旧值，会失败并提示事件计数不匹配。

- [x] **步骤 3：编写最少实现代码或调整测试夹具**

若 UI 已支持通用事件标题，只调整 mock 事件数量断言；若 UI 未显示通用标题，最小修改 `InspectorPanel` 的时间线渲染，让非 `TOOL_TRACE` 事件继续显示 `eventTitle`。

- [x] **步骤 4：运行测试验证通过**

运行：

```bash
npm --prefix desktop test -- --run src/test/App.test.tsx
```

预期：`App.test.tsx` 通过。

## 任务 4：阶段验证、第二大脑、提交推送

**文件：**
- 修改：Obsidian `MatrixCode` 项目图谱。

- [x] **步骤 1：运行完整验证**

运行：

```bash
npm --prefix desktop test
npm --prefix desktop run build
/Users/Masons/Ai/Maven/bin/mvn -Dmaven.repo.local=/Users/Masons/Ai/Maven_Ai_Store -q -pl server test
set -a; source .env.local; set +a; /Users/Masons/Ai/Maven/bin/mvn -Dmaven.repo.local=/Users/Masons/Ai/Maven_Ai_Store -q -pl server -Dmatrixcode.real-runtime-test=true -Dtest=RealRuntimeIntegrationTest test
git diff --check
```

结果：桌面端全量、桌面端构建、服务端全量、迁移回归、真实 MySQL `v66.1` 迁移、真实 API 事件顺序验证、运行诊断和真实 `RealRuntimeIntegrationTest` 均通过。Milvus 曾短暂拒绝连接，复测后历史内网地址已连通，真实集成通过。

- [x] **步骤 2：浏览器抽查**

重启后端与前端服务后，打开 `http://127.0.0.1:5173/`，确认右侧运行中心能展示生命周期事件标题，且布局无新增拥挤。实测运行中心展示“运行开始”“执行准备”“工具调用 trace”，浏览器控制台无 error。

- [x] **步骤 3：敏感信息扫描**

运行仓库与 Obsidian 扫描，确认真实 API Key、数据库密码未写入版本库或第二大脑。

- [x] **步骤 4：更新 Obsidian 项目图谱**

新增 `阶段成果/66 Agent Runtime 运行状态流转与自动恢复策略.md`，并更新首页、阶段索引、模块地图、验证风险和模型网关上下文页。

- [x] **步骤 5：提交并推送**

运行：

```bash
git add .
git commit -m "feat(AgentRuntime): 增加运行生命周期事件"
git push origin HEAD:master
```

结果：提交 `0d39b39` 已推送到远程 `master`。
