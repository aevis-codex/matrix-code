# MatrixCode 第十一阶段运行态提醒中心实现计划

> **面向 AI 代理的工作者：** 必需子技能：使用 superpowers:subagent-driven-development（推荐）或 superpowers:executing-plans 逐任务实现此计划。步骤使用复选框（`- [ ]`）语法来跟踪进度。

**目标：** 构建桌面端运行态提醒中心，让待审批、本地任务终态和 Compose 动作结果能在工作台中形成可见提醒。

**架构：** 服务端保持现有事件和工作台快照协议。桌面端新增纯函数从 `ProjectWorkbench` 派生提醒，App 负责关闭态和顶部提醒，`RoleSwitcher` 展示待审批徽标，`InspectorPanel` 展示提醒列表。

**技术栈：** React、TypeScript、Vitest、Testing Library、现有 SSE 工作台刷新机制。

---

## 文件结构

- 创建：`desktop/src/runtimeNotifications.ts`
  - 定义 `RuntimeNotification`、提醒等级、提醒派生、待审批计数和顶部提醒选择纯函数。
- 创建：`desktop/src/runtimeNotifications.test.ts`
  - 覆盖提醒派生、排序、上限、待审批计数和关闭态过滤。
- 修改：`desktop/src/components/RoleSwitcher.tsx`
  - 接收 `pendingApprovalCount`，在运维角色旁显示数字徽标。
- 修改：`desktop/src/components/InspectorPanel.tsx`
  - 接收 `runtimeNotifications`，新增“运行态提醒”卡片。
- 修改：`desktop/src/App.tsx`
  - 计算提醒、保存当前会话关闭 ID、展示顶部提醒条并传递徽标和列表数据。
- 修改：`desktop/src/test/App.test.tsx`
  - 覆盖顶部提醒、关闭提醒、SSE 刷新后新提醒和运维徽标。
- 修改：`desktop/src/App.css`
  - 添加顶部提醒、角色徽标、提醒列表等级样式。
- 修改：`docs/development/local-run.md`
  - 增加第十一阶段验证说明。
- 修改：`docs/superpowers/plans/2026-06-25-matrixcode-runtime-notifications.md`
  - 记录红灯、绿灯、全量验证和浏览器验证结果。

## 任务 1：提醒派生纯函数

**文件：**
- 创建：`desktop/src/runtimeNotifications.ts`
- 创建：`desktop/src/runtimeNotifications.test.ts`

- [x] **步骤 1：编写失败的纯函数测试**

创建 `desktop/src/runtimeNotifications.test.ts`：

```ts
import { describe, expect, it } from 'vitest';
import type { ProjectWorkbench } from './api/client';
import {
  countPendingApprovals,
  deriveRuntimeNotifications,
  latestVisibleNotification
} from './runtimeNotifications';

const baseWorkbench: ProjectWorkbench = {
  projectId: 'demo',
  projectName: '支付系统重构',
  currentStage: '上线准备',
  roles: [],
  documents: [],
  bugs: [],
  deploymentTargets: [],
  deploymentRuntimeSummaries: [],
  composeEnvironments: [],
  composeRuntimeViews: [],
  metrics: { cacheHitRate: 0, sessionTokens: 0, eventCount: 0, documentCount: 0, openBugCount: 0 },
  modelGateway: { bindings: [], metrics: { requestCount: 0, cacheHitTokens: 0, cacheMissInputTokens: 0, outputTokens: 0, cacheHitRate: 0, estimatedCost: 0, currency: 'CNY', recentContextTypes: [] }, recentRequests: [] },
  localExecution: {
    workspaces: [],
    recentFileOperations: [],
    recentTasks: [],
    activeTasks: [],
    recentTaskLogs: [],
    recentGitDiff: null,
    recentAuditRecords: []
  },
  events: []
};

describe('运行态提醒派生', () => {
  it('从待审批任务派生操作提醒并统计待审批数量', () => {
    const workbench = {
      ...baseWorkbench,
      localExecution: {
        ...baseWorkbench.localExecution,
        recentTasks: [
          {
            taskId: 'task-1',
            projectId: 'demo',
            workspaceId: 'workspace-1',
            actorId: 'user-dev',
            toolType: 'SHELL',
            command: 'git status',
            approvalDecision: 'ASK',
            approverId: '',
            approvalNote: '',
            decidedAt: null,
            canceledBy: '',
            cancelNote: '',
            canceledAt: null,
            safetyRejectionReason: '',
            status: 'APPROVAL_PENDING',
            exitCode: null,
            durationMillis: 0,
            createdAt: '2026-06-25T06:00:00Z'
          }
        ]
      }
    } as ProjectWorkbench;

    const notifications = deriveRuntimeNotifications(workbench);

    expect(countPendingApprovals(workbench)).toBe(1);
    expect(notifications[0]).toMatchObject({
      id: 'approval:task-1',
      level: 'ACTION',
      title: '需要审批本地命令',
      message: 'git status'
    });
  });

  it('按时间倒序保留最近五条本地任务和 Compose 提醒', () => {
    const workbench = {
      ...baseWorkbench,
      localExecution: {
        ...baseWorkbench.localExecution,
        recentTasks: Array.from({ length: 6 }, (_, index) => ({
          taskId: `task-${index}`,
          projectId: 'demo',
          workspaceId: 'workspace-1',
          actorId: 'user-dev',
          toolType: 'SHELL',
          command: `echo ${index}`,
          approvalDecision: 'ALLOW',
          approverId: 'user-reviewer',
          approvalNote: '',
          decidedAt: '2026-06-25T06:00:00Z',
          canceledBy: '',
          cancelNote: '',
          canceledAt: null,
          safetyRejectionReason: '',
          status: index % 2 === 0 ? 'SUCCESS' : 'FAILED',
          exitCode: index % 2 === 0 ? 0 : 1,
          durationMillis: 100,
          createdAt: `2026-06-25T06:0${index}:00Z`
        }))
      }
    } as ProjectWorkbench;

    const notifications = deriveRuntimeNotifications(workbench);

    expect(notifications).toHaveLength(5);
    expect(notifications[0].id).toBe('local-task:task-5:FAILED');
    expect(notifications[0].level).toBe('ERROR');
  });

  it('可以跳过当前会话已关闭的顶部提醒', () => {
    const notifications = [
      { id: 'n-1', level: 'ERROR' as const, title: '失败', message: '命令失败', occurredAt: '2026-06-25T06:00:00Z' },
      { id: 'n-2', level: 'SUCCESS' as const, title: '成功', message: '命令成功', occurredAt: '2026-06-25T05:00:00Z' }
    ];

    expect(latestVisibleNotification(notifications, new Set(['n-1']))?.id).toBe('n-2');
  });
});
```

- [x] **步骤 2：运行测试验证红灯**

运行：

```bash
cd desktop && npm test -- src/runtimeNotifications.test.ts
```

预期：失败，错误包含 `Cannot find module './runtimeNotifications'` 或导出函数不存在。

- [x] **步骤 3：实现提醒派生纯函数**

创建 `desktop/src/runtimeNotifications.ts`：

```ts
import type { ExecutionTask, ProjectWorkbench } from './api/client';

export type RuntimeNotificationLevel = 'ACTION' | 'SUCCESS' | 'WARNING' | 'ERROR';

export type RuntimeNotification = {
  id: string;
  level: RuntimeNotificationLevel;
  title: string;
  message: string;
  occurredAt: string;
};

const terminalTaskStatuses = ['SUCCESS', 'FAILED', 'CANCELED'] as const;

function taskOccurredAt(task: ExecutionTask): string {
  return task.canceledAt ?? task.decidedAt ?? task.createdAt;
}

function notificationWeight(level: RuntimeNotificationLevel): number {
  return level === 'ACTION' ? 4 : level === 'ERROR' ? 3 : level === 'WARNING' ? 2 : 1;
}

function compareNotifications(left: RuntimeNotification, right: RuntimeNotification): number {
  const rightTime = new Date(right.occurredAt).getTime();
  const leftTime = new Date(left.occurredAt).getTime();
  if (Number.isFinite(rightTime) && Number.isFinite(leftTime) && rightTime !== leftTime) {
    return rightTime - leftTime;
  }

  return notificationWeight(right.level) - notificationWeight(left.level);
}

export function countPendingApprovals(workbench: ProjectWorkbench): number {
  return workbench.localExecution.recentTasks.filter((task) => task.status === 'APPROVAL_PENDING').length;
}

export function deriveRuntimeNotifications(workbench: ProjectWorkbench): RuntimeNotification[] {
  const notifications: RuntimeNotification[] = [];

  workbench.localExecution.recentTasks.forEach((task) => {
    if (task.status === 'APPROVAL_PENDING') {
      notifications.push({
        id: `approval:${task.taskId}`,
        level: 'ACTION',
        title: '需要审批本地命令',
        message: task.command,
        occurredAt: task.createdAt
      });
      return;
    }

    if (terminalTaskStatuses.includes(task.status as (typeof terminalTaskStatuses)[number])) {
      const failed = task.status === 'FAILED';
      notifications.push({
        id: `local-task:${task.taskId}:${task.status}`,
        level: failed ? 'ERROR' : task.status === 'CANCELED' ? 'WARNING' : 'SUCCESS',
        title: failed ? '本地命令执行失败' : task.status === 'CANCELED' ? '本地命令已取消' : '本地命令执行成功',
        message: task.command,
        occurredAt: taskOccurredAt(task)
      });
    }
  });

  workbench.composeRuntimeViews.forEach((view) => {
    const operation = view.latestOperation;
    if (!operation) {
      return;
    }

    notifications.push({
      id: `compose:${operation.id}:${operation.status}`,
      level: operation.status === 'FAILED' ? 'ERROR' : 'SUCCESS',
      title: operation.status === 'FAILED' ? 'Compose 动作失败' : 'Compose 动作成功',
      message: operation.summary,
      occurredAt: operation.createdAt
    });
  });

  return notifications.sort(compareNotifications).slice(0, 5);
}

export function latestVisibleNotification(
  notifications: RuntimeNotification[],
  dismissedIds: Set<string>
): RuntimeNotification | null {
  return notifications.find((notification) => !dismissedIds.has(notification.id)) ?? null;
}
```

- [x] **步骤 4：运行纯函数测试绿灯**

运行：

```bash
cd desktop && npm test -- src/runtimeNotifications.test.ts
```

预期：测试通过。

- [x] **步骤 5：提交提醒派生函数**

运行：

```bash
git add desktop/src/runtimeNotifications.ts desktop/src/runtimeNotifications.test.ts
git commit -m "feat(桌面端): 派生运行态提醒"
```

## 任务 2：角色徽标和右侧提醒卡

**文件：**
- 修改：`desktop/src/components/RoleSwitcher.tsx`
- 修改：`desktop/src/components/InspectorPanel.tsx`
- 修改：`desktop/src/test/App.test.tsx`
- 修改：`desktop/src/App.css`

- [x] **步骤 1：编写失败的 UI 测试**

在 `desktop/src/test/App.test.tsx` 中新增测试：

```ts
it('待审批命令会显示运维徽标和运行态提醒列表', async () => {
  render(<App />);

  const 角色工作区 = within(await screen.findByRole('navigation', { name: '角色工作区' }));
  expect(角色工作区.getByLabelText('运维待审批 1 项')).toBeTruthy();

  const 运行态提醒 = within(screen.getByLabelText('运行态提醒'));
  expect(运行态提醒.getByText('需要审批本地命令')).toBeTruthy();
  expect(运行态提醒.getByText(/ssh prod systemctl restart app/)).toBeTruthy();
});
```

- [x] **步骤 2：运行测试验证红灯**

运行：

```bash
cd desktop && npm test -- src/test/App.test.tsx -t 待审批命令会显示运维徽标和运行态提醒列表
```

预期：失败，原因是没有“运行态提醒”卡片，也没有运维徽标。

- [x] **步骤 3：扩展 `RoleSwitcher`**

修改 `desktop/src/components/RoleSwitcher.tsx`：

```ts
type RoleSwitcherProps = {
  activeRole: string;
  pendingApprovalCount?: number;
  onRoleChange: (role: string) => void;
};
```

在运维角色按钮内显示徽标：

```tsx
{role.name === '运维' && pendingApprovalCount > 0 ? (
  <span className="role-item__badge" aria-label={`运维待审批 ${pendingApprovalCount} 项`}>
    {pendingApprovalCount > 9 ? '9+' : pendingApprovalCount}
  </span>
) : null}
```

- [x] **步骤 4：扩展 `InspectorPanel`**

在 `desktop/src/components/InspectorPanel.tsx` 导入 `RuntimeNotification`：

```ts
import type { RuntimeNotification } from '../runtimeNotifications';
```

扩展 props：

```ts
runtimeNotifications: RuntimeNotification[];
```

在“本地执行代理”卡片之后新增：

```tsx
<article className="metric-card" aria-label="运行态提醒">
  <p className="metric-card__label">运行态提醒</p>
  {runtimeNotifications.length ? (
    <ul className="notification-list">
      {runtimeNotifications.map((notification) => (
        <li className={`notification-list__item notification-list__item--${notification.level.toLowerCase()}`} key={notification.id}>
          <strong>{notification.title}</strong>
          <span>{notification.message}</span>
        </li>
      ))}
    </ul>
  ) : (
    <p className="empty-state">暂无运行态提醒</p>
  )}
</article>
```

- [x] **步骤 5：添加样式**

在 `desktop/src/App.css` 增加：

```css
.role-item__badge {
  align-self: center;
  min-width: 22px;
  border-radius: 999px;
  background: #dc2626;
  color: #fff;
  font-size: 12px;
  font-weight: 700;
  line-height: 22px;
  text-align: center;
}

.notification-list {
  display: grid;
  gap: 8px;
  margin: 0;
  padding: 0;
  list-style: none;
}

.notification-list__item {
  display: grid;
  gap: 4px;
  border-left: 3px solid #64748b;
  padding-left: 10px;
}

.notification-list__item--action { border-left-color: #2563eb; }
.notification-list__item--success { border-left-color: #16a34a; }
.notification-list__item--warning { border-left-color: #d97706; }
.notification-list__item--error { border-left-color: #dc2626; }
```

- [x] **步骤 6：运行 UI 测试绿灯**

运行：

```bash
cd desktop && npm test -- src/test/App.test.tsx -t 待审批命令会显示运维徽标和运行态提醒列表
```

预期：测试通过。

- [x] **步骤 7：提交角色徽标和右侧卡片**

运行：

```bash
git add desktop/src/components/RoleSwitcher.tsx desktop/src/components/InspectorPanel.tsx desktop/src/test/App.test.tsx desktop/src/App.css
git commit -m "feat(桌面端): 展示运行态提醒列表"
```

## 任务 3：顶部提醒条和关闭态

**文件：**
- 修改：`desktop/src/App.tsx`
- 修改：`desktop/src/test/App.test.tsx`
- 修改：`desktop/src/App.css`

- [x] **步骤 1：编写失败的顶部提醒测试**

在 `desktop/src/test/App.test.tsx` 中新增测试：

```ts
it('顶部运行态提醒可以关闭且不会影响审批按钮', async () => {
  render(<App />);

  const 顶部提醒 = await screen.findByRole('status', { name: '运行态提醒' });
  expect(within(顶部提醒).getByText('需要审批本地命令')).toBeTruthy();

  fireEvent.click(within(顶部提醒).getByRole('button', { name: '关闭提醒' }));

  expect(screen.queryByRole('status', { name: '运行态提醒' })).toBeNull();
  expect(screen.getByRole('button', { name: '批准执行' })).toBeTruthy();
});
```

- [x] **步骤 2：运行测试验证红灯**

运行：

```bash
cd desktop && npm test -- src/test/App.test.tsx -t 顶部运行态提醒可以关闭且不会影响审批按钮
```

预期：失败，原因是没有顶部提醒条。

- [x] **步骤 3：在 App 中接入提醒状态**

在 `desktop/src/App.tsx` 导入：

```ts
import {
  countPendingApprovals,
  deriveRuntimeNotifications,
  latestVisibleNotification
} from './runtimeNotifications';
```

新增状态：

```ts
const [dismissedNotificationIds, setDismissedNotificationIds] = useState<Set<string>>(() => new Set());
```

在 ready 渲染区计算：

```ts
const runtimeNotifications = deriveRuntimeNotifications(workbench);
const pendingApprovalCount = countPendingApprovals(workbench);
const topNotification = latestVisibleNotification(runtimeNotifications, dismissedNotificationIds);
```

在阶段头下方渲染：

```tsx
{topNotification ? (
  <section className={`runtime-alert runtime-alert--${topNotification.level.toLowerCase()}`} role="status" aria-label="运行态提醒">
    <div>
      <strong>{topNotification.title}</strong>
      <span>{topNotification.message}</span>
    </div>
    <button
      aria-label="关闭提醒"
      className="runtime-alert__close"
      onClick={() =>
        setDismissedNotificationIds((current) => new Set([...current, topNotification.id]))
      }
      type="button"
    >
      ×
    </button>
  </section>
) : null}
```

把 `pendingApprovalCount` 传给 `RoleSwitcher`，把 `runtimeNotifications` 传给 `InspectorPanel`。

- [x] **步骤 4：添加顶部提醒样式**

在 `desktop/src/App.css` 增加：

```css
.runtime-alert {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  border: 1px solid #cbd5e1;
  border-radius: 8px;
  padding: 12px 14px;
  background: #f8fafc;
}

.runtime-alert div {
  display: grid;
  gap: 4px;
}

.runtime-alert--action { border-color: #93c5fd; background: #eff6ff; }
.runtime-alert--success { border-color: #86efac; background: #f0fdf4; }
.runtime-alert--warning { border-color: #fcd34d; background: #fffbeb; }
.runtime-alert--error { border-color: #fca5a5; background: #fef2f2; }

.runtime-alert__close {
  width: 32px;
  height: 32px;
  border: 0;
  border-radius: 50%;
  background: transparent;
  color: #334155;
  cursor: pointer;
  font-size: 20px;
  line-height: 1;
}
```

- [x] **步骤 5：运行顶部提醒测试绿灯**

运行：

```bash
cd desktop && npm test -- src/test/App.test.tsx -t 顶部运行态提醒可以关闭且不会影响审批按钮
```

预期：测试通过。

- [x] **步骤 6：提交顶部提醒条**

运行：

```bash
git add desktop/src/App.tsx desktop/src/test/App.test.tsx desktop/src/App.css
git commit -m "feat(桌面端): 添加顶部运行态提醒"
```

## 任务 4：SSE 刷新后提醒同步

**文件：**
- 修改：`desktop/src/test/App.test.tsx`

- [x] **步骤 1：编写失败的 SSE 提醒测试**

在 `desktop/src/test/App.test.tsx` 中扩展已有 `收到本地任务运行态事件后自动刷新工作台` 测试，追加断言：

```ts
expect(await screen.findByText('本地命令执行成功')).toBeTruthy();
expect(screen.getByText(/sleep 5/)).toBeTruthy();
```

新增 Compose 失败提醒断言：

```ts
expect(await screen.findByText('Compose 动作失败')).toBeTruthy();
expect(screen.getByText(/Docker Compose 命令超时/)).toBeTruthy();
```

- [x] **步骤 2：运行测试验证红灯或确认已覆盖**

运行：

```bash
cd desktop && npm test -- src/test/App.test.tsx -t '收到本地任务运行态事件后自动刷新工作台|收到 Compose 运行态事件后自动刷新工作台'
```

预期：如果任务 3 已完整接入，测试直接通过；如果失败，错误应指向提醒没有从刷新后的工作台重新派生。

- [x] **步骤 3：补齐 App 派生路径**

如果步骤 2 失败，确认 `runtimeNotifications` 是在每次 ready 渲染时从当前 `workbench` 计算，而不是只在初次加载时计算。不要新增额外状态保存提醒列表。

- [x] **步骤 4：运行相关测试绿灯**

运行同一步骤 2 命令，预期通过。

- [x] **步骤 5：提交 SSE 提醒同步测试**

运行：

```bash
git add desktop/src/test/App.test.tsx desktop/src/App.tsx
git commit -m "test(桌面端): 覆盖 SSE 刷新后的提醒同步"
```

如果只修改测试文件，`git add` 会只暂存测试文件。

## 任务 5：文档、全量验证和浏览器验证

**文件：**
- 修改：`docs/development/local-run.md`
- 修改：`docs/superpowers/plans/2026-06-25-matrixcode-runtime-notifications.md`

- [x] **步骤 1：补充本地运行文档**

在 `docs/development/local-run.md` 第十阶段后新增：

```markdown
## 第十一阶段运行态提醒中心验证

服务端和桌面端启动后，提交一条需要审批的本地命令，例如 `git status`。桌面端左侧运维角色应显示待审批数量徽标，顶部应显示“需要审批本地命令”提醒，右侧“运行态提醒”卡片应列出该提醒。

批准一条 `sleep 3` 命令并等待完成。页面应在 SSE 刷新后显示“本地命令执行成功”提醒。触发 Compose 启动失败或超时后，右侧“运行态提醒”应显示“Compose 动作失败”和失败摘要。
```

- [x] **步骤 2：运行全量验证**

运行：

```bash
/Users/Masons/Ai/Maven/bin/mvn -Dmaven.repo.local=/Users/Masons/Ai/Maven_Ai_Store -q -pl server test
cd desktop && npm test
cd desktop && npm run build
cd desktop && npm run tauri:build -- --help
```

预期：全部退出码 0。

- [x] **步骤 3：运行文档和差异检查**

运行：

```bash
rg -n "T(O)DO|T[B]D|F[I]XME|待[定]|占[位]|place(holder)|S[u]mmary|G[o]als|Acceptance C[r]iteria" docs/superpowers/specs/2026-06-25-matrixcode-runtime-notifications-design.md docs/superpowers/plans/2026-06-25-matrixcode-runtime-notifications.md
rg --pcre2 -n "(?<!bin/)\\bm[v]n\\b" docs/superpowers/specs/2026-06-25-matrixcode-runtime-notifications-design.md docs/superpowers/plans/2026-06-25-matrixcode-runtime-notifications.md docs/development/local-run.md
git diff --check
```

预期：前两条无输出；`git diff --check` 退出码 0。

- [x] **步骤 4：浏览器验证**

启动服务端和 Vite，打开 `http://127.0.0.1:5173/`：

- 提交 `git status` 待审批命令。
- 观察运维徽标、顶部提醒和右侧提醒卡片。
- 关闭顶部提醒，确认审批按钮仍可用。
- 批准 `sleep 3` 并等待完成，确认成功提醒出现。
- 触发 Compose 失败或超时，确认错误提醒出现。
- 浏览器控制台无 error。

- [x] **步骤 5：记录验证并提交**

在本计划末尾新增“第十一阶段验证记录”，记录红灯、绿灯、全量测试、构建、文档检查、浏览器验证、端口释放和残留进程检查。

运行：

```bash
git add docs/development/local-run.md docs/superpowers/plans/2026-06-25-matrixcode-runtime-notifications.md
git commit -m "docs: 记录第十一阶段运行态提醒验证"
```

## 自检记录

- 规格覆盖：待审批徽标、顶部提醒、右侧提醒卡、关闭态、SSE 刷新后提醒同步和浏览器验证均有任务覆盖。
- 范围控制：第十一阶段不引入服务端提醒表、系统级通知、多成员状态、日志流或数据库持久化。
- 类型一致性：计划使用 `RuntimeNotification`、`deriveRuntimeNotifications`、`countPendingApprovals` 和 `latestVisibleNotification`，命名贯穿测试与实现。
- TDD 顺序：纯函数、UI 卡片、顶部提醒和 SSE 同步均先写测试，再写实现。

## 第十一阶段验证记录

- 纯函数红灯：`cd desktop && npm test -- src/runtimeNotifications.test.ts` 首次失败，原因是缺少 `./runtimeNotifications` 模块。
- 纯函数绿灯：同一命令通过，`desktop/src/runtimeNotifications.test.ts` 共 3 条测试通过。
- UI 列表红灯：`cd desktop && npm test -- src/test/App.test.tsx -t 待审批命令会显示运维徽标和运行态提醒列表` 首次失败，原因是没有运维待审批徽标和运行态提醒卡片。
- UI 列表绿灯：同一命令通过；随后 `cd desktop && npm test -- src/test/App.test.tsx` 通过，App 测试 26 条通过。
- 顶部提醒红灯：`cd desktop && npm test -- src/test/App.test.tsx -t 顶部运行态提醒可以关闭且不会影响审批按钮` 首次失败，原因是没有顶部运行态提醒。
- 顶部提醒绿灯：同一命令通过；随后 `cd desktop && npm test -- src/test/App.test.tsx` 通过，App 测试 27 条通过。期间修正了 Compose 事件测试的异步等待，等待 SSE 订阅和刷新完成后再断言。
- SSE 提醒同步：`cd desktop && npm test -- src/test/App.test.tsx -t '收到本地任务运行态事件后自动刷新工作台|收到 Compose 运行态事件后自动刷新工作台'` 通过，2 条事件刷新测试通过。
- 服务端全量测试：`/Users/Masons/Ai/Maven/bin/mvn -Dmaven.repo.local=/Users/Masons/Ai/Maven_Ai_Store -q -pl server test` 退出码 0，Surefire 汇总为 201 条测试通过，0 个失败，0 个错误，0 个跳过。
- 桌面端全量测试：`cd desktop && npm test` 退出码 0，3 个测试文件、57 条测试通过。
- 桌面端构建：`cd desktop && npm run build` 退出码 0，`tsc --noEmit` 和 Vite production build 均通过。
- Tauri 检查：`cd desktop && npm run tauri:build -- --help` 退出码 0，Tauri build 帮助命令可用。
- 浏览器验证：启动服务端和 Vite 后打开 `http://127.0.0.1:5173/`，API 提交 `git status` 待审批命令，页面显示运维待审批徽标、顶部“需要审批本地命令”提醒和右侧“运行态提醒”列表；关闭顶部提醒后，“批准执行”按钮仍可用。
- 浏览器运行态验证：API 提交并批准 `sleep 3`，任务 `6a691725-184f-46e8-bd7d-5fd7cbcd4908` 最终进入 `SUCCESS`，页面通过 SSE 显示“本地命令执行成功”和 `sleep 3`；配置 Compose 服务 `missing-service` 后触发启动，操作 `df418f4b-77f3-41c6-baf0-079498ae15e5` 返回 `FAILED`，摘要为 `Docker Compose 命令超时`，页面显示“Compose 动作失败”提醒。
- 浏览器控制台：浏览器验证期间 error 日志为空。
- 服务收尾：服务端和 Vite 均已停止，`lsof -nP -iTCP:8080 -sTCP:LISTEN` 与 `lsof -nP -iTCP:5173 -sTCP:LISTEN` 无输出；`pgrep -fl 'docker compose|docker-credential-desktop|vite --host 127.0.0.1|spring-boot:run'` 无输出。

### 2026-06-25 回溯验收

- 结论：第十一阶段已完成并验证；历史 checklist 已按执行记录回填为完成状态。
- 初始需求对齐：本阶段服务于多人协作智能体控制台的运行态可见性，覆盖待审批、任务终态和 Compose 结果提醒，未偏离 MVP 纵切目标。
- 边界确认：本阶段未引入服务端提醒持久化，相关缺口已由第十二、十三、十四阶段补齐。
