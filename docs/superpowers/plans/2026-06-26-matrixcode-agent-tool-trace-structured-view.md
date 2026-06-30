# MatrixCode Agent 工具 trace 结构化展示实现计划

> **面向 AI 代理的工作者：** 必需子技能：使用 superpowers:subagent-driven-development（推荐）或 superpowers:executing-plans 逐任务实现此计划。步骤使用复选框（`- [ ]`）语法来跟踪进度。

**目标：** 在桌面端运行中心把 Agent `TOOL_TRACE` JSON payload 展示为结构化工具调用摘要。

**架构：** 复用现有 Agent Runtime API 和 `matrixcode_agent_run_events`。桌面端 `InspectorPanel` 在渲染运行时间线时解析低敏 JSON payload，解析成功显示工具、动作、状态、引用 ID 和摘要，解析失败回退原文。

**技术栈：** React、TypeScript、Vitest、Java 21、Spring Boot、MyBatis-Plus、Flyway。

---

## 文件结构

- 修改：`desktop/src/test/App.test.tsx`，增加结构化工具 trace 展示断言。
- 修改：`desktop/src/components/InspectorPanel.tsx`，新增工具 trace payload 解析和结构化渲染。
- 新增：`docs/superpowers/specs/2026-06-26-matrixcode-agent-tool-trace-structured-view-design.md`。
- 新增：`docs/superpowers/plans/2026-06-26-matrixcode-agent-tool-trace-structured-view.md`。
- 新增：`/Users/Masons/Documents/Obsidian/Aevis/MatrixCode/阶段成果/57 Agent 工具 trace 结构化展示.md`。

### 任务 1：桌面端结构化展示工具 trace

- [x] **步骤 1：编写失败的 UI 测试**

在 `desktop/src/test/App.test.tsx` 的 `Agent运行事件` fixture 中把 `TOOL_TRACE` payload 扩展为：

```ts
eventPayload:
  '{"toolName":"local-execution.commands","action":"submit-test-command","status":"APPROVAL_PENDING","referenceId":"task-1","summary":"测试命令已提交审批"}'
```

在运行中心断言：

```ts
expect(Agent运行时间线.getByText(/工具 local-execution.commands/)).toBeTruthy();
expect(Agent运行时间线.getByText(/动作 submit-test-command/)).toBeTruthy();
expect(Agent运行时间线.getByText(/状态 APPROVAL_PENDING/)).toBeTruthy();
expect(Agent运行时间线.getByText(/引用 task-1/)).toBeTruthy();
expect(Agent运行时间线.getByText(/摘要 测试命令已提交审批/)).toBeTruthy();
```

- [x] **步骤 2：运行测试验证失败**

```bash
npm --prefix desktop test -- --run src/test/App.test.tsx
```

预期：测试失败，运行中心找不到结构化工具 trace 文案。

实际红灯：`App.test.tsx` 45 passed / 1 failed，运行中心仍展示原始 JSON，找不到 `工具 local-execution.commands`。

- [x] **步骤 3：实现最少前端代码**

在 `InspectorPanel.tsx` 中新增：

```ts
type ToolTracePayload = {
  toolName?: string;
  action?: string;
  status?: string;
  referenceId?: string;
  summary?: string;
};
```

新增解析函数：

```ts
function parseToolTracePayload(event: AgentRunEventRecord): ToolTracePayload | null {
  if (event.eventType !== 'TOOL_TRACE') {
    return null;
  }
  try {
    const parsed = JSON.parse(event.eventPayload) as ToolTracePayload;
    return typeof parsed === 'object' && parsed !== null ? parsed : null;
  } catch {
    return null;
  }
}
```

运行中心渲染时，解析成功展示工具、动作、状态、引用和摘要；解析失败继续展示 `event.eventPayload`。

- [x] **步骤 4：运行测试验证通过**

```bash
npm --prefix desktop test -- --run src/test/App.test.tsx
```

预期：目标测试通过。

实际绿灯：`App.test.tsx` 46/46 通过。

### 任务 2：回归、第二大脑、提交

- [x] **步骤 1：运行桌面端全量测试和构建**

```bash
npm --prefix desktop test
npm --prefix desktop run build
```

- [x] **步骤 2：运行服务端全量测试**

```bash
/Users/Masons/Ai/Maven/bin/mvn -Dmaven.repo.local=/Users/Masons/Ai/Maven_Ai_Store -q -pl server test
```

- [x] **步骤 3：运行真实集成**

```bash
set -a; source .env.local; set +a
/Users/Masons/Ai/Maven/bin/mvn -Dmaven.repo.local=/Users/Masons/Ai/Maven_Ai_Store -q -pl server -Dmatrixcode.real-runtime-test=true -Dtest=RealRuntimeIntegrationTest test
```

- [x] **步骤 4：静态和安全检查**

```bash
git diff --check
```

同时精确扫描真实 API Key 和数据库密码，确认仓库与 Obsidian 文档无泄漏。

- [x] **步骤 5：更新第二大脑并回溯对齐**

新增第 57 阶段成果，更新首页、总览、阶段索引、模块地图、验证与风险、模型网关专题。

- [x] **步骤 6：提交并推送**

```bash
git add .
git commit -m "feat(Agent运行): 结构化展示工具 trace"
git push origin HEAD:master
```

实际结果：提交 `70a72b4 feat(Agent运行): 结构化展示工具 trace` 已推送到 `origin/master`，远程 `master` 指向 `70a72b4a3c3e9bd2df86af8d2f4fb20704d6ac1a`。

执行证据：

- 桌面端目标测试：`App.test.tsx` 46/46 通过。
- 桌面端全量：`Tests 93 passed`。
- 桌面端构建：`npm --prefix desktop run build` 退出码 0。
- 服务端全量：`files=78 tests=366 failures=0 errors=0 skipped=7`。
- 真实集成：`RealRuntimeIntegrationTest tests=7 failures=0 errors=0 skipped=0`；真实 MySQL `matrix_code` 保持 Flyway `v56.1` 最新。
- 静态检查：`git diff --check` 无输出；精确密钥扫描 `secret_matches=0`。
