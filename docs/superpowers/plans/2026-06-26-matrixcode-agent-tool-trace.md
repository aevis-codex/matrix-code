# MatrixCode Agent 工具调用 trace 实现计划

> **面向 AI 代理的工作者：** 必需子技能：使用 superpowers:subagent-driven-development（推荐）或 superpowers:executing-plans 逐任务实现此计划。步骤使用复选框（`- [x]`）语法来跟踪进度。

**目标：** 统一 Agent 工具调用 trace 格式，让编码智能体的测试命令、Git diff、文件写入和交接文档动作可结构化审计。

**架构：** `AgentRuntimeService.appendToolTrace(...)` 复用现有 `matrixcode_agent_run_events` 写 `TOOL_TRACE` 事件；编码智能体业务服务在实际工具动作完成后调用该入口；桌面端继续通过运行中心事件时间线展示。

**技术栈：** Java 21、Spring Boot、MyBatis-Plus、JUnit 5、React、TypeScript、Vitest。

---

## 文件结构

- 修改：`server/src/main/java/com/matrixcode/agentruntime/application/AgentRuntimeService.java`，新增 `appendToolTrace(...)` 和短摘要归一化。
- 修改：`server/src/main/java/com/matrixcode/codingagent/application/CodingAgentExecutionService.java`，执行准备后记录测试命令和 Git diff trace。
- 修改：`server/src/main/java/com/matrixcode/codingagent/application/CodingAgentPatchService.java`，Patch 成功后记录文件写入 trace。
- 修改：`server/src/main/java/com/matrixcode/codingagent/application/CodingAgentHandoffService.java`，交接文档创建后记录文档 trace。
- 修改：`server/src/test/java/com/matrixcode/agentruntime/AgentRuntimeServiceTest.java`，覆盖工具 trace payload。
- 修改：`server/src/test/java/com/matrixcode/codingagent/CodingAgentTaskServiceTest.java`，覆盖编码智能体工具 trace。
- 修改：`desktop/src/test/App.test.tsx`，覆盖运行中心展示工具 trace。
- 新增：`/Users/Masons/Documents/Obsidian/Aevis/MatrixCode/阶段成果/54 Agent 工具调用 trace.md`，更新项目图谱。

### 任务 1：Agent Runtime 统一工具 trace 入口

- [x] **步骤 1：编写失败的服务测试**

在 `AgentRuntimeServiceTest` 新增：

```java
@Test
void 追加工具Trace会写入统一事件格式() {
    var repository = new RecordingAgentRuntimeRepository();
    var service = new AgentRuntimeService(Optional.of(repository), new ObjectMapper(), fixedClock);

    var event = service.appendToolTrace(
            "run-1",
            "demo",
            "local-execution.commands",
            "submit-test-command",
            "APPROVAL_PENDING",
            "task-1",
            "测试命令已提交审批",
            java.util.Map.of("workspaceId", "workspace-main")
    );

    assertThat(event.eventType()).isEqualTo("TOOL_TRACE");
    assertThat(event.eventTitle()).isEqualTo("工具调用 trace");
    assertThat(event.eventPayload()).contains("\"toolName\":\"local-execution.commands\"");
    assertThat(event.eventPayload()).contains("\"action\":\"submit-test-command\"");
    assertThat(event.eventPayload()).contains("\"referenceId\":\"task-1\"");
    assertThat(event.eventPayload()).contains("\"workspaceId\":\"workspace-main\"");
}
```

- [x] **步骤 2：运行测试验证失败**

```bash
/Users/Masons/Ai/Maven/bin/mvn -Dmaven.repo.local=/Users/Masons/Ai/Maven_Ai_Store -q -pl server -Dtest=AgentRuntimeServiceTest test
```

预期：编译失败，`appendToolTrace(...)` 尚不存在。

实际：编译失败，错误集中在 `AgentRuntimeService` 找不到 `appendToolTrace(...)`。

- [x] **步骤 3：实现最少服务代码**

新增 `appendToolTrace(...)`，内部调用 `appendEvent(...)`：

```java
return appendEvent(runId, projectId, "TOOL_TRACE", "工具调用 trace", Map.of(
        "toolName", requireText(toolName, "工具名称不能为空"),
        "action", requireText(action, "工具动作不能为空"),
        "status", requireText(status, "工具状态不能为空"),
        "referenceId", trimToEmpty(referenceId),
        "summary", failureSummaryOrDefault(summary),
        "metadata", metadata == null ? Map.of() : metadata
));
```

- [x] **步骤 4：运行服务测试验证通过**

运行步骤 2 命令，预期退出码 0。

实际：退出码 0。

### 任务 2：编码智能体业务写入工具 trace

- [x] **步骤 1：编写失败的编码智能体测试**

更新 `CodingAgentTaskServiceTest`：

执行准备测试断言事件类型：

```java
assertThat(repository.events)
        .extracting(AgentRunEventRecord::eventType)
        .containsExactly("TASK_PLANNED", "EXECUTION_PREPARED", "TOOL_TRACE", "TOOL_TRACE");
assertThat(repository.events.get(2).eventPayload()).contains("\"toolName\":\"local-execution.commands\"");
assertThat(repository.events.get(3).eventPayload()).contains("\"toolName\":\"local-execution.git-diff\"");
```

Patch 测试断言：

```java
assertThat(repository.events)
        .extracting(AgentRunEventRecord::eventType)
        .containsExactly("PATCH_APPLIED", "TOOL_TRACE");
```

Handoff 测试断言：

```java
assertThat(repository.events)
        .extracting(AgentRunEventRecord::eventType)
        .containsExactly("HANDOFF_RECORDED", "TOOL_TRACE");
```

- [x] **步骤 2：运行测试验证失败**

```bash
/Users/Masons/Ai/Maven/bin/mvn -Dmaven.repo.local=/Users/Masons/Ai/Maven_Ai_Store -q -pl server -Dtest=CodingAgentTaskServiceTest test
```

预期：事件序列缺少 `TOOL_TRACE`。

实际：3 条测试失败，执行准备、Patch 和 Handoff 事件序列均缺少 `TOOL_TRACE`。

- [x] **步骤 3：实现最少业务代码**

在执行准备后追加两条 trace：

- `local-execution.commands / submit-test-command`
- `local-execution.git-diff / capture-baseline`

在 Patch 后追加：

- `local-execution.files.write / apply-patch`

在 Handoff 后追加：

- `document.center / create-coding-agent-handoff`

- [x] **步骤 4：运行编码智能体测试验证通过**

运行步骤 2 命令，预期退出码 0。

实际：退出码 0。

### 任务 3：桌面端运行中心展示工具 trace

- [x] **步骤 1：编写失败的 UI 测试**

在 `desktop/src/test/App.test.tsx` 的 `Agent运行事件` fixture 中增加一条 `TOOL_TRACE`，并在运行中心断言：

```ts
expect(Agent运行时间线.getByText(/工具调用 trace/)).toBeTruthy();
expect(Agent运行时间线.getByText(/local-execution.commands/)).toBeTruthy();
```

- [x] **步骤 2：运行测试验证失败**

```bash
npm --prefix desktop test -- --run src/test/App.test.tsx
```

预期：如果现有运行中心直接展示事件，则该测试可能立即通过；若立即通过，说明前端无需生产代码改动，只需记录为验证结果。

实际：目标测试直接通过，运行中心已能展示 `TOOL_TRACE` 标题和 payload。

- [x] **步骤 3：实现最少前端代码**

只有当步骤 2 失败时才改 `InspectorPanel`。优先保持现有事件时间线，不增加新 UI 区块。

实际：无需修改前端生产代码。

- [x] **步骤 4：运行 UI 测试验证通过**

运行步骤 2 命令，预期退出码 0。

实际：`src/test/App.test.tsx` 46 条测试通过。

### 任务 4：回归、第二大脑、提交

- [x] **步骤 1：运行服务端关联回归**

```bash
/Users/Masons/Ai/Maven/bin/mvn -Dmaven.repo.local=/Users/Masons/Ai/Maven_Ai_Store -q -pl server -Dtest=AgentRuntimeServiceTest,CodingAgentTaskServiceTest,DatabaseMigrationCommentPolicyTest,DatabaseMigrationServiceTest test
```

实际：退出码 0。

- [x] **步骤 2：运行服务端全量测试**

```bash
/Users/Masons/Ai/Maven/bin/mvn -Dmaven.repo.local=/Users/Masons/Ai/Maven_Ai_Store -q -pl server test
```

实际：退出码 0，Surefire 统计 `files=78 tests=363 failures=0 errors=0 skipped=7`。

- [x] **步骤 3：运行桌面端全量测试和构建**

```bash
npm --prefix desktop test
npm --prefix desktop run build
```

实际：桌面端全量 `Tests 93 passed`，Vite 构建通过。

- [x] **步骤 4：运行真实集成**

```bash
set -a; source .env.local; set +a
/Users/Masons/Ai/Maven/bin/mvn -Dmaven.repo.local=/Users/Masons/Ai/Maven_Ai_Store -q -pl server -Dmatrixcode.real-runtime-test=true -Dtest=RealRuntimeIntegrationTest test
```

实际：退出码 0，`RealRuntimeIntegrationTest tests=7 failures=0 errors=0 skipped=0`；真实 MySQL `matrix_code` 当前 Flyway `v53.1`，本阶段无新增 DDL。

- [x] **步骤 5：静态和安全检查**

```bash
git diff --check
```

同时精确扫描真实 API Key 和数据库密码字面值，确认仓库与 Obsidian 文档无泄漏。

实际：`git diff --check` 无输出；精确扫描真实 API Key 和数据库密码字面值无命中。

- [x] **步骤 6：更新第二大脑并回溯对齐**

新增第 54 阶段成果，更新首页、总览、阶段索引、模块地图、验证与风险、模型网关专题，记录本阶段对第 53 阶段失败恢复和最初 Agent 控制台需求的回溯结论。

实际：已新增 `54 Agent 工具调用 trace.md`，并更新项目首页、项目总览、阶段索引、模块地图、验证与风险、模型网关与上下文门禁、状态持久化与数据库迁移。

- [x] **步骤 7：提交并推送**

```bash
git add .
git commit -m "feat(Agent运行): 增加工具调用 trace"
git push origin HEAD:master
```

## 2026-06-26 计划状态回填

- 本次回溯确认：该计划对应能力已在后续阶段完成，并已进入 Obsidian 阶段成果、验证与风险、模块地图和真实验证记录。
- 原未勾选项属于历史计划状态未回填，不代表当前功能缺失；已统一回填为完成，后续验收以对应阶段成果页和最新验证命令为准。
