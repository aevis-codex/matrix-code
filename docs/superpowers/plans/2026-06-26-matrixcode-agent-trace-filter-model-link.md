# MatrixCode Agent trace 筛选与模型请求线索实现计划

> **面向 AI 代理的工作者：** 必需子技能：使用 superpowers:subagent-driven-development（推荐）或 superpowers:executing-plans 逐任务实现此计划。步骤使用复选框（`- [ ]`）语法来跟踪进度。

**目标：** 在运行中心为 Agent 运行时间线增加事件筛选，并展示当前项目最近模型请求线索。

**架构：** 复用桌面端已经加载的 `agentRunEvents` 和 `modelGateway.recentRequests`。筛选状态仅保存在 `OperationsDetailPanel` 本地，不新增后端 API、不新增数据库迁移。

**技术栈：** React、TypeScript、Vitest、Java 21、Spring Boot。

---

## 文件结构

- 修改：`desktop/src/components/InspectorPanel.tsx`，增加本地筛选状态、筛选按钮、过滤事件列表和最近模型请求线索。
- 修改：`desktop/src/test/App.test.tsx`，增加运行中心筛选和模型请求线索断言。
- 新增：`docs/superpowers/specs/2026-06-26-matrixcode-agent-trace-filter-model-link-design.md`。
- 新增：`docs/superpowers/plans/2026-06-26-matrixcode-agent-trace-filter-model-link.md`。
- 新增：`/Users/Masons/Documents/Obsidian/Aevis/MatrixCode/阶段成果/58 Agent trace 筛选与模型请求线索.md`。

### 任务 1：运行中心 Agent 事件筛选

- [x] **步骤 1：编写失败的 UI 测试**

在 `App.test.tsx` 的 Agent 运行事件测试中加入：

```ts
expect(Agent运行时间线.getByText('事件 2/2')).toBeTruthy();
expect(Agent运行时间线.getByText('模型请求线索 request-1 · matrixcode-local-product')).toBeTruthy();

const Agent事件筛选 = within(运行中心.getByLabelText('Agent 事件筛选'));
fireEvent.click(Agent事件筛选.getByRole('button', { name: '工具' }));
expect(Agent运行时间线.getByText('事件 1/2')).toBeTruthy();
expect(Agent运行时间线.queryByText(/运行失败/)).toBeNull();
expect(Agent运行时间线.getByText(/工具 local-execution.commands/)).toBeTruthy();

fireEvent.click(Agent事件筛选.getByRole('button', { name: '失败' }));
expect(Agent运行时间线.getByText('事件 1/2')).toBeTruthy();
expect(Agent运行时间线.getByText(/运行失败/)).toBeTruthy();
expect(Agent运行时间线.queryByText(/工具 local-execution.commands/)).toBeNull();
```

- [x] **步骤 2：运行测试验证失败**

```bash
npm --prefix desktop test -- --run src/test/App.test.tsx
```

预期：找不到 `Agent 事件筛选` 或 `模型请求线索`。

- [x] **步骤 3：实现最少前端代码**

在 `InspectorPanel.tsx`：

- 引入 `useState`。
- 定义 `type AgentEventFilter = 'all' | 'tool' | 'failure';`。
- 在 `OperationsDetailPanel` 中维护 `agentEventFilter`。
- 过滤 `agentRunEvents`：
  - `all`：全部。
  - `tool`：`event.eventType === 'TOOL_TRACE'`。
  - `failure`：`event.eventType.includes('FAILED')`。
- 在 Agent 运行时间线卡片中新增筛选按钮、事件数量和最近模型请求线索。

- [x] **步骤 4：运行测试验证通过**

```bash
npm --prefix desktop test -- --run src/test/App.test.tsx
```

预期：目标测试通过。

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

同时精确扫描真实 API Key 和数据库密码。

- [x] **步骤 5：更新第二大脑并回溯对齐**

新增第 58 阶段成果，更新首页、总览、阶段索引、模块地图、验证与风险、模型网关专题。

- [x] **步骤 6：提交并推送**

```bash
git add .
git commit -m "feat(Agent运行): 增加 trace 筛选"
git push origin HEAD:master
```

远程 `master` 提交：`32a2e5923666bf16e0a467389b42d3509ceaaea9`。
