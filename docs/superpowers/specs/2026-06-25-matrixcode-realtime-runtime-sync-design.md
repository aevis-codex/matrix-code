# MatrixCode 第十阶段运行态实时同步设计规格

## 背景

第七阶段引入本地长任务队列，第八阶段用轮询补齐运行中任务可见性，第九阶段把 Docker Compose 演示环境纳入工作台运行态。当前服务端已经有项目事件总线和 `/api/projects/{projectId}/events/stream` SSE 接口，后端也会在本地任务与 Compose 动作发生时发布事件。

第十阶段的目标不是重做实时系统，而是让桌面端订阅现有项目事件流，在运行态事件到达时自动刷新工作台。这样可以减少用户等待 2 秒轮询窗口的体感延迟，也为后续日志直连、审批提醒和多端协作打基础。

## 目标

- 桌面端启动并加载工作台后，连接当前项目的 SSE 事件流。
- 收到本地任务运行态事件时，自动刷新工作台，更新右侧本地执行代理卡片。
- 收到 Compose 配置或动作事件时，自动刷新工作台，更新运维面板与右侧 Compose 运行态。
- SSE 断开或浏览器不支持 EventSource 时，不阻断现有操作；本地任务轮询仍作为 fallback。
- 测试覆盖 API 客户端订阅行为和 App 收到事件后的自动刷新行为。

## 非目标

- 不引入 WebSocket。
- 不实现逐行日志流终端。
- 不改变服务端事件存储为数据库持久化。
- 不为所有业务事件做精细局部 patch 更新；第十阶段统一刷新工作台快照。
- 不新增用户设置页、权限模型或多项目切换 UI。

## 推荐方案

采用“现有命名 SSE 事件 + 桌面端事件类型白名单 + 工作台快照刷新”的方案。

备选方案一是继续只靠轮询，代码最少，但 Compose 动作和已完成任务不会即时反映。备选方案二是新增 WebSocket 双向通道，能力更强，但会扩大服务端协议、测试和异常处理范围。当前推荐方案复用已有 `ProjectEventController.stream`，改动集中在桌面端，服务端只补必要测试，风险最低。

## 事件范围

第十阶段桌面端监听以下事件类型：

- `LOCAL_COMMAND_QUEUED`
- `LOCAL_COMMAND_STARTED`
- `LOCAL_COMMAND_COMPLETED`
- `LOCAL_COMMAND_FAILED`
- `LOCAL_COMMAND_CANCELED`
- `COMPOSE_ENVIRONMENT_CONFIGURED`
- `COMPOSE_OPERATION_RECORDED`

这些事件都代表工作台运行态可能发生变化。收到事件后，桌面端调用 `loadProjectWorkbench(projectId)` 并保留当前角色页，不把用户切回 loading 全屏。

## 桌面端设计

`desktop/src/api/client.ts` 新增：

- `runtimeSyncEventTypes`：运行态事件类型白名单。
- `ProjectEventSubscription`：包含 `close()` 的订阅句柄。
- `subscribeProjectEvents(projectId, handlers, serverUrl)`：创建 `EventSource`，为白名单事件注册监听器，解析事件 JSON，并在关闭时移除监听器和关闭连接。

当浏览器环境没有 `EventSource` 时，订阅函数返回 no-op 句柄，并通过 `onUnsupported` 告知调用方。App 不展示错误，保留轮询 fallback。

`desktop/src/App.tsx` 在工作台首次 ready 后订阅当前项目事件。收到事件后调用：

```ts
void refreshWorkbench({ keepCurrent: true }).catch(() => undefined);
```

为避免事件风暴，App 侧做轻量去抖：同一个浏览器 tick 内多个运行态事件只触发一次刷新。第十阶段不做复杂批处理；如果后续要接入日志流，再单独设计。

## 服务端设计

服务端已经满足核心协议：

- `ProjectEventBus.publish(...)` 保存事件并通知订阅者。
- `ProjectEventController.stream(...)` 使用事件类型作为 SSE event name。
- `LocalTaskQueueService` 发布本地命令队列、开始、完成、失败、取消事件。
- `WorkbenchService` 发布 Compose 配置和动作记录事件。

第十阶段只补测试确认运行态事件类型通过 SSE 映射可被客户端按名称监听；不改变事件记录结构。

## 错误处理

- SSE 连接异常不弹阻断错误，不影响表单动作。
- 手动动作提交后仍会执行原有 `refreshWorkbench({ keepCurrent: true })`。
- 本地任务存在 `QUEUED` 或 `RUNNING` 时，保留第八阶段 2 秒轮询，作为 SSE 断开时的兜底。
- 如果事件 JSON 解析失败，忽略该事件并继续监听后续事件。

## 测试策略

- API 客户端测试：mock `EventSource`，验证白名单事件注册、JSON 解析、关闭清理和无 EventSource fallback。
- App 测试：首次加载工作台后触发 `LOCAL_COMMAND_COMPLETED` 事件，断言工作台自动刷新并展示终态任务。
- App 测试：触发 `COMPOSE_OPERATION_RECORDED` 事件，断言右侧 Compose 运行态更新。
- 服务端测试：确认本地任务和 Compose 事件类型仍能通过项目事件流发布。
- 全量验证：服务端测试、桌面测试、桌面 build、Tauri 命令入口、浏览器运行态验证。

## 验收标准

- 用户打开桌面端后，无需手动点击刷新即可接收运行态 SSE 事件。
- 本地任务完成事件到达后，右侧卡片能自动显示终态和日志。
- Compose 动作事件到达后，运维面板和右侧 Compose 运行态能自动显示最新结果。
- SSE 不可用时，页面不崩溃，本地任务轮询继续工作。
- 所有新增和既有测试通过，文档包含第十阶段验证路径。

## 后续阶段入口

第十一阶段可以继续扩展以下方向：

- 按任务 ID 订阅局部日志流。
- 对审批事件做桌面通知或待办提醒。
- 将项目事件流和运行态记录落入数据库。
- 增加多项目、多成员在线状态和断线重连状态展示。
