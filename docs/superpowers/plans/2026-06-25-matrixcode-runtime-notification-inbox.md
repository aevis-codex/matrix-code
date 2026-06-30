# MatrixCode 第十二阶段运行态提醒收件箱实现计划

> **面向 AI 代理的工作者：** 必需子技能：使用 superpowers:subagent-driven-development（推荐）或 superpowers:executing-plans 逐任务实现此计划。步骤使用复选框（`- [ ]`）语法来跟踪进度。

**目标：** 把第十一阶段的运行态提醒升级为服务端内存收件箱，让顶部提醒关闭后跨页面刷新保持已读。

**架构：** 服务端新增运行态提醒领域模块，通过工作台聚合结果同步项目级提醒并保留 `readAt`。工作台响应携带最近提醒，桌面端优先使用服务端字段，旧响应缺字段时继续使用前端派生逻辑。关闭顶部提醒会调用已读接口，服务端发布 SSE 事件驱动工作台刷新。

**技术栈：** Java 21、Spring Boot、JUnit 5、AssertJ、MockMvc、TypeScript、React、Vitest、Testing Library、Vite、Tauri。

---

## 文件结构

- 创建：`server/src/main/java/com/matrixcode/runtime/domain/RuntimeNotification.java`，表示单条提醒记录和已读副本更新。
- 创建：`server/src/main/java/com/matrixcode/runtime/domain/RuntimeNotificationLevel.java`，定义 `ACTION`、`SUCCESS`、`WARNING`、`ERROR`。
- 创建：`server/src/main/java/com/matrixcode/runtime/domain/RuntimeNotificationSourceType.java`，定义 `APPROVAL`、`LOCAL_TASK`、`COMPOSE_OPERATION`。
- 创建：`server/src/main/java/com/matrixcode/runtime/application/RuntimeNotificationService.java`，负责同步提醒、保留已读状态、标记已读和最近列表。
- 创建：`server/src/test/java/com/matrixcode/runtime/RuntimeNotificationServiceTest.java`，覆盖提醒同步、已读保留和保留上限。
- 修改：`server/src/main/java/com/matrixcode/workbench/domain/ProjectWorkbench.java`，追加 `runtimeNotifications` 字段并复制列表。
- 修改：`server/src/main/java/com/matrixcode/workbench/application/WorkbenchService.java`，注入提醒服务，工作台聚合时同步提醒，新增标记已读方法。
- 修改：`server/src/main/java/com/matrixcode/workbench/api/WorkbenchController.java`，新增提醒已读接口。
- 修改：`server/src/test/java/com/matrixcode/workbench/WorkbenchServiceTest.java`，补齐构造注入并断言工作台提醒。
- 修改：`server/src/test/java/com/matrixcode/workbench/WorkbenchControllerTest.java`，覆盖工作台提醒字段和已读接口。
- 修改：`desktop/src/api/client.ts`，增加提醒类型、工作台字段、已读 API 和 SSE 类型。
- 修改：`desktop/src/api/client.test.ts`，覆盖已读 API 请求和 SSE 事件订阅。
- 修改：`desktop/src/runtimeNotifications.ts`，新增服务端优先函数并让顶部提醒跳过已读记录。
- 修改：`desktop/src/runtimeNotifications.test.ts`，覆盖服务端优先、旧响应兜底和已读跳过。
- 修改：`desktop/src/App.tsx`，关闭顶部提醒时本地关闭并调用服务端已读。
- 修改：`desktop/src/test/App.test.tsx`，覆盖关闭动作、刷新后顶部状态和 SSE 已读刷新。
- 修改：`desktop/src/components/InspectorPanel.tsx`，右侧列表显示未读或已读。
- 修改：`desktop/src/App.css`，补充提醒状态标签和已读样式。
- 修改：`docs/development/local-run.md`，记录第十二阶段本地验证路径。
- 修改：`docs/superpowers/plans/2026-06-25-matrixcode-runtime-notification-inbox.md`，执行后记录验证证据。

## 任务 1：服务端提醒收件箱

**文件：**
- 创建：`server/src/test/java/com/matrixcode/runtime/RuntimeNotificationServiceTest.java`
- 创建：`server/src/main/java/com/matrixcode/runtime/domain/RuntimeNotification.java`
- 创建：`server/src/main/java/com/matrixcode/runtime/domain/RuntimeNotificationLevel.java`
- 创建：`server/src/main/java/com/matrixcode/runtime/domain/RuntimeNotificationSourceType.java`
- 创建：`server/src/main/java/com/matrixcode/runtime/application/RuntimeNotificationService.java`

- [x] **步骤 1：编写失败测试**

在 `RuntimeNotificationServiceTest` 中新增 3 个测试：

```java
@Test
void 同步本地任务和Compose操作生成稳定提醒() {
    var service = new RuntimeNotificationService();

    var notifications = service.sync(
            "demo",
            localExecution(List.of(
                    task("task-approval", "git status", "APPROVAL_PENDING", null, "2026-06-25T06:00:00Z"),
                    task("task-success", "sleep 1", "SUCCESS", 0, "2026-06-25T06:01:00Z"),
                    task("task-failed", "exit 1", "FAILED", 1, "2026-06-25T06:02:00Z"),
                    canceledTask("task-canceled", "sleep 9", "2026-06-25T06:03:00Z")
            )),
            List.of(compose("compose-op-1", "FAILED", "Docker Compose 命令超时", "2026-06-25T06:04:00Z"))
    );

    assertThat(notifications).extracting("id")
            .containsExactly(
                    "compose:compose-op-1:FAILED",
                    "local-task:task-canceled:CANCELED",
                    "local-task:task-failed:FAILED",
                    "local-task:task-success:SUCCESS",
                    "approval:task-approval"
            );
    assertThat(notifications.getFirst().level()).isEqualTo(RuntimeNotificationLevel.ERROR);
}

@Test
void 重复同步保留已读时间() {
    var service = new RuntimeNotificationService();
    service.sync("demo", localExecution(List.of(task("task-1", "git status", "APPROVAL_PENDING", null, "2026-06-25T06:00:00Z"))), List.of());

    var read = service.markRead("demo", "approval:task-1");
    var afterSync = service.sync("demo", localExecution(List.of(task("task-1", "git status", "APPROVAL_PENDING", null, "2026-06-25T06:00:00Z"))), List.of());

    assertThat(afterSync.getFirst().readAt()).isEqualTo(read.readAt());
}

@Test
void 每个项目最多保留五十条且最近接口返回十条() {
    var service = new RuntimeNotificationService();

    service.sync("demo", localExecution(IntStream.range(0, 55)
            .mapToObj(index -> task("task-" + index, "echo " + index, "SUCCESS", 0, "2026-06-25T06:%02d:00Z".formatted(index)))
            .toList()), List.of());

    assertThat(service.allForTesting("demo")).hasSize(50);
    assertThat(service.recent("demo")).hasSize(10);
    assertThat(service.recent("demo").getFirst().id()).isEqualTo("local-task:task-54:SUCCESS");
}
```

测试 helper 使用现有领域对象构造真实任务和 Compose 视图。`localExecution(...)` 返回本地执行聚合对象，`task(...)` 使用 `ExecutionTaskStatus.valueOf(status)`，`compose(...)` 使用 `ComposeOperationStatus.valueOf(status)`。

- [x] **步骤 2：运行测试验证失败**

运行：

```bash
/Users/Masons/Ai/Maven/bin/mvn -Dmaven.repo.local=/Users/Masons/Ai/Maven_Ai_Store -q -pl server -Dtest=RuntimeNotificationServiceTest test
```

预期：编译失败，提示 `RuntimeNotificationService` 或 `RuntimeNotificationLevel` 找不到。

- [x] **步骤 3：实现最小服务端领域代码**

实现要点：

```java
public record RuntimeNotification(
        String id,
        String projectId,
        RuntimeNotificationLevel level,
        String title,
        String message,
        RuntimeNotificationSourceType sourceType,
        String sourceId,
        Instant occurredAt,
        Instant readAt
) {
    public RuntimeNotification markRead(Instant instant) {
        return readAt == null ? new RuntimeNotification(id, projectId, level, title, message, sourceType, sourceId, occurredAt, instant) : this;
    }
}
```

`RuntimeNotificationService` 使用 `ConcurrentHashMap<String, Map<String, RuntimeNotification>>` 保存项目提醒。`sync(...)` 只处理待审批、本地任务终态和带最新操作的 Compose 视图；稳定 ID 已存在时复用旧记录的 `readAt`。排序规则为时间倒序、等级权重倒序、ID 升序。保留上限为 50，`recent(...)` 返回前 10 条。

- [x] **步骤 4：运行测试验证通过**

运行同一步骤 2 的命令。预期：`RuntimeNotificationServiceTest` 全部通过。

- [x] **步骤 5：提交**

```bash
git add server/src/main/java/com/matrixcode/runtime server/src/test/java/com/matrixcode/runtime
git commit -m "feat(服务端): 管理运行态提醒收件箱"
```

## 任务 2：工作台响应和已读接口

**文件：**
- 修改：`server/src/main/java/com/matrixcode/workbench/domain/ProjectWorkbench.java`
- 修改：`server/src/main/java/com/matrixcode/workbench/application/WorkbenchService.java`
- 修改：`server/src/main/java/com/matrixcode/workbench/api/WorkbenchController.java`
- 修改：`server/src/test/java/com/matrixcode/workbench/WorkbenchServiceTest.java`
- 修改：`server/src/test/java/com/matrixcode/workbench/WorkbenchControllerTest.java`

- [x] **步骤 1：编写失败测试**

在 `WorkbenchServiceTest` 增加断言：提交待审批本地命令后，工作台返回 `runtimeNotifications`，第一条 ID 为 `approval:<taskId>`。

在 `WorkbenchControllerTest` 增加接口测试：

```java
@Test
void 工作台返回运行态提醒并支持标记已读() throws Exception {
    var workspaceResponse = mockMvc.perform(post("/api/projects/demo/local-execution/workspaces")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""
                            {"name":"当前项目","rootPath":"%s"}
                            """.formatted(workspace.toString())))
            .andExpect(status().isOk())
            .andReturn();
    var workspaceId = JsonPath.read(workspaceResponse.getResponse().getContentAsString(), "$.id").toString();
    var taskResponse = mockMvc.perform(post("/api/projects/demo/local-execution/workspaces/" + workspaceId + "/commands")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""
                            {"actorId":"user-ops","command":"git status"}
                            """))
            .andExpect(status().isOk())
            .andReturn();
    var taskId = JsonPath.read(taskResponse.getResponse().getContentAsString(), "$.taskId").toString();
    var notificationId = "approval:" + taskId;

    mockMvc.perform(get("/api/projects/demo/workbench"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.runtimeNotifications[0].id").value(notificationId))
            .andExpect(jsonPath("$.runtimeNotifications[0].readAt").doesNotExist());

    mockMvc.perform(post("/api/projects/demo/runtime-notifications/" + notificationId + "/read"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.id").value(notificationId))
            .andExpect(jsonPath("$.readAt").isNotEmpty());

    mockMvc.perform(get("/api/projects/demo/workbench"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.runtimeNotifications[0].readAt").isNotEmpty());
}
```

再增加一个不存在 ID 的接口测试，预期 HTTP 400 且消息包含 `运行态提醒不存在`。

- [x] **步骤 2：运行测试验证失败**

运行：

```bash
/Users/Masons/Ai/Maven/bin/mvn -Dmaven.repo.local=/Users/Masons/Ai/Maven_Ai_Store -q -pl server -Dtest=WorkbenchControllerTest,WorkbenchServiceTest test
```

预期：编译失败或 JSON 断言失败，原因是工作台尚未暴露提醒字段与已读接口。

- [x] **步骤 3：实现工作台聚合和接口**

实现要点：

```java
// ProjectWorkbench 构造参数末尾追加
List<RuntimeNotification> runtimeNotifications
```

`WorkbenchService` 构造函数注入 `RuntimeNotificationService`。`get(projectId)` 在得到 `localExecution` 和 `composeRuntimeViews` 后调用：

```java
var runtimeNotifications = runtimeNotificationService.sync(projectId, localExecution, composeRuntimeViews);
```

返回 `ProjectWorkbench` 时把 `runtimeNotifications` 放在 `events` 后。新增：

```java
public RuntimeNotification markRuntimeNotificationRead(String projectId, String notificationId) {
    requireText(projectId, "项目编号不能为空");
    notificationId = requireText(notificationId, "运行态提醒编号不能为空");
    var notification = runtimeNotificationService.markRead(projectId, notificationId);
    publish(projectId, "RUNTIME_NOTIFICATION_READ", "运行态提醒已读");
    return notification;
}
```

`WorkbenchController` 新增：

```java
@PostMapping("/runtime-notifications/{notificationId}/read")
public RuntimeNotification markRuntimeNotificationRead(
        @PathVariable String projectId,
        @PathVariable String notificationId
) {
    return service.markRuntimeNotificationRead(projectId, notificationId);
}
```

- [x] **步骤 4：运行测试验证通过**

运行同一步骤 2 的命令。预期：相关测试全部通过。

- [x] **步骤 5：提交**

```bash
git add server/src/main/java/com/matrixcode/workbench server/src/test/java/com/matrixcode/workbench
git commit -m "feat(服务端): 暴露运行态提醒已读接口"
```

## 任务 3：桌面 API 和提醒选择函数

**文件：**
- 修改：`desktop/src/api/client.ts`
- 修改：`desktop/src/api/client.test.ts`
- 修改：`desktop/src/runtimeNotifications.ts`
- 修改：`desktop/src/runtimeNotifications.test.ts`

- [x] **步骤 1：编写失败测试**

在 `client.test.ts` 增加：

```typescript
it('标记运行态提醒已读', async () => {
  const fetchMock = vi.fn().mockResolvedValue({
    ok: true,
    json: async () => ({
      id: 'approval:task-1',
      projectId: 'demo',
      level: 'ACTION',
      title: '需要审批本地命令',
      message: 'git status',
      sourceType: 'APPROVAL',
      sourceId: 'task-1',
      occurredAt: '2026-06-25T06:00:00Z',
      readAt: '2026-06-25T06:01:00Z'
    })
  });
  vi.stubGlobal('fetch', fetchMock);

  await markRuntimeNotificationRead('demo', 'approval:task-1', 'http://localhost:8080');

  expect(fetchMock).toHaveBeenCalledWith(
    'http://localhost:8080/api/projects/demo/runtime-notifications/approval%3Atask-1/read',
    { method: 'POST', headers: { Accept: 'application/json' } }
  );
});
```

在 SSE 测试中触发 `RUNTIME_NOTIFICATION_READ`，预期 `onEvent` 被调用。

在 `runtimeNotifications.test.ts` 增加：

```typescript
it('优先使用服务端运行态提醒', () => {
  const workbench = {
    ...baseWorkbench,
    runtimeNotifications: [
      {
        id: 'approval:task-1',
        projectId: 'demo',
        level: 'ACTION',
        title: '服务端提醒',
        message: 'git status',
        sourceType: 'APPROVAL',
        sourceId: 'task-1',
        occurredAt: '2026-06-25T06:00:00Z',
        readAt: null
      }
    ]
  };

  expect(runtimeNotificationsFromWorkbench(workbench)[0].title).toBe('服务端提醒');
});

it('跳过服务端已读提醒选择下一条未读提醒', () => {
  const notifications = [
    serverNotification('n-1', 'ERROR', '已读失败', '2026-06-25T06:00:00Z', '2026-06-25T06:01:00Z'),
    serverNotification('n-2', 'SUCCESS', '未读成功', '2026-06-25T05:00:00Z', null)
  ];

  expect(latestVisibleNotification(notifications, new Set())?.id).toBe('n-2');
});
```

- [x] **步骤 2：运行测试验证失败**

运行：

```bash
cd desktop && npm test -- src/api/client.test.ts src/runtimeNotifications.test.ts
```

预期：测试失败，提示 `markRuntimeNotificationRead` 或 `runtimeNotificationsFromWorkbench` 未导出，SSE 事件未订阅。

- [x] **步骤 3：实现客户端类型、API 和纯函数**

实现要点：

```typescript
export type RuntimeNotification = {
  id: string;
  projectId?: string;
  level: RuntimeNotificationLevel;
  title: string;
  message: string;
  sourceType?: 'APPROVAL' | 'LOCAL_TASK' | 'COMPOSE_OPERATION';
  sourceId?: string;
  occurredAt: string;
  readAt?: string | null;
};
```

`ProjectWorkbench` 追加 `runtimeNotifications?: RuntimeNotification[]`。`runtimeSyncEventTypes` 追加 `RUNTIME_NOTIFICATION_READ`。新增 API：

```typescript
export function markRuntimeNotificationRead(
  projectId: string,
  notificationId: string,
  serverUrl = matrixCodeServerUrl()
): Promise<RuntimeNotification> {
  return requestJson<RuntimeNotification>(
    projectUrl(serverUrl, projectId, `/runtime-notifications/${encodeURIComponent(notificationId)}/read`),
    { method: 'POST' }
  );
}
```

`runtimeNotifications.ts` 新增：

```typescript
export function runtimeNotificationsFromWorkbench(workbench: ProjectWorkbench): RuntimeNotification[] {
  if (Array.isArray(workbench.runtimeNotifications)) {
    return workbench.runtimeNotifications;
  }

  return deriveRuntimeNotifications(workbench);
}
```

`latestVisibleNotification` 对 `readAt` 有值的提醒直接跳过。

- [x] **步骤 4：运行测试验证通过**

运行同一步骤 2 的命令。预期：指定桌面测试全部通过。

- [x] **步骤 5：提交**

```bash
git add desktop/src/api/client.ts desktop/src/api/client.test.ts desktop/src/runtimeNotifications.ts desktop/src/runtimeNotifications.test.ts
git commit -m "feat(桌面端): 使用服务端运行态提醒"
```

## 任务 4：桌面顶部已读和右侧状态

**文件：**
- 修改：`desktop/src/App.tsx`
- 修改：`desktop/src/test/App.test.tsx`
- 修改：`desktop/src/components/InspectorPanel.tsx`
- 修改：`desktop/src/App.css`

- [x] **步骤 1：编写失败测试**

在 `App.test.tsx` 的 mock 中加入 `markRuntimeNotificationRead: vi.fn()`，基础工作台加入 `runtimeNotifications`。新增测试：

```typescript
it('关闭顶部运行态提醒时调用已读接口并刷新工作台', async () => {
  加载项目工作台
    .mockResolvedValueOnce({
      ...基础工作台,
      runtimeNotifications: [
        {
          id: 'approval:task-1',
          projectId: 'demo',
          level: 'ACTION',
          title: '需要审批本地命令',
          message: 'ssh prod systemctl restart app',
          sourceType: 'APPROVAL',
          sourceId: 'task-1',
          occurredAt: '2026-06-24T10:00:00Z',
          readAt: null
        }
      ]
    })
    .mockResolvedValueOnce({
      ...基础工作台,
      runtimeNotifications: [
        {
          id: 'approval:task-1',
          projectId: 'demo',
          level: 'ACTION',
          title: '需要审批本地命令',
          message: 'ssh prod systemctl restart app',
          sourceType: 'APPROVAL',
          sourceId: 'task-1',
          occurredAt: '2026-06-24T10:00:00Z',
          readAt: '2026-06-24T10:01:00Z'
        }
      ]
    });
  vi.mocked(markRuntimeNotificationRead).mockResolvedValue({} as never);

  render(<App />);

  const closeButton = await screen.findByRole('button', { name: '关闭提醒' });
  fireEvent.click(closeButton);

  await waitFor(() => expect(markRuntimeNotificationRead).toHaveBeenCalledWith('demo', 'approval:task-1'));
  await waitFor(() => expect(screen.queryByRole('status', { name: '运行态提醒' })).not.toBeInTheDocument());
  expect(screen.getByText('已读')).toBeInTheDocument();
});
```

新增右侧列表断言：未读提醒显示 `未读`，已读提醒显示 `已读`。

- [x] **步骤 2：运行测试验证失败**

运行：

```bash
cd desktop && npm test -- src/test/App.test.tsx
```

预期：测试失败，原因是 App 尚未调用已读 API，右侧列表尚未显示已读状态。

- [x] **步骤 3：实现 UI 行为**

`App.tsx` 导入 `markRuntimeNotificationRead` 和 `runtimeNotificationsFromWorkbench`。计算提醒时改为：

```typescript
const runtimeNotifications = runtimeNotificationsFromWorkbench(workbench);
```

新增关闭处理函数：

```typescript
async function handleDismissTopNotification(notificationId: string) {
  if (workbenchState.type !== 'ready') {
    return;
  }

  setDismissedNotificationIds((current) => new Set([...current, notificationId]));
  try {
    await markRuntimeNotificationRead(workbenchState.workbench.projectId, notificationId);
    await refreshWorkbench({ keepCurrent: true });
  } catch {
    setWorkbenchState((current) =>
      current.type === 'ready' ? { ...current, refreshing: false, syncError: syncFailureMessage } : current
    );
  }
}
```

关闭按钮调用 `void handleDismissTopNotification(topNotification.id)`。

`InspectorPanel.tsx` 在每条提醒里追加：

```tsx
<small className="notification-list__state">{notification.readAt ? '已读' : '未读'}</small>
```

`App.css` 给 `.notification-list__state` 和已读项增加低强调样式。

- [x] **步骤 4：运行测试验证通过**

运行同一步骤 2 的命令。预期：指定桌面测试全部通过。

- [x] **步骤 5：提交**

```bash
git add desktop/src/App.tsx desktop/src/test/App.test.tsx desktop/src/components/InspectorPanel.tsx desktop/src/App.css
git commit -m "feat(桌面端): 持久化顶部提醒已读状态"
```

## 任务 5：文档、全量验证和浏览器验证

**文件：**
- 修改：`docs/development/local-run.md`
- 修改：`docs/superpowers/plans/2026-06-25-matrixcode-runtime-notification-inbox.md`

- [x] **步骤 1：补充本地运行文档**

在 `docs/development/local-run.md` 的阶段验证章节增加第十二阶段说明，覆盖：

- 服务端工作台返回 `runtimeNotifications`。
- 关闭顶部提醒会写入 `readAt`。
- 刷新页面后同一提醒不再出现在顶部。
- 右侧列表仍显示提醒并标记已读。

- [x] **步骤 2：运行服务端全量测试**

运行：

```bash
/Users/Masons/Ai/Maven/bin/mvn -Dmaven.repo.local=/Users/Masons/Ai/Maven_Ai_Store -q -pl server test
```

预期：退出码 0，Surefire 汇总失败数为 0。

- [x] **步骤 3：运行桌面端测试和构建**

运行：

```bash
cd desktop && npm test
cd desktop && npm run build
cd desktop && npm run tauri:build -- --help
```

预期：三条命令退出码均为 0。

- [x] **步骤 4：运行文档和差异检查**

运行：

```bash
rg -n "T(O)DO|T[B]D|F[I]XME|待[定]|占[位]|place(holder)|S[u]mmary|G[o]als|Acceptance C[r]iteria" docs/superpowers/specs/2026-06-25-matrixcode-runtime-notification-inbox-design.md docs/superpowers/plans/2026-06-25-matrixcode-runtime-notification-inbox.md docs/development/local-run.md
rg --pcre2 -n "(?<!bin/)\\bm[v]n\\b" docs/superpowers/specs/2026-06-25-matrixcode-runtime-notification-inbox-design.md docs/superpowers/plans/2026-06-25-matrixcode-runtime-notification-inbox.md docs/development/local-run.md
git diff --check
```

预期：前两条命令无匹配输出，`git diff --check` 退出码 0。

- [x] **步骤 5：浏览器验证**

启动服务端和桌面开发服务器后，在浏览器完成以下动作：

- 通过 API 提交 `git status` 待审批命令。
- 确认顶部显示“需要审批本地命令”，右侧列表显示 `未读`。
- 点击关闭顶部提醒，刷新页面，确认顶部不再显示同一提醒。
- 确认右侧列表仍显示该提醒，并标记为 `已读`。
- 提交并批准 `sleep 3`，等待任务成功，确认新的成功提醒以 `未读` 出现。
- 检查浏览器控制台没有 error。
- 关闭浏览器验证页，停止服务端和 Vite，确认端口 8080 与 5173 没有监听进程。

- [x] **步骤 6：记录验证证据并提交**

把步骤 2 到步骤 5 的命令、退出码和浏览器验证结果写回本计划的“验证记录”章节，再提交：

```bash
git add docs/development/local-run.md docs/superpowers/plans/2026-06-25-matrixcode-runtime-notification-inbox.md
git commit -m "docs: 记录第十二阶段运行态提醒验证"
```

## 规格覆盖自检

- 服务端内存收件箱：任务 1 覆盖模型、同步、排序、保留上限和已读保留。
- 工作台响应字段：任务 2 覆盖 `ProjectWorkbench.runtimeNotifications`。
- 已读接口：任务 2 覆盖成功、幂等和不存在 ID 的 HTTP 400。
- 桌面端服务端优先：任务 3 覆盖 API 类型、已读请求、SSE 类型和旧响应兜底。
- 顶部提醒关闭后跨刷新保持已读：任务 4 和任务 5 覆盖组件测试与浏览器验证。
- 右侧列表继续显示最近提醒并标注状态：任务 4 覆盖 UI 和样式。
- 全量验证和文档：任务 5 覆盖服务端测试、桌面测试、构建、Tauri 帮助命令、文档扫描和浏览器验证。

## 验证记录

第十二阶段最终验证已在 2026-06-25 执行，证据如下：

- 服务端测试：`/Users/Masons/Ai/Maven/bin/mvn -Dmaven.repo.local=/Users/Masons/Ai/Maven_Ai_Store -q -pl server test`，退出码 0。Surefire 汇总：208 条测试，0 失败，0 错误，0 跳过。
- 桌面端测试：`cd desktop && npm test`，退出码 0。Vitest 汇总：3 个测试文件通过，64 条测试通过。
- 桌面端构建：`cd desktop && npm run build`，退出码 0，`tsc --noEmit` 和 `vite build` 均完成。
- Tauri 入口验证：`cd desktop && npm run tauri:build -- --help`，退出码 0，命令输出构建帮助信息。
- 文档红旗扫描：`rg -n "T(O)DO|T[B]D|F[I]XME|待[定]|占[位]|place(holder)|S[u]mmary|G[o]als|Acceptance C[r]iteria" ...`，无匹配输出。
- Maven 命令规范扫描：`rg --pcre2 -n "(?<!bin/)\\bm[v]n\\b" ...`，无匹配输出。
- 差异空白检查：`git diff --check`，退出码 0。
- 浏览器验证：服务端启动在 8080，Vite 启动在 5173。通过 API 提交 `git status` 待审批任务，工作台返回 `runtimeNotifications[0].readAt = null`。页面显示顶部「需要审批本地命令」、右侧「未读」和 `git status`。点击关闭顶部提醒后，顶部消失，右侧同一提醒显示「已读」。刷新页面后，同一提醒没有回到顶部，右侧仍保留「已读」。提交并批准 `sleep 3` 后，任务最终为 `SUCCESS`，页面显示新的「本地命令执行成功」和「未读」。浏览器控制台 error 日志为空。
- 清理验证：浏览器验证页已关闭；服务端和 Vite 已停止；`lsof -nP -iTCP:8080 -sTCP:LISTEN`、`lsof -nP -iTCP:5173 -sTCP:LISTEN`、`pgrep -fl 'docker compose|docker-credential-desktop|vite --host 127.0.0.1|spring-boot:run'` 均无输出。
