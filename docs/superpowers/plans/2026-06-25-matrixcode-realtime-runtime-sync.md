# MatrixCode 第十阶段运行态实时同步实现计划

> **面向 AI 代理的工作者：** 必需子技能：使用 superpowers:subagent-driven-development（推荐）或 superpowers:executing-plans 逐任务实现此计划。步骤使用复选框（`- [ ]`）语法来跟踪进度。

**目标：** 让桌面端订阅现有项目 SSE 事件流，并在本地任务或 Compose 运行态事件到达时自动刷新工作台。

**架构：** 服务端复用 `ProjectEventBus` 和 `/events/stream`，只补事件类型测试。桌面端在 API 客户端封装 `EventSource` 订阅，App 在 ready 状态下订阅当前项目，收到白名单运行态事件后保留当前页面并刷新工作台。

**技术栈：** Java 21、Spring Boot、JUnit 5、MockMvc、React、TypeScript、Vitest、Testing Library、浏览器 EventSource。

---

## 文件结构

- 修改：`server/src/test/java/com/matrixcode/realtime/ProjectEventStreamTest.java`
  - 覆盖运行态事件命名 SSE 映射。
- 修改：`desktop/src/api/client.ts`
  - 新增运行态事件白名单和 `subscribeProjectEvents`。
- 修改：`desktop/src/api/client.test.ts`
  - mock `EventSource`，覆盖订阅、解析、关闭和 fallback。
- 修改：`desktop/src/App.tsx`
  - ready 后订阅当前项目事件，收到运行态事件后刷新工作台。
- 修改：`desktop/src/test/App.test.tsx`
  - 验证本地任务和 Compose 事件触发自动刷新。
- 修改：`docs/development/local-run.md`
  - 增加第十阶段验证说明。
- 修改：`docs/superpowers/plans/2026-06-25-matrixcode-realtime-runtime-sync.md`
  - 记录红灯、绿灯、全量验证和浏览器验证结果。

## 任务 1：服务端 SSE 运行态事件测试

**文件：**
- 修改：`server/src/test/java/com/matrixcode/realtime/ProjectEventStreamTest.java`

- [x] **步骤 1：编写失败的服务端测试**

在 `ProjectEventStreamTest` 中新增测试，确认本地任务和 Compose 事件类型会作为项目事件保留，并且 stream 接口仍声明 `text/event-stream`：

```java
@Test
void 运行态事件可以通过项目事件流发布() throws Exception {
    var bus = new ProjectEventBus();
    bus.publish(new ProjectEvent("project-1", "LOCAL_COMMAND_COMPLETED", "任务运行完成，退出码：0"));
    bus.publish(new ProjectEvent("project-1", "COMPOSE_OPERATION_RECORDED", "运维启动了 Compose 演示环境"));
    var mockMvc = MockMvcBuilders.standaloneSetup(new ProjectEventController(bus)).build();

    mockMvc.perform(get("/api/projects/project-1/events"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$[0].type").value("LOCAL_COMMAND_COMPLETED"))
            .andExpect(jsonPath("$[1].type").value("COMPOSE_OPERATION_RECORDED"));

    var streamMapping = ProjectEventController.class
            .getMethod("stream", String.class)
            .getAnnotation(GetMapping.class);
    assertThat(streamMapping.produces()).contains(MediaType.TEXT_EVENT_STREAM_VALUE);
}
```

- [x] **步骤 2：运行测试验证红灯或确认现有能力**

运行：

```bash
/Users/Masons/Ai/Maven/bin/mvn -Dmaven.repo.local=/Users/Masons/Ai/Maven_Ai_Store -q -pl server -Dtest=ProjectEventStreamTest test
```

预期：如果现有服务端已满足能力，测试直接通过；记录为“能力确认绿灯”。如果失败，错误应指向事件查询或 stream 映射。

- [x] **步骤 3：最少服务端修复**

如果步骤 2 失败，只允许在 `ProjectEvent`、`ProjectEventBus` 或 `ProjectEventController` 内修复事件保留或 stream 映射，不改变业务模块发布事件的方式。

- [x] **步骤 4：运行服务端局部测试**

运行同一步骤 2 命令，预期退出码 0。

- [x] **步骤 5：提交服务端测试或修复**

运行：

```bash
git add server/src/test/java/com/matrixcode/realtime/ProjectEventStreamTest.java server/src/main/java/com/matrixcode/realtime/domain/ProjectEvent.java server/src/main/java/com/matrixcode/realtime/application/ProjectEventBus.java server/src/main/java/com/matrixcode/realtime/api/ProjectEventController.java
git commit -m "test: 覆盖运行态 SSE 事件发布"
```

如果 main 文件没有改动，`git add` 会只暂存测试文件。

## 任务 2：桌面 API 客户端订阅红绿

**文件：**
- 修改：`desktop/src/api/client.ts`
- 修改：`desktop/src/api/client.test.ts`

- [x] **步骤 1：编写失败的 API 客户端测试**

在 `desktop/src/api/client.test.ts` 新增 fake EventSource：

```ts
class FakeEventSource {
  static instances: FakeEventSource[] = [];
  listeners = new Map<string, Set<(event: MessageEvent<string>) => void>>();
  closed = false;

  constructor(public url: string) {
    FakeEventSource.instances.push(this);
  }

  addEventListener(type: string, listener: (event: MessageEvent<string>) => void) {
    const listeners = this.listeners.get(type) ?? new Set();
    listeners.add(listener);
    this.listeners.set(type, listeners);
  }

  removeEventListener(type: string, listener: (event: MessageEvent<string>) => void) {
    this.listeners.get(type)?.delete(listener);
  }

  close() {
    this.closed = true;
  }

  emit(type: string, data: unknown) {
    this.listeners.get(type)?.forEach((listener) => listener({ data: JSON.stringify(data) } as MessageEvent<string>));
  }
}
```

新增测试：

```ts
it('订阅项目运行态事件并在关闭时清理监听器', () => {
  vi.stubGlobal('EventSource', FakeEventSource);
  const onEvent = vi.fn();

  const subscription = subscribeProjectEvents('demo/项目', { onEvent }, 'http://localhost:8080/');
  const source = FakeEventSource.instances[0];

  expect(source.url).toBe('http://localhost:8080/api/projects/demo%2F%E9%A1%B9%E7%9B%AE/events/stream');
  source.emit('LOCAL_COMMAND_COMPLETED', {
    id: 'event-1',
    projectId: 'demo/项目',
    type: 'LOCAL_COMMAND_COMPLETED',
    message: '任务完成',
    occurredAt: '2026-06-25T00:00:00Z'
  });
  source.emit('UNRELATED_EVENT', { id: 'event-2' });

  expect(onEvent).toHaveBeenCalledTimes(1);
  subscription.close();
  expect(source.closed).toBe(true);
});
```

新增 fallback 测试：

```ts
it('EventSource不可用时返回空订阅并通知调用方', () => {
  vi.stubGlobal('EventSource', undefined);
  const onUnsupported = vi.fn();

  const subscription = subscribeProjectEvents('demo', { onEvent: vi.fn(), onUnsupported }, 'http://localhost:8080');

  subscription.close();
  expect(onUnsupported).toHaveBeenCalledOnce();
});
```

- [x] **步骤 2：运行测试验证红灯**

运行：

```bash
cd desktop && npm test -- src/api/client.test.ts
```

预期：失败，错误包含 `subscribeProjectEvents` 未导出。

- [x] **步骤 3：实现订阅 API**

在 `desktop/src/api/client.ts` 新增：

```ts
export const runtimeSyncEventTypes = [
  'LOCAL_COMMAND_QUEUED',
  'LOCAL_COMMAND_STARTED',
  'LOCAL_COMMAND_COMPLETED',
  'LOCAL_COMMAND_FAILED',
  'LOCAL_COMMAND_CANCELED',
  'COMPOSE_ENVIRONMENT_CONFIGURED',
  'COMPOSE_OPERATION_RECORDED'
] as const;

export type RuntimeSyncEventType = (typeof runtimeSyncEventTypes)[number];
export type ProjectEventSubscription = { close: () => void };

export function subscribeProjectEvents(
  projectId = 'demo',
  handlers: {
    onEvent: (event: ProjectEvent) => void;
    onError?: () => void;
    onUnsupported?: () => void;
  },
  serverUrl = matrixCodeServerUrl()
): ProjectEventSubscription {
  if (typeof EventSource === 'undefined') {
    handlers.onUnsupported?.();
    return { close: () => undefined };
  }

  const source = new EventSource(projectUrl(serverUrl, projectId, '/events/stream'));
  const listeners = new Map<RuntimeSyncEventType, (event: MessageEvent<string>) => void>();

  runtimeSyncEventTypes.forEach((type) => {
    const listener = (event: MessageEvent<string>) => {
      try {
        handlers.onEvent(JSON.parse(event.data) as ProjectEvent);
      } catch {
      }
    };
    listeners.set(type, listener);
    source.addEventListener(type, listener);
  });

  source.onerror = () => handlers.onError?.();

  return {
    close: () => {
      listeners.forEach((listener, type) => source.removeEventListener(type, listener));
      source.close();
    }
  };
}
```

- [x] **步骤 4：运行 API 客户端测试绿灯**

运行：

```bash
cd desktop && npm test -- src/api/client.test.ts
```

预期：测试通过。

- [x] **步骤 5：提交 API 客户端订阅**

运行：

```bash
git add desktop/src/api/client.ts desktop/src/api/client.test.ts
git commit -m "feat(桌面端): 封装项目运行态事件订阅"
```

## 任务 3：App 接入 SSE 自动刷新

**文件：**
- 修改：`desktop/src/App.tsx`
- 修改：`desktop/src/test/App.test.tsx`

- [x] **步骤 1：编写失败的 App 测试**

在 `desktop/src/test/App.test.tsx` 的 mock 区域加入 `subscribeProjectEvents` mock：

```ts
let projectEventHandler: ((event: ProjectEvent) => void) | null = null;
const subscribeProjectEventsMock = vi.fn((_projectId: string, handlers: { onEvent: (event: ProjectEvent) => void }) => {
  projectEventHandler = handlers.onEvent;
  return { close: vi.fn() };
});
```

新增测试一：

```ts
it('收到本地任务运行态事件后自动刷新工作台', async () => {
  loadProjectWorkbenchMock
    .mockResolvedValueOnce(workbenchWithRunningTask)
    .mockResolvedValueOnce(workbenchWithSuccessfulTask);

  render(<App />);
  expect(await screen.findByText(/运行中/)).toBeTruthy();

  act(() => {
    projectEventHandler?.({
      id: 'event-1',
      projectId: 'demo',
      type: 'LOCAL_COMMAND_COMPLETED',
      message: '任务运行完成',
      occurredAt: '2026-06-25T00:00:00Z'
    });
  });

  expect(await screen.findByText(/任务运行完成，退出码：0/)).toBeTruthy();
  expect(loadProjectWorkbenchMock).toHaveBeenCalledTimes(2);
});
```

新增测试二：

```ts
it('收到Compose运行态事件后自动刷新工作台', async () => {
  loadProjectWorkbenchMock
    .mockResolvedValueOnce(workbenchWithConfiguredCompose)
    .mockResolvedValueOnce(workbenchWithFailedCompose);

  render(<App />);
  expect(await screen.findByText(/已配置/)).toBeTruthy();

  act(() => {
    projectEventHandler?.({
      id: 'event-2',
      projectId: 'demo',
      type: 'COMPOSE_OPERATION_RECORDED',
      message: '运维启动了 Compose 演示环境',
      occurredAt: '2026-06-25T00:00:00Z'
    });
  });

  expect(await screen.findByText(/Docker Compose 命令超时/)).toBeTruthy();
  expect(loadProjectWorkbenchMock).toHaveBeenCalledTimes(2);
});
```

- [x] **步骤 2：运行测试验证红灯**

运行：

```bash
cd desktop && npm test -- src/test/App.test.tsx
```

预期：失败，原因是 App 尚未调用 `subscribeProjectEvents`。

- [x] **步骤 3：实现 App 订阅和轻量去抖**

在 `desktop/src/App.tsx` 导入 `subscribeProjectEvents`，新增常量：

```ts
const runtimeEventRefreshDelayMillis = 0;
```

在 ready 项目 ID 变化时订阅：

```ts
const readyProjectId = workbenchState.type === 'ready' ? workbenchState.workbench.projectId : null;

useEffect(() => {
  if (!readyProjectId) {
    return undefined;
  }

  let refreshScheduled = false;
  const subscription = subscribeProjectEvents(readyProjectId, {
    onEvent: () => {
      if (refreshScheduled) {
        return;
      }
      refreshScheduled = true;
      window.setTimeout(() => {
        refreshScheduled = false;
        void refreshWorkbench({ keepCurrent: true }).catch(() => undefined);
      }, runtimeEventRefreshDelayMillis);
    }
  });

  return () => subscription.close();
}, [readyProjectId]);
```

- [x] **步骤 4：运行 App 测试绿灯**

运行：

```bash
cd desktop && npm test -- src/test/App.test.tsx
```

预期：测试通过。

- [x] **步骤 5：提交 App 接入**

运行：

```bash
git add desktop/src/App.tsx desktop/src/test/App.test.tsx
git commit -m "feat(桌面端): 接入运行态 SSE 自动刷新"
```

## 任务 4：文档、全量验证和浏览器验证

**文件：**
- 修改：`docs/development/local-run.md`
- 修改：`docs/superpowers/plans/2026-06-25-matrixcode-realtime-runtime-sync.md`

- [x] **步骤 1：补充本地运行文档**

在 `docs/development/local-run.md` 第九阶段后新增第十阶段验证说明：

```markdown
## 第十阶段运行态实时同步验证

服务端和桌面端启动后，按第八阶段提交 `sleep 3` 本地任务并批准执行。桌面端右侧“本地执行代理”应在任务完成事件到达后自动显示成功日志；若 SSE 不可用，第八阶段轮询仍会刷新终态。

按第九阶段登记 Compose 环境并触发启动。桌面端右侧“Compose 运行态”应在 `COMPOSE_OPERATION_RECORDED` 事件到达后自动刷新，展示成功、失败或超时摘要。
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
rg -n "T(O)DO|T[B]D|F[I]XME|待[定]|占[位]|place(holder)|S[u]mmary|G[o]als|Acceptance C[r]iteria" docs/superpowers/specs/2026-06-25-matrixcode-realtime-runtime-sync-design.md docs/superpowers/plans/2026-06-25-matrixcode-realtime-runtime-sync.md
rg --pcre2 -n "(?<!bin/)\\bm[v]n\\b" docs/superpowers/specs/2026-06-25-matrixcode-realtime-runtime-sync-design.md docs/superpowers/plans/2026-06-25-matrixcode-realtime-runtime-sync.md docs/development/local-run.md
git diff --check
```

预期：无输出或退出码表示无匹配；差异检查通过。

- [x] **步骤 4：浏览器验证**

启动服务端和 Vite，打开 `http://127.0.0.1:5173/`：

- 授权本地工作区，提交 `sleep 3`，批准执行。
- 观察右侧“本地执行代理”自动显示终态。
- 登记 Compose 环境，触发启动。
- 观察右侧“Compose 运行态”自动显示最新摘要。
- 浏览器控制台无 error。

- [x] **步骤 5：记录验证并提交**

在本计划末尾新增“第十阶段验证记录”，记录红灯、绿灯、全量测试、构建、文档检查、浏览器验证和端口释放结果。

运行：

```bash
git add docs/development/local-run.md docs/superpowers/plans/2026-06-25-matrixcode-realtime-runtime-sync.md
git commit -m "docs: 记录第十阶段运行态实时同步验证"
```

## 自检记录

- 规格覆盖：运行态事件范围、桌面订阅、fallback、测试和验证路径均有任务覆盖。
- 类型一致性：计划使用 `ProjectEvent`、`runtimeSyncEventTypes`、`subscribeProjectEvents` 和 `ProjectEventSubscription`，与桌面端命名保持一致。
- 范围控制：第十阶段不引入 WebSocket、逐行日志终端、数据库持久化或多项目切换 UI。
- TDD 顺序：服务端、API 客户端和 App 接入均先写测试，再写实现。

## 第十阶段验证记录

**提交记录：**

- `fc3d894 docs: 规划第十阶段运行态实时同步`
- `306d026 docs: 编写第十阶段运行态实时同步计划`
- `93da06f test: 覆盖运行态 SSE 事件发布`
- `abb49b4 feat(桌面端): 封装项目运行态事件订阅`
- `da56594 feat(桌面端): 接入运行态 SSE 自动刷新`
- `13842b1 fix(桌面端): 稳定 Compose 环境默认选择`

**红绿记录：**

- 服务端 `ProjectEventStreamTest` 新增运行态事件测试后，现有 SSE 能力直接通过，记录为能力确认绿灯。
- API 客户端红灯：`npm test -- src/api/client.test.ts` 失败，原因是 `subscribeProjectEvents` 尚未导出。
- API 客户端绿灯：`npm test -- src/api/client.test.ts` 通过，1 个文件 27 个测试。
- App 红灯：`npm test -- src/test/App.test.tsx` 失败，原因是 App 尚未调用 `subscribeProjectEvents`。
- App 绿灯：`npm test -- src/test/App.test.tsx` 通过，1 个文件 25 个测试。
- 全量桌面验证中发现 Compose 环境刷新后默认选择存在短暂空值，补充 `13842b1` 后目标用例和桌面全量测试均通过。

**全量验证：**

- 服务端：`/Users/Masons/Ai/Maven/bin/mvn -Dmaven.repo.local=/Users/Masons/Ai/Maven_Ai_Store -q -pl server test` 退出码 0；Surefire 汇总 `Tests run: 201, Failures: 0, Errors: 0, Skipped: 0`。
- 桌面端：`npm test` 退出码 0；2 个测试文件、52 个测试通过。
- 构建：`npm run build` 退出码 0，`tsc --noEmit` 和 `vite build` 均通过。
- Tauri：`npm run tauri:build -- --help` 退出码 0。

**浏览器验证：**

- 启动服务端和 Vite 后打开 `http://127.0.0.1:5173/`。
- API 创建并批准 `sleep 3` 待审批任务；页面在不手动刷新的情况下通过 SSE 自动显示 `最近命令 成功 · ALLOW · sleep 3` 和 `任务运行完成，退出码：0`。
- API 登记 Compose 演示环境；页面通过 `COMPOSE_ENVIRONMENT_CONFIGURED` 自动显示 `matrixcode-demo · web · 已配置`。
- API 触发 Compose 启动；本机 Docker 凭证助手路径按预期返回 `Docker Compose 命令超时`，页面通过 `COMPOSE_OPERATION_RECORDED` 自动显示失败摘要和 `Image nginx:alpine Pulling` 日志摘录。
- 浏览器控制台 `error` 日志为 `[]`。
- 验证后 `pgrep -fl 'docker compose|docker-credential-desktop'` 无输出，未残留 Compose 或凭证助手进程。
- 验证服务已停止；`lsof -nP -iTCP:8080 -sTCP:LISTEN` 和 `lsof -nP -iTCP:5173 -sTCP:LISTEN` 均无输出。
