# MatrixCode Agent 运行审计详情实现计划

> **面向 AI 代理的工作者：** 必需子技能：使用 superpowers:subagent-driven-development（推荐）或 superpowers:executing-plans 逐任务实现此计划。步骤使用复选框（`- [ ]`）语法来跟踪进度。

**目标：** 在运行中心新增 Agent 运行审计详情卡片，聚合最新运行、事件计数和最近模型请求缓存线索。

**架构：** 继续复用 `OperationsDetailPanel` 已有 props，不新增后端 API 或数据库迁移。前端本地派生工具事件数、失败事件数和最近模型请求摘要，并在运行中心独立卡片展示。

**技术栈：** React、TypeScript、Vitest、Java 21、Spring Boot。

---

## 文件结构

- 修改：`desktop/src/components/InspectorPanel.tsx`，新增审计详情派生字段和卡片。
- 修改：`desktop/src/test/App.test.tsx`，增加运行中心审计详情断言。
- 新增：`docs/superpowers/specs/2026-06-26-matrixcode-agent-audit-detail-design.md`。
- 新增：`docs/superpowers/plans/2026-06-26-matrixcode-agent-audit-detail.md`。
- 新增：`/Users/Masons/Documents/Obsidian/Aevis/MatrixCode/阶段成果/59 Agent 运行审计详情.md`。

### 任务 1：运行中心 Agent 审计详情

- [x] **步骤 1：编写失败的 UI 测试**

在 `App.test.tsx` 的 `右侧运行指标和运行中心展示真实 Agent 运行事件` 测试中加入：

```ts
const Agent审计详情 = within(运行中心.getByLabelText('Agent 审计详情'));
expect(Agent审计详情.getByText('运行 run-1 · 失败 · 可重试')).toBeTruthy();
expect(Agent审计详情.getByText('目标 修复支付失败重试')).toBeTruthy();
expect(Agent审计详情.getByText('操作者 user-dev · 角色 开发')).toBeTruthy();
expect(Agent审计详情.getByText('模型 deepseek/deepseek-chat · coding')).toBeTruthy();
expect(Agent审计详情.getByText('工具事件 1 · 失败事件 1')).toBeTruthy();
expect(Agent审计详情.getByText('恢复 run-0 · 可重试')).toBeTruthy();
expect(Agent审计详情.getByText('摘要 测试命令超时，未产生交接文档')).toBeTruthy();
expect(Agent审计详情.getByText('失败 测试命令超时')).toBeTruthy();
expect(Agent审计详情.getByText('模型请求 request-1 · PROVIDER · prefix fp-cache-001')).toBeTruthy();
```

- [x] **步骤 2：运行测试验证失败**

```bash
npm --prefix desktop test -- --run src/test/App.test.tsx
```

预期：找不到 `Agent 审计详情`。

- [x] **步骤 3：实现最少前端代码**

在 `OperationsDetailPanel` 中增加：

```ts
const toolTraceCount = agentRunEvents.filter((event) => event.eventType === 'TOOL_TRACE').length;
const failureEventCount = agentRunEvents.filter((event) => event.eventType.includes('FAILED')).length;
```

在 Agent 运行时间线前新增卡片：

```tsx
<article className="metric-card" aria-label="Agent 审计详情">
  <p className="metric-card__label">Agent 审计详情</p>
  {latestAgentRun ? (
    <ul className="metric-list">
      <li>
        <span>
          运行 {latestAgentRun.id} · {agentRunStatusLabels[latestAgentRun.status]} ·{' '}
          {latestAgentRun.retryable ? '可重试' : '不可重试'}
        </span>
      </li>
      <li>
        <span>目标 {latestAgentRun.goal}</span>
      </li>
      <li>
        <span>操作者 {latestAgentRun.actorUserId} · 角色 {modelRoleLabels[latestAgentRun.roleKey]}</span>
      </li>
      <li>
        <span>
          模型 {latestAgentRun.providerId}/{latestAgentRun.modelName} · {latestAgentRun.agentKind}
        </span>
      </li>
      <li>
        <span>
          工具事件 {toolTraceCount.toLocaleString('zh-CN')} · 失败事件 {failureEventCount.toLocaleString('zh-CN')}
        </span>
      </li>
      {latestAgentRun.retryOfRunId ? (
        <li>
          <span>恢复 {latestAgentRun.retryOfRunId} · {latestAgentRun.retryable ? '可重试' : '不可重试'}</span>
        </li>
      ) : null}
      {latestAgentRun.summary ? (
        <li>
          <span>摘要 {latestAgentRun.summary}</span>
        </li>
      ) : null}
      {latestAgentRun.failureSummary ? (
        <li>
          <span>失败 {latestAgentRun.failureSummary}</span>
        </li>
      ) : null}
      {recentRequest ? (
        <li>
          <span>
            模型请求 {recentRequest.requestId} · {recentRequest.usage.cacheSource ?? 'UNKNOWN'} · prefix{' '}
            {recentRequest.usage.stablePrefixHash ?? '无'}
          </span>
        </li>
      ) : null}
    </ul>
  ) : (
    <p className="empty-state">暂无 Agent 审计详情</p>
  )}
</article>
```

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

同时精确扫描真实 API Key 和数据库密码，排除本地 `.env.local` 自身。

- [x] **步骤 5：更新第二大脑并回溯对齐**

新增第 59 阶段成果，更新首页、总览、阶段索引、模块地图、验证与风险、模型网关专题。

- [x] **步骤 6：提交并推送**

```bash
git add .
git commit -m "feat(Agent运行): 增加审计详情"
git push origin HEAD:master
```

远程 `master` 提交：`78959f3f3a1751cccaacd775dd30c315d26acf2f`。
