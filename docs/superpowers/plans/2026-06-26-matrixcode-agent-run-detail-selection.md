# MatrixCode Agent 运行详情选择实现计划

> **面向 AI 代理的工作者：** 必需子技能：使用 superpowers:subagent-driven-development（推荐）或 superpowers:executing-plans 逐任务实现此计划。步骤使用复选框（`- [ ]`）语法来跟踪进度。

**目标：** 运行中心支持选择历史 Agent 运行，并按选中运行展示审计详情、模型请求关联和工具 trace。

**架构：** 只改桌面端展示层。`OperationsDetailPanel` 持有选中运行 ID，本地过滤已加载的 `agentRuns`、`agentRunEvents` 和 `modelGateway.recentRequests`；`AgentRunEventPayload` 增强 `TOOL_TRACE.metadata` 展示。

**技术栈：** React、TypeScript、Vitest、Testing Library。

---

## 文件结构

- 修改：`desktop/src/components/InspectorPanel.tsx`，新增运行选择状态、运行事件过滤和模型 trace metadata 展示。
- 修改：`desktop/src/App.css`，新增运行选择器样式。
- 修改：`desktop/src/api/client.ts`，将桌面端默认后端地址统一到 Spring Boot 默认 `http://localhost:8080`。
- 修改：`desktop/src/test/App.test.tsx`，增加历史运行和模型请求 trace 测试。
- 修改：`desktop/src/api/client.test.ts`，覆盖默认后端端口约定。
- 修改：`.env.example`，将 `SERVER_PORT` 示例值统一为 `8080`。
- 新增：`docs/superpowers/specs/2026-06-26-matrixcode-agent-run-detail-selection-design.md`。
- 新增：`docs/superpowers/plans/2026-06-26-matrixcode-agent-run-detail-selection.md`。
- 新增：`/Users/Masons/Documents/Obsidian/Aevis/MatrixCode/阶段成果/62 Agent 运行详情选择.md`。

### 任务 1：红灯测试

- [x] **步骤 1：扩展测试 fixture**

在 `App.test.tsx` 中新增 `run-2`、`request-2` 和 `run-2` 的模型请求 `TOOL_TRACE`，并让 `loadAgentRunEvents` 按 runId 返回对应事件。

- [x] **步骤 2：新增运行选择测试**

新增测试：打开运行中心，点击 `run-2`，断言审计详情、事件计数、模型请求关联和模型 trace metadata 都切换到 `run-2`。

- [x] **步骤 3：运行目标测试验证失败**

```bash
npm --prefix desktop test -- --run src/test/App.test.tsx
```

预期：失败于缺少 `Agent 运行选择`，且默认事件未按运行过滤。

实际：目标测试先失败于缺少 `Agent 运行选择`，且默认事件统计未按运行过滤；端口修复另行用客户端测试先红灯验证，默认地址仍为 `18080`。

### 任务 2：前端最小实现

- [x] **步骤 1：解析 trace metadata**

`parseToolTracePayload` 读取 `payload.metadata.providerId`、`modelName`、`cacheSource`、`stablePrefixHash`、`cachePolicyId` 和 `volatileSuffixStrategy`。

- [x] **步骤 2：按选中运行过滤**

`OperationsDetailPanel` 增加 `selectedAgentRunId`，默认回退 `agentRuns[0]`；审计详情、事件计数、事件筛选和模型请求匹配都基于选中运行。

- [x] **步骤 3：新增运行选择器**

在运行中心新增 `Agent 运行选择` 卡片，按钮展示状态、目标、runId、角色和模型。按钮用 `aria-pressed` 表示当前选中项。

- [x] **步骤 4：补样式**

新增 `.metric-card--wide` 和 `.agent-run-selector*`，确保双列弹窗中选择器占满一行，长文本省略，不撑乱布局。

### 任务 3：验证、文档、提交

- [x] **步骤 1：目标测试到绿灯**

```bash
npm --prefix desktop test -- --run src/test/App.test.tsx
```

- [x] **步骤 2：全量验证**

```bash
npm --prefix desktop test
npm --prefix desktop run build
/Users/Masons/Ai/Maven/bin/mvn -Dmaven.repo.local=/Users/Masons/Ai/Maven_Ai_Store -q -pl server test
set -a; source .env.local; set +a
/Users/Masons/Ai/Maven/bin/mvn -Dmaven.repo.local=/Users/Masons/Ai/Maven_Ai_Store -q -pl server -Dmatrixcode.real-runtime-test=true -Dtest=RealRuntimeIntegrationTest test
git diff --check
git diff --cached --check
```

结果：

- `npm --prefix desktop test -- --run src/test/App.test.tsx`：47 passed。
- `npm --prefix desktop test -- --run src/api/client.test.ts`：41 passed。
- `npm --prefix desktop test`：94 passed。
- `npm --prefix desktop run build`：通过。
- `/Users/Masons/Ai/Maven/bin/mvn -Dmaven.repo.local=/Users/Masons/Ai/Maven_Ai_Store -q -pl server test`：退出码 0，`surefire_files=78 tests=369 failures=0 errors=0 skipped=7`。
- 真实集成：`RealRuntimeIntegrationTest` 退出码 0，真实 MySQL `matrix_code` Flyway `v60.1`，无需新增迁移。
- 浏览器核验：`http://127.0.0.1:5173/` 进入主工作台，运行中心可打开，`Agent 运行选择`、审计详情和模型请求线索可见。

- [x] **步骤 3：安全扫描**

扫描 key/password/token/secret 类敏感值，排除 `.env.local`、`.git`、`node_modules`、`target` 和 `dist`，确认 `secret_matches=0`。

- [x] **步骤 4：第二大脑回溯**

更新项目首页、阶段索引、总览、模块地图、验证与风险、模型网关专题和阶段成果页，记录第 62 阶段未偏离多人实时协作智能体控制台主线。

- [x] **步骤 5：提交并推送**

```bash
git add .
git commit -m "feat(运行中心): 支持选择Agent运行详情"
git push origin HEAD:master
```

结果：提交 `71aedd9 feat(运行中心): 支持选择Agent运行详情` 已推送到远程 `master`。
