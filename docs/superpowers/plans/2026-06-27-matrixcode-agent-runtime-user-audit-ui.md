# MatrixCode Agent Runtime 用户责任审计 UI 实现计划

> **面向 AI 代理的工作者：** 必需子技能：使用 superpowers:subagent-driven-development（推荐）或 superpowers:executing-plans 逐任务实现此计划。步骤使用复选框（`- [ ]`）语法来跟踪进度。

**目标：** 在桌面端运行中心展示当前操作者的 Agent Runtime 用户责任审计报告。

**架构：** 桌面端 API client 新增用户责任审计类型和加载函数；`App` 在工作台刷新时并行加载当前操作者审计报告；`OperationsDetailPanel` 在运行中心弹窗展示低敏责任统计和责任条目。

**技术栈：** React 19、TypeScript 6、Vite、Vitest、Testing Library、既有 MatrixCode 桌面端 API client。

---

## 文件结构

- 修改：`desktop/src/api/client.ts`，新增 `AgentRunUserAuditEntry`、`AgentRunUserAuditReport` 和 `loadAgentRunUserAudit(...)`。
- 修改：`desktop/src/api/client.test.ts`，覆盖用户责任审计 API 路径。
- 修改：`desktop/src/App.tsx`，把当前操作者的审计报告纳入工作台状态。
- 修改：`desktop/src/components/InspectorPanel.tsx`，运行中心展示用户责任审计卡片。
- 修改：`desktop/src/test/App.test.tsx`，覆盖展示和降级行为。
- 修改：`desktop/src/App.css`，补充责任审计列表的紧凑样式。
- 修改：Obsidian `MatrixCode` 项目图谱，记录第 74 阶段成果和回溯结论。

## 任务 1：API client 类型和加载函数

- [x] **步骤 1：编写失败测试**

在 `desktop/src/api/client.test.ts` 新增测试：

```ts
it('按用户读取 Agent Runtime 责任审计报告', async () => {
  const fetchMock = vi.fn().mockResolvedValue({
    ok: true,
    json: async () => ({ projectId: 'demo', userId: 'user-dev', totalRuns: 1, activeResponsibilities: 1, modelRequestCount: 1, entries: [] })
  });
  vi.stubGlobal('fetch', fetchMock);

  await loadAgentRunUserAudit('demo', 'user-dev', 30, 'http://localhost:8080');

  expect(fetchMock).toHaveBeenCalledWith(
    'http://localhost:8080/api/projects/demo/agent-runs/user-audit?userId=user-dev&limit=30',
    { headers: { Accept: 'application/json' } }
  );
});
```

- [x] **步骤 2：运行测试验证失败**

```bash
npm --prefix desktop test -- client.test.ts
```

结果：FAIL，`loadAgentRunUserAudit is not a function`，符合红灯预期。

- [x] **步骤 3：实现最少 API 代码**

在 `client.ts` 增加类型和函数，请求路径使用既有 `projectUrl(...)`。

- [x] **步骤 4：运行测试通过**

```bash
npm --prefix desktop test -- client.test.ts
```

结果：PASS，`client.test.ts` 47 条通过。

## 任务 2：运行中心展示责任审计

- [x] **步骤 1：编写失败测试**

在 `desktop/src/test/App.test.tsx` 新增测试：

```ts
it('运行中心展示当前用户 Agent 责任审计', async () => {
  render(<App />);

  const 运行中心 = await 打开运行中心();
  const 用户责任审计 = within(运行中心.getByLabelText('用户责任审计'));

  expect(用户责任审计.getByText('当前用户 user-product')).toBeTruthy();
  expect(用户责任审计.getByText('运行 1 · 活跃 1 · 模型请求 1')).toBeTruthy();
  expect(用户责任审计.getByText('运行 run-1 · 认领 Worker · 失败')).toBeTruthy();
});
```

- [x] **步骤 2：运行测试验证失败**

```bash
npm --prefix desktop test -- App.test.tsx
```

结果：FAIL，`loadAgentRunUserAudit` 未被调用，符合红灯预期。

- [x] **步骤 3：实现状态、加载和展示**

`App` 状态新增 `agentRunUserAudit`。`refreshWorkbench` 在拿到工作台后使用当前操作者调用 `loadAgentRunUserAudit(...)`，失败时返回空报告。`OperationsDetailPanel` 新增 `agentRunUserAudit` prop，并在运行中心弹窗渲染责任审计卡片。

- [x] **步骤 4：运行测试通过**

```bash
npm --prefix desktop test -- App.test.tsx
```

结果：PASS，`App.test.tsx` 51 条通过。

## 任务 3：降级、完整验证和第二大脑

- [x] **步骤 1：编写降级测试**

在 `App.test.tsx` 新增审计接口失败用例，确认运行中心仍能打开并显示「暂无责任运行」。同时补充「切换当前操作者后刷新 Agent 责任审计」红绿用例。

- [x] **步骤 2：运行完整验证**

```bash
npm --prefix desktop test
npm --prefix desktop run build
/Users/Masons/Ai/Maven/bin/mvn -Dmaven.repo.local=/Users/Masons/Ai/Maven_Ai_Store -q -pl server test
./scripts/check-real-runtime.sh .env.local
set -a; source .env.local; set +a; /Users/Masons/Ai/Maven/bin/mvn -Dmaven.repo.local=/Users/Masons/Ai/Maven_Ai_Store -q -pl server -Dmatrixcode.real-runtime-test=true -Dtest=RealRuntimeIntegrationTest test
git diff --check
```

结果：桌面端、服务端、真实运行检查、真实集成和静态检查通过。

- [x] **步骤 3：安全扫描**

扫描旧地址、旧 collection 和真实密钥片段，预期无输出。

- [x] **步骤 4：更新第二大脑并提交**

新增 `阶段成果/74 Agent Runtime 用户责任审计入口.md`，更新首页、阶段索引、模块地图、验证与风险、模型网关与上下文门禁、角色工作台与桌面端。

提交：`feat(desktop): 展示用户责任审计`

## 实际验证记录

- API client 红绿：`npm --prefix desktop test -- client.test.ts`，47 条通过。
- 运行中心红绿：`npm --prefix desktop test -- App.test.tsx`，53 条通过。
- 桌面端目标合并：`npm --prefix desktop test -- client.test.ts App.test.tsx`，100 条通过。
- 桌面端全量：`npm --prefix desktop test`，106 条通过。
- 桌面端构建：`npm --prefix desktop run build` 通过。
- 服务端全量：`files=84 tests=409 failures=0 errors=0 skipped=7`。
- 真实运行检查：MySQL、Milvus、Redis、RocketMQ 连通，真实运行配置检查通过。
- 真实集成：`RealRuntimeIntegrationTest tests=7 failures=0 errors=0 skipped=0`，耗时 `456.5 s`。
- 静态检查：`git diff --check` 通过。
- 安全扫描：旧地址、旧 collection、真实密钥和数据库密码扫描无命中。

## 阶段回溯

- 与最初需求对齐：多人智能体控制台现在能在运行中心查看当前用户责任链，满足角色和用户维度的可审计要求。
- 与布局要求对齐：右侧仍只显示运行指标和关键事件；责任审计明细放在运行中心弹窗。
- 与第 73 阶段对齐：前端只消费后端低敏审计报告，不展示 prompt、响应、向量正文、工具输出或密钥。
- 与上线目标对齐：审计接口不可用时运行中心降级为空态，不影响主工作台；切换当前操作者会刷新责任审计报告。
- 下一阶段建议：推进完整认证权限，把当前操作者从前端选择器升级为可信身份上下文，并限制审计查询权限。

## 自检

- 规格覆盖度：API、状态加载、运行中心展示、降级、验证和第二大脑均有任务。
- 占位符扫描：计划无「待定」「TODO」「后续实现」占位。
- 类型一致性：统一使用 `AgentRunUserAuditEntry`、`AgentRunUserAuditReport` 和 `loadAgentRunUserAudit(...)`。
