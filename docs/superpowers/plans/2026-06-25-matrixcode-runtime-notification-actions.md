# MatrixCode 第十三阶段运行态提醒操作中心实现计划

> **面向 AI 代理的工作者：** 必需子技能：使用 superpowers:subagent-driven-development（推荐）或 superpowers:executing-plans 逐任务实现此计划。步骤使用复选框（`- [ ]`）语法来跟踪进度。

**目标：** 把右侧运行态提醒列表升级为可筛选、可查看未读数量、可一键全部已读的轻量操作中心。

**架构：** 服务端继续使用第十二阶段的内存提醒收件箱，新增当前项目批量已读方法和接口，成功后发布项目事件触发桌面端刷新。桌面端由 `App` 维护筛选和批量操作状态，`InspectorPanel` 只负责展示、筛选切换和触发回调，保持组件可测。

**技术栈：** Java 21、Spring Boot、JUnit 5、AssertJ、MockMvc、React、TypeScript、Vitest、Testing Library、Vite。

---

## 文件结构

- 修改：`server/src/test/java/com/matrixcode/runtime/RuntimeNotificationServiceTest.java`
  - 增加批量已读和幂等测试。
- 修改：`server/src/main/java/com/matrixcode/runtime/application/RuntimeNotificationService.java`
  - 增加 `markAllRead(projectId)`，复用 `recent(projectId)` 排序结果。
- 修改：`server/src/main/java/com/matrixcode/workbench/application/WorkbenchService.java`
  - 增加 `markAllRuntimeNotificationsRead(projectId)` 并发布 `RUNTIME_NOTIFICATIONS_READ`。
- 修改：`server/src/main/java/com/matrixcode/workbench/api/WorkbenchController.java`
  - 增加 `POST /runtime-notifications/read-all`。
- 修改：`server/src/test/java/com/matrixcode/workbench/WorkbenchControllerTest.java`
  - 覆盖批量已读接口、空项目返回空数组、工作台读取已读状态。
- 修改：`desktop/src/api/client.ts`
  - 增加批量已读客户端方法和 SSE 事件类型。
- 修改：`desktop/src/api/client.test.ts`
  - 覆盖批量已读请求路径和 SSE 事件监听。
- 修改：`desktop/src/App.tsx`
  - 增加提醒筛选状态、批量已读提交状态、筛选后的列表和批量已读回调。
- 修改：`desktop/src/components/InspectorPanel.tsx`
  - 增加未读数量、分段筛选按钮、全部已读按钮、空未读文案。
- 修改：`desktop/src/App.css`
  - 增加提醒工具条、分段按钮和批量操作按钮样式。
- 修改：`desktop/src/test/App.test.tsx`
  - 覆盖右侧未读数量、筛选切换、空未读、全部已读成功和失败。
- 修改：`docs/development/local-run.md`
  - 记录第十三阶段人工浏览器验证步骤和验证命令。

## 任务 1：服务端提醒收件箱批量已读

**文件：**
- 修改：`server/src/test/java/com/matrixcode/runtime/RuntimeNotificationServiceTest.java`
- 修改：`server/src/main/java/com/matrixcode/runtime/application/RuntimeNotificationService.java`

- [x] **步骤 1：编写失败的服务端单元测试**

在 `RuntimeNotificationServiceTest` 增加两个测试：

```java
@Test
void 批量标记项目提醒已读() {
    var service = new RuntimeNotificationService();
    service.sync(
            "demo",
            localExecution(List.of(
                    task("task-approval", "git status", ExecutionTaskStatus.APPROVAL_PENDING, null, "2026-06-25T06:00:00Z"),
                    task("task-success", "sleep 1", ExecutionTaskStatus.SUCCESS, 0, "2026-06-25T06:01:00Z")
            )),
            List.of()
    );

    var notifications = service.markAllRead("demo");

    assertThat(notifications).hasSize(2);
    assertThat(notifications).allSatisfy(notification -> assertThat(notification.readAt()).isNotNull());
    assertThat(service.recent("demo")).allSatisfy(notification -> assertThat(notification.readAt()).isNotNull());
}

@Test
void 重复批量已读不会覆盖原已读时间() {
    var service = new RuntimeNotificationService();
    service.sync(
            "demo",
            localExecution(List.of(task("task-1", "git status", ExecutionTaskStatus.APPROVAL_PENDING, null, "2026-06-25T06:00:00Z"))),
            List.of()
    );

    var firstReadAt = service.markAllRead("demo").getFirst().readAt();
    var secondReadAt = service.markAllRead("demo").getFirst().readAt();

    assertThat(firstReadAt).isNotNull();
    assertThat(secondReadAt).isEqualTo(firstReadAt);
}
```

- [x] **步骤 2：运行测试验证失败**

运行：

```bash
/Users/Masons/Ai/Maven/bin/mvn -Dmaven.repo.local=/Users/Masons/Ai/Maven_Ai_Store -q -pl server -Dtest=RuntimeNotificationServiceTest test
```

预期：编译失败，提示 `markAllRead(String)` 未定义。

- [x] **步骤 3：编写最少实现代码**

在 `RuntimeNotificationService` 中，放在 `markRead` 后面：

```java
public List<RuntimeNotification> markAllRead(String projectId) {
    projectId = requireText(projectId, "项目编号不能为空");
    var projectNotifications = notifications.get(projectId);
    if (projectNotifications == null || projectNotifications.isEmpty()) {
        return List.of();
    }

    var readAt = Instant.now();
    projectNotifications.replaceAll((ignored, notification) ->
            notification.readAt() == null ? notification.withReadAt(readAt) : notification
    );
    return recent(projectId);
}
```

- [x] **步骤 4：运行测试验证通过**

运行：

```bash
/Users/Masons/Ai/Maven/bin/mvn -Dmaven.repo.local=/Users/Masons/Ai/Maven_Ai_Store -q -pl server -Dtest=RuntimeNotificationServiceTest test
```

预期：`RuntimeNotificationServiceTest` 全部通过。

- [x] **步骤 5：Commit**

```bash
git add server/src/test/java/com/matrixcode/runtime/RuntimeNotificationServiceTest.java server/src/main/java/com/matrixcode/runtime/application/RuntimeNotificationService.java
git commit -m "feat(服务端): 支持运行态提醒全部已读"
```

## 任务 2：服务端工作台接口和事件

**文件：**
- 修改：`server/src/main/java/com/matrixcode/workbench/application/WorkbenchService.java`
- 修改：`server/src/main/java/com/matrixcode/workbench/api/WorkbenchController.java`
- 修改：`server/src/test/java/com/matrixcode/workbench/WorkbenchControllerTest.java`

- [x] **步骤 1：编写失败的控制器测试**

在 `WorkbenchControllerTest` 增加：

```java
@Test
void 可以批量标记当前项目运行态提醒已读() throws Exception {
    var workspaceResponse = mockMvc.perform(post("/api/projects/demo/local-execution/workspaces")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""
                            {"name":"当前项目","rootPath":"%s"}
                            """.formatted(workspace.toString())))
            .andExpect(status().isOk())
            .andReturn();
    var workspaceId = JsonPath.read(workspaceResponse.getResponse().getContentAsString(), "$.id").toString();
    mockMvc.perform(post("/api/projects/demo/local-execution/commands")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""
                            {"workspaceId":"%s","actorId":"user-ops","command":"git status"}
                            """.formatted(workspaceId)))
            .andExpect(status().isOk());

    mockMvc.perform(get("/api/projects/demo/workbench"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.runtimeNotifications[0].readAt").value(nullValue()));

    mockMvc.perform(post("/api/projects/demo/runtime-notifications/read-all"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$[0].readAt").isNotEmpty());

    mockMvc.perform(post("/api/projects/demo/runtime-notifications/read-all"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$[0].readAt").isNotEmpty());

    mockMvc.perform(get("/api/projects/demo/workbench"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.runtimeNotifications[0].readAt").isNotEmpty());
}

@Test
void 空项目批量标记运行态提醒已读时返回空数组() throws Exception {
    mockMvc.perform(post("/api/projects/empty/runtime-notifications/read-all"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$").isArray())
            .andExpect(jsonPath("$").isEmpty());
}
```

- [x] **步骤 2：运行测试验证失败**

运行：

```bash
/Users/Masons/Ai/Maven/bin/mvn -Dmaven.repo.local=/Users/Masons/Ai/Maven_Ai_Store -q -pl server -Dtest=WorkbenchControllerTest test
```

预期：批量已读接口返回 404。

- [x] **步骤 3：实现服务和控制器**

在 `WorkbenchService` 的单条已读方法后增加：

```java
public List<RuntimeNotification> markAllRuntimeNotificationsRead(String projectId) {
    requireText(projectId, "项目编号不能为空");
    var notifications = runtimeNotificationService.markAllRead(projectId);
    publish(projectId, "RUNTIME_NOTIFICATIONS_READ", "运行态提醒已全部已读");
    return notifications;
}
```

在 `WorkbenchController` 的单条已读接口后增加：

```java
@PostMapping("/runtime-notifications/read-all")
public List<RuntimeNotification> markAllRuntimeNotificationsRead(@PathVariable String projectId) {
    return service.markAllRuntimeNotificationsRead(projectId);
}
```

- [x] **步骤 4：运行测试验证通过**

运行：

```bash
/Users/Masons/Ai/Maven/bin/mvn -Dmaven.repo.local=/Users/Masons/Ai/Maven_Ai_Store -q -pl server -Dtest=WorkbenchControllerTest test
```

预期：`WorkbenchControllerTest` 全部通过。

- [x] **步骤 5：Commit**

```bash
git add server/src/main/java/com/matrixcode/workbench/application/WorkbenchService.java server/src/main/java/com/matrixcode/workbench/api/WorkbenchController.java server/src/test/java/com/matrixcode/workbench/WorkbenchControllerTest.java
git commit -m "feat(服务端): 暴露运行态提醒全部已读接口"
```

## 任务 3：桌面端 API 客户端

**文件：**
- 修改：`desktop/src/api/client.ts`
- 修改：`desktop/src/api/client.test.ts`

- [x] **步骤 1：编写失败的客户端测试**

在 `client.test.ts` 导入 `markAllRuntimeNotificationsRead`，并增加：

```ts
it('批量标记运行态提醒已读时调用全部已读地址', async () => {
  const fetchMock = vi.fn().mockResolvedValue({
    ok: true,
    json: async () => [
      {
        id: 'approval:task-1',
        projectId: 'demo',
        level: 'ACTION',
        title: '需要审批本地命令',
        message: 'git status',
        sourceType: 'APPROVAL',
        sourceId: 'task-1',
        occurredAt: '2026-06-25T06:00:00Z',
        readAt: '2026-06-25T06:01:00Z'
      }
    ]
  });
  vi.stubGlobal('fetch', fetchMock);

  await markAllRuntimeNotificationsRead('demo', 'http://localhost:8080');

  expect(fetchMock).toHaveBeenCalledWith('http://localhost:8080/api/projects/demo/runtime-notifications/read-all', {
    method: 'POST',
    headers: { Accept: 'application/json' }
  });
});
```

在现有「订阅项目运行态事件并在关闭时清理监听器」测试中，向 `expectedTypes` 或等价断言加入：

```ts
'RUNTIME_NOTIFICATIONS_READ'
```

- [x] **步骤 2：运行测试验证失败**

运行：

```bash
cd desktop && npm test -- src/api/client.test.ts
```

预期：`markAllRuntimeNotificationsRead` 导入或调用失败，SSE 事件断言缺少新事件。

- [x] **步骤 3：实现 API 客户端**

在 `runtimeSyncEventTypes` 中追加：

```ts
'RUNTIME_NOTIFICATIONS_READ'
```

在 `markRuntimeNotificationRead` 后增加：

```ts
export function markAllRuntimeNotificationsRead(
  projectId: string,
  serverUrl = matrixCodeServerUrl()
): Promise<RuntimeNotification[]> {
  return requestJson<RuntimeNotification[]>(
    projectUrl(serverUrl, projectId, '/runtime-notifications/read-all'),
    { method: 'POST' }
  );
}
```

- [x] **步骤 4：运行测试验证通过**

运行：

```bash
cd desktop && npm test -- src/api/client.test.ts
```

预期：`client.test.ts` 全部通过。

- [x] **步骤 5：Commit**

```bash
git add desktop/src/api/client.ts desktop/src/api/client.test.ts
git commit -m "feat(桌面端): 接入运行态提醒全部已读接口"
```

## 任务 4：桌面端提醒操作中心

**文件：**
- 修改：`desktop/src/App.tsx`
- 修改：`desktop/src/components/InspectorPanel.tsx`
- 修改：`desktop/src/App.css`
- 修改：`desktop/src/test/App.test.tsx`

- [x] **步骤 1：编写失败的 App 测试**

在 `App.test.tsx` 的 API mock 中加入 `markAllRuntimeNotificationsRead: vi.fn()`，导入并声明：

```ts
const 批量标记运行态提醒已读 = vi.mocked(markAllRuntimeNotificationsRead);
```

在 `beforeEach` 中设置：

```ts
批量标记运行态提醒已读.mockResolvedValue([服务端已读审批提醒, 服务端已读成功提醒]);
```

增加三个测试：

```ts
it('右侧运行态提醒支持未读数量和未读筛选', async () => {
  加载项目工作台.mockResolvedValueOnce({
    ...基础工作台,
    runtimeNotifications: [服务端未读审批提醒, 服务端已读成功提醒]
  });

  render(<App />);

  const 运行态提醒 = within(await screen.findByLabelText('运行态提醒列表'));
  expect(运行态提醒.getByText('未读 1')).toBeTruthy();
  fireEvent.click(运行态提醒.getByRole('button', { name: '未读' }));

  expect(运行态提醒.getByText('需要审批本地命令')).toBeTruthy();
  expect(运行态提醒.queryByText('本地命令执行成功')).toBeNull();
});

it('未读筛选没有记录时显示空状态', async () => {
  加载项目工作台.mockResolvedValueOnce({
    ...基础工作台,
    runtimeNotifications: [服务端已读审批提醒]
  });

  render(<App />);

  const 运行态提醒 = within(await screen.findByLabelText('运行态提醒列表'));
  fireEvent.click(运行态提醒.getByRole('button', { name: '未读' }));

  expect(运行态提醒.getByText('暂无未读提醒')).toBeTruthy();
});

it('可以在右侧把全部运行态提醒标记为已读', async () => {
  加载项目工作台
    .mockResolvedValueOnce({
      ...基础工作台,
      runtimeNotifications: [服务端未读审批提醒, { ...服务端已读成功提醒, readAt: null }]
    })
    .mockResolvedValueOnce({
      ...基础工作台,
      runtimeNotifications: [服务端已读审批提醒, 服务端已读成功提醒]
    });

  render(<App />);

  expect(await screen.findByRole('status', { name: '运行态提醒' })).toBeTruthy();
  fireEvent.click(within(screen.getByLabelText('运行态提醒列表')).getByRole('button', { name: '全部已读' }));

  await waitFor(() => expect(批量标记运行态提醒已读).toHaveBeenCalledWith('demo'));
  await waitFor(() => expect(加载项目工作台).toHaveBeenCalledTimes(2));
  expect(screen.queryByRole('status', { name: '运行态提醒' })).toBeNull();
  expect(within(screen.getByLabelText('运行态提醒列表')).getAllByText('已读')).toHaveLength(2);
});
```

增加失败测试：

```ts
it('全部已读失败时保留页面并显示同步错误', async () => {
  加载项目工作台.mockResolvedValueOnce({
    ...基础工作台,
    runtimeNotifications: [服务端未读审批提醒]
  });
  批量标记运行态提醒已读.mockRejectedValueOnce(new Error('network'));

  render(<App />);

  fireEvent.click(within(await screen.findByLabelText('运行态提醒列表')).getByRole('button', { name: '全部已读' }));

  expect(await screen.findByText('同步最新工作台失败，请稍后重试')).toBeTruthy();
  expect(screen.getByRole('status', { name: '运行态提醒' })).toBeTruthy();
});
```

- [x] **步骤 2：运行测试验证失败**

运行：

```bash
cd desktop && npm test -- src/test/App.test.tsx
```

预期：缺少批量 API mock、按钮和筛选 UI，相关测试失败。

- [x] **步骤 3：实现 `App.tsx` 状态和回调**

导入新 API：

```ts
markAllRuntimeNotificationsRead,
```

增加类型和状态：

```ts
type RuntimeNotificationFilter = 'all' | 'unread';

const [runtimeNotificationFilter, setRuntimeNotificationFilter] = useState<RuntimeNotificationFilter>('all');
const [runtimeNotificationActionBusy, setRuntimeNotificationActionBusy] = useState(false);
```

在 `handleDismissTopNotification` 后增加：

```ts
async function handleMarkAllRuntimeNotificationsRead() {
  if (workbenchState.type !== 'ready' || runtimeNotificationActionBusy) {
    return;
  }

  setRuntimeNotificationActionBusy(true);
  try {
    await markAllRuntimeNotificationsRead(workbenchState.workbench.projectId);
    setDismissedNotificationIds((current) => new Set([...current, ...runtimeNotificationsFromWorkbench(workbenchState.workbench).map((notification) => notification.id)]));
    await refreshWorkbench({ keepCurrent: true });
  } catch {
    setWorkbenchState((current) =>
      current.type === 'ready' ? { ...current, refreshing: false, syncError: syncFailureMessage } : current
    );
  } finally {
    setRuntimeNotificationActionBusy(false);
  }
}
```

在渲染前计算：

```ts
const unreadRuntimeNotificationCount = runtimeNotifications.filter((notification) => !notification.readAt).length;
const visibleRuntimeNotifications =
  runtimeNotificationFilter === 'unread'
    ? runtimeNotifications.filter((notification) => !notification.readAt)
    : runtimeNotifications;
```

传给 `InspectorPanel`：

```tsx
runtimeNotifications={visibleRuntimeNotifications}
runtimeNotificationFilter={runtimeNotificationFilter}
runtimeNotificationUnreadCount={unreadRuntimeNotificationCount}
runtimeNotificationActionBusy={runtimeNotificationActionBusy}
onRuntimeNotificationFilterChange={setRuntimeNotificationFilter}
onMarkAllRuntimeNotificationsRead={handleMarkAllRuntimeNotificationsRead}
```

- [x] **步骤 4：实现 `InspectorPanel.tsx` UI**

扩展 props：

```ts
type RuntimeNotificationFilter = 'all' | 'unread';

runtimeNotificationFilter?: RuntimeNotificationFilter;
runtimeNotificationUnreadCount?: number;
runtimeNotificationActionBusy?: boolean;
onRuntimeNotificationFilterChange?: (filter: RuntimeNotificationFilter) => void;
onMarkAllRuntimeNotificationsRead?: () => Promise<void>;
```

在组件参数中设置默认值：

```ts
runtimeNotificationFilter = 'all',
runtimeNotificationUnreadCount = runtimeNotifications.filter((notification) => !notification.readAt).length,
runtimeNotificationActionBusy = false,
onRuntimeNotificationFilterChange,
onMarkAllRuntimeNotificationsRead,
```

替换提醒卡片头部：

```tsx
<div className="notification-toolbar">
  <div>
    <p className="metric-card__label">运行态提醒</p>
    <span className="notification-toolbar__count">未读 {runtimeNotificationUnreadCount}</span>
  </div>
  <div className="notification-toolbar__actions">
    <div className="notification-filter" aria-label="运行态提醒筛选">
      <button
        aria-pressed={runtimeNotificationFilter === 'all'}
        className="notification-filter__button"
        onClick={() => onRuntimeNotificationFilterChange?.('all')}
        type="button"
      >
        全部
      </button>
      <button
        aria-pressed={runtimeNotificationFilter === 'unread'}
        className="notification-filter__button"
        onClick={() => onRuntimeNotificationFilterChange?.('unread')}
        type="button"
      >
        未读
      </button>
    </div>
    <button
      className="secondary-button notification-toolbar__mark-read"
      disabled={runtimeNotificationUnreadCount === 0 || runtimeNotificationActionBusy}
      onClick={() => void onMarkAllRuntimeNotificationsRead?.()}
      type="button"
    >
      全部已读
    </button>
  </div>
</div>
```

空状态改为：

```tsx
<p className="empty-state">
  {runtimeNotificationFilter === 'unread' ? '暂无未读提醒' : '暂无运行态提醒'}
</p>
```

- [x] **步骤 5：补充 CSS**

在 `App.css` 中添加：

```css
.notification-toolbar {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 12px;
}

.notification-toolbar__count {
  color: var(--text-muted);
  display: block;
  font-size: 12px;
  margin-top: 4px;
}

.notification-toolbar__actions {
  align-items: flex-end;
  display: flex;
  flex-direction: column;
  gap: 8px;
}

.notification-filter {
  background: rgba(15, 23, 42, 0.06);
  border-radius: 8px;
  display: inline-flex;
  padding: 3px;
}

.notification-filter__button {
  background: transparent;
  border: 0;
  border-radius: 6px;
  color: var(--text-muted);
  cursor: pointer;
  font-size: 12px;
  min-width: 44px;
  padding: 5px 8px;
}

.notification-filter__button[aria-pressed='true'] {
  background: var(--surface);
  color: var(--text-strong);
  box-shadow: 0 1px 2px rgba(15, 23, 42, 0.08);
}

.notification-toolbar__mark-read {
  font-size: 12px;
  min-height: 30px;
  padding: 6px 10px;
}
```

如果变量名不存在，使用文件中已有的相近变量替换。

- [x] **步骤 6：运行测试验证通过**

运行：

```bash
cd desktop && npm test -- src/test/App.test.tsx
```

预期：`App.test.tsx` 全部通过。

- [x] **步骤 7：Commit**

```bash
git add desktop/src/App.tsx desktop/src/components/InspectorPanel.tsx desktop/src/App.css desktop/src/test/App.test.tsx
git commit -m "feat(桌面端): 增强运行态提醒操作中心"
```

## 任务 5：文档、全量验证和浏览器验证

**文件：**
- 修改：`docs/development/local-run.md`
- 修改：`docs/superpowers/plans/2026-06-25-matrixcode-runtime-notification-actions.md`

- [x] **步骤 1：更新本地运行文档**

在 `docs/development/local-run.md` 的运行态验证记录附近加入：

```markdown
### 第十三阶段：运行态提醒操作中心验证

- 服务端新增当前项目运行态提醒全部已读接口：`POST /api/projects/{projectId}/runtime-notifications/read-all`。
- 右侧「运行态提醒」显示未读数量，支持「全部」和「未读」视图。
- 点击「全部已读」后，顶部提醒消失，右侧记录保留并显示「已读」。
- 浏览器验证生成两条未读提醒后，确认「未读 2」、未读筛选、全部已读、刷新保持已读状态和控制台无 error。
```

- [x] **步骤 2：运行全量服务端测试**

运行：

```bash
/Users/Masons/Ai/Maven/bin/mvn -Dmaven.repo.local=/Users/Masons/Ai/Maven_Ai_Store -q -pl server test
```

预期：服务端测试全部通过，输出无失败和错误。

- [x] **步骤 3：运行全量桌面端测试**

运行：

```bash
cd desktop && npm test
```

预期：桌面端测试全部通过。

- [x] **步骤 4：运行桌面端构建**

运行：

```bash
cd desktop && npm run build
```

预期：类型检查和 Vite 构建退出码为 0。

- [x] **步骤 5：运行 Tauri 命令检查**

运行：

```bash
cd desktop && npm run tauri:build -- --help
```

预期：命令退出码为 0。

- [x] **步骤 6：运行文档和 diff 检查**

运行：

```bash
rg -n "T(O)DO|T[B]D|F[I]XME|待[定]|占[位]|place(holder)|S[u]mmary|G[o]als|Acceptance C[r]iteria" docs/superpowers/specs/2026-06-25-matrixcode-runtime-notification-actions-design.md docs/superpowers/plans/2026-06-25-matrixcode-runtime-notification-actions.md docs/development/local-run.md
rg --pcre2 -n "(?<!bin/)\\bm[v]n\\b" docs/superpowers/specs/2026-06-25-matrixcode-runtime-notification-actions-design.md docs/superpowers/plans/2026-06-25-matrixcode-runtime-notification-actions.md docs/development/local-run.md
git diff --check
```

预期：前两个搜索命令无匹配，`git diff --check` 退出码为 0。

- [x] **步骤 7：浏览器验证**

执行过程：

1. 启动服务端和 Vite。
2. 打开桌面页。
3. 通过 API 提交一条待审批命令，页面显示顶部「需要审批本地命令」，右侧显示「未读 1」。
4. 批准命令并提交一条会成功的本地命令，等待运行完成后右侧显示「未读 2」。
5. 点击「未读」，确认只显示未读提醒。
6. 点击「全部已读」，确认顶部提醒消失，未读数量变成 0。
7. 点击「全部」，确认两条提醒保留并显示「已读」。
8. 刷新页面，确认已读状态不回退。
9. 检查浏览器控制台没有 error。
10. 停止服务端和 Vite，确认 8080 和 5173 端口释放。

- [x] **步骤 8：记录验证证据并提交文档**

把命令、退出码、测试数量、浏览器验证现象写入本计划的执行记录区域。然后提交：

```bash
git add docs/development/local-run.md docs/superpowers/plans/2026-06-25-matrixcode-runtime-notification-actions.md
git commit -m "docs: 记录第十三阶段运行态提醒验证"
```

## 执行记录

执行阶段按任务逐项更新这里，记录每个验证命令的退出码和关键输出。

### 2026-06-25 第十三阶段验证

- 服务端单元测试红灯：`/Users/Masons/Ai/Maven/bin/mvn -Dmaven.repo.local=/Users/Masons/Ai/Maven_Ai_Store -q -pl server -Dtest=RuntimeNotificationServiceTest test` 首次退出码 1，失败原因为 `markAllRead(String)` 未定义。
- 服务端单元测试绿灯：同一命令退出码 0；报告 `RuntimeNotificationServiceTest` 为 6 条测试，0 失败，0 错误，0 跳过。
- 服务端接口红灯：`/Users/Masons/Ai/Maven/bin/mvn -Dmaven.repo.local=/Users/Masons/Ai/Maven_Ai_Store -q -pl server -Dtest=WorkbenchControllerTest test` 首次退出码 1，新增批量已读接口场景返回 404。
- 服务端接口绿灯：同一命令退出码 0；报告 `WorkbenchControllerTest` 为 11 条测试，0 失败，0 错误，0 跳过。
- 桌面端 API 红灯：`cd desktop && npm test -- src/api/client.test.ts` 首次退出码 1，失败原因为 `markAllRuntimeNotificationsRead` 未导出且未监听 `RUNTIME_NOTIFICATIONS_READ`。
- 桌面端 API 绿灯：同一命令退出码 0；`client.test.ts` 为 29 条测试通过。
- 桌面端交互红灯：`cd desktop && npm test -- src/test/App.test.tsx` 首次退出码 1，缺少未读数量、筛选按钮和「全部已读」按钮。
- 桌面端交互绿灯：同一命令退出码 0；`App.test.tsx` 为 34 条测试通过。
- 服务端全量验证：`/Users/Masons/Ai/Maven/bin/mvn -Dmaven.repo.local=/Users/Masons/Ai/Maven_Ai_Store -q -pl server test` 退出码 0；Surefire 汇总为 212 条测试，0 失败，0 错误，0 跳过。
- 桌面端全量验证：`cd desktop && npm test` 退出码 0；3 个测试文件、69 条测试通过。
- 桌面端构建：`cd desktop && npm run build` 退出码 0；`tsc --noEmit` 和 `vite build` 完成。
- Tauri 命令检查：`cd desktop && npm run tauri:build -- --help` 退出码 0。
- 文档扫描：红旗词扫描无匹配，裸命令扫描无匹配，`git diff --check` 退出码 0。
- 浏览器验证：启动服务端 8080 和 Vite 5173 后，通过 API 准备一条待审批 `git status` 和一条已完成 `sleep 3`。页面初始显示右侧「未读 2」，两条提醒分别为「本地命令执行成功」和「需要审批本地命令」。点击「未读」后筛选按钮处于选中状态；点击「全部已读」后顶部提醒消失，右侧未读数量为 0，未读视图显示「暂无未读提醒」。切回「全部」后两条提醒保留并显示「已读」；刷新页面后仍为未读 0 且顶部无提醒。浏览器控制台 error 日志为空数组。
- 资源清理：浏览器验证标签页已关闭；服务端和 Vite 已停止。`lsof -nP -iTCP:8080 -sTCP:LISTEN`、`lsof -nP -iTCP:5173 -sTCP:LISTEN`、`pgrep -fl 'vite --host 127.0.0.1|spring-boot:run|docker compose|docker-credential-desktop'` 均无输出。

### 2026-06-25 回溯验收

- 结论：第十三阶段已完成并验证；历史 checklist 已按执行记录回填为完成状态。
- 初始需求对齐：本阶段增强运行态提醒的处理能力，支撑多人协作控制台里“未读聚焦”和“批量清理”的日常操作，未偏离阶段目标。
- 边界确认：本阶段仍是项目级提醒已读状态；用户级提醒视图依赖后续用户身份和项目成员权限。
