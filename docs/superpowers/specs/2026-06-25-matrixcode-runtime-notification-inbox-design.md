# MatrixCode 第十二阶段运行态提醒收件箱设计规格

## 背景

第十一阶段已经在桌面端从工作台快照派生运行态提醒，用户可以看到待审批、本地任务终态和 Compose 结果，并能关闭顶部提醒。当前限制是关闭状态只保存在浏览器会话内：刷新页面或重新打开桌面端后，同一条提醒会再次出现在顶部。

第十二阶段把提醒从“纯前端派生状态”升级为“服务端收件箱投影”。服务端根据工作台运行态生成稳定提醒记录，并保存已读状态。桌面端继续使用第十一阶段的展示形态，但提醒列表和顶部未读提醒来自服务端工作台字段。这样可以在不引入数据库的前提下，让提醒已读状态跨页面刷新保留。

## 目标

- 服务端维护项目级运行态提醒收件箱，提醒记录在当前服务进程内保留。
- 工作台响应包含最近运行态提醒，桌面端无需重新从快照派生主路径提醒。
- 用户关闭顶部提醒时，服务端把该提醒标记为已读；刷新页面后同一提醒不再作为顶部提醒出现。
- 右侧“运行态提醒”列表继续显示最近提醒，并标注未读或已读。
- 保留第十一阶段前端派生逻辑作为兼容兜底，避免旧服务端响应缺少提醒字段时白屏。
- 测试覆盖提醒同步、已读状态保留、桌面端关闭动作和 SSE 刷新后提醒更新。

## 非目标

- 不接入数据库、Redis、文件落盘或跨服务重启恢复。
- 不做系统级通知、通知权限偏好或声音提醒。
- 不做多用户独立已读状态；第十二阶段按项目共享已读状态处理。
- 不实现提醒删除、批量已读、多项目聚合收件箱。
- 不重构项目事件总线，也不把所有业务事件都转成提醒。

## 推荐方案

采用“服务端内存收件箱 + 工作台投影字段”的方案：

- 新增 `runtime` 或 `notification` 领域模块，集中管理 `RuntimeNotification`、提醒等级和 `RuntimeNotificationService`。
- `WorkbenchService.get(projectId)` 在聚合本地任务和 Compose 运行态后调用提醒服务同步收件箱。
- 提醒服务使用第十一阶段相同的稳定 ID 规则做 upsert，保留已读时间，不重复创建同一提醒。
- `ProjectWorkbench` 增加 `runtimeNotifications` 字段，返回最近 10 条未删除提醒。
- 新增已读接口：`POST /api/projects/{projectId}/runtime-notifications/{notificationId}/read`。
- 桌面端优先使用 `workbench.runtimeNotifications`；旧响应没有该字段时才使用本地派生函数。

备选方案一是直接上数据库提醒表，优点是接近生产形态，缺点是会把第十二阶段扩大为存储迁移和启动依赖改造。备选方案二是只用 `localStorage` 保存关闭 ID，改动最小，但无法为后续多端协作和服务端审计铺路。当前推荐方案处在两者之间，能解决刷新后重复提醒的问题，又保持实现可控。

## 提醒模型

服务端提醒记录字段：

- `id`：稳定提醒 ID。
- `projectId`：项目编号。
- `level`：`ACTION`、`SUCCESS`、`WARNING`、`ERROR`。
- `title`：中文标题。
- `message`：命令、操作摘要或失败原因。
- `sourceType`：`APPROVAL`、`LOCAL_TASK`、`COMPOSE_OPERATION`。
- `sourceId`：任务 ID 或 Compose 操作 ID。
- `occurredAt`：提醒发生时间。
- `readAt`：已读时间，未读时为 `null`。

稳定 ID 继续沿用第十一阶段规则：

- 待审批：`approval:<taskId>`
- 本地任务终态：`local-task:<taskId>:<status>`
- Compose 操作：`compose:<operationId>:<status>`

提醒排序：

1. `occurredAt` 倒序。
2. 同一时间按等级权重排序：`ACTION` > `ERROR` > `WARNING` > `SUCCESS`。
3. 同一时间同一等级按 ID 字典序排序，保证测试稳定。

收件箱保留策略：

- 每个项目最多保留 50 条提醒。
- 工作台只返回最近 10 条。
- 超过上限时移除最旧提醒。
- 已读提醒仍保留在列表中，只是不再作为顶部提醒候选。

## 服务端设计

新增文件：

- `server/src/main/java/com/matrixcode/runtime/domain/RuntimeNotification.java`
- `server/src/main/java/com/matrixcode/runtime/domain/RuntimeNotificationLevel.java`
- `server/src/main/java/com/matrixcode/runtime/domain/RuntimeNotificationSourceType.java`
- `server/src/main/java/com/matrixcode/runtime/application/RuntimeNotificationService.java`

`RuntimeNotificationService` 提供：

- `sync(projectId, localExecution, composeRuntimeViews)`：从工作台运行态同步提醒记录，返回最近提醒。
- `markRead(projectId, notificationId)`：标记已读并返回更新后的提醒。
- `recent(projectId)`：读取最近提醒。

`sync` 的输入使用已有工作台聚合对象，不直接依赖控制器请求。这样提醒生成逻辑只绑定到当前快照，不需要在每个业务动作里重复写提醒代码。

`WorkbenchService.get` 增加步骤：

1. 先聚合 `localExecution` 和 `composeRuntimeViews`。
2. 调用 `runtimeNotificationService.sync(projectId, localExecution, composeRuntimeViews)`。
3. 把返回结果放入 `ProjectWorkbench.runtimeNotifications`。

`WorkbenchController` 新增接口：

```text
POST /api/projects/{projectId}/runtime-notifications/{notificationId}/read
```

接口语义：

- 如果提醒存在且属于该项目，写入 `readAt`。
- 如果提醒已经已读，保持原 `readAt`，返回当前记录。
- 如果提醒不存在，返回 HTTP 400，错误信息包含 `运行态提醒不存在`。

标记已读后服务端发布 `RUNTIME_NOTIFICATION_READ` 项目事件，桌面端可通过现有 SSE 刷新工作台。

## 桌面端设计

`desktop/src/api/client.ts` 增加：

- `RuntimeNotification` 类型，与服务端字段对齐。
- `ProjectWorkbench.runtimeNotifications?: RuntimeNotification[]`，先做可选字段以兼容旧响应。
- `markRuntimeNotificationRead(projectId, notificationId)` API 方法。

`desktop/src/runtimeNotifications.ts` 调整：

- 保留第十一阶段的本地派生函数作为 fallback。
- 新增 `runtimeNotificationsFromWorkbench(workbench)`：优先返回 `workbench.runtimeNotifications`，缺失时走本地派生。
- `latestVisibleNotification` 改为优先选择 `readAt === null` 的提醒；对于 fallback 派生提醒，继续使用本地关闭 ID。

`desktop/src/App.tsx` 调整：

- 计算提醒时改用 `runtimeNotificationsFromWorkbench(workbench)`。
- 点击顶部关闭按钮时，先把当前提醒 ID 加入本地关闭集合，再调用 `markRuntimeNotificationRead`。
- 已读接口成功后刷新工作台；接口失败时保留本地关闭态，并显示现有同步错误提示，不阻断审批按钮。

`desktop/src/components/InspectorPanel.tsx` 调整：

- 右侧列表显示 `未读` 或 `已读` 状态。
- 未读提醒保持当前等级边框；已读提醒降低文字颜色，但仍可读。

## 数据流

1. 服务端工作台聚合本地任务、Compose 运行态和当前事件。
2. 提醒服务从聚合结果同步收件箱，稳定 ID 已存在时保留已读状态。
3. 工作台响应携带 `runtimeNotifications`。
4. 桌面端顶部提醒选择最近一条未读提醒。
5. 用户关闭顶部提醒，桌面端调用已读接口。
6. 服务端记录 `readAt` 并发布 `RUNTIME_NOTIFICATION_READ`。
7. 桌面端通过 SSE 刷新工作台；该提醒仍在右侧列表中，但顶部不再显示。

## 错误处理

- 服务端同步提醒时必须容忍空任务、空 Compose 运行态和缺失最新操作。
- 无效时间按最旧时间处理，不能导致工作台接口失败。
- 已读接口找不到提醒时返回 HTTP 400，不创建虚假提醒。
- 桌面端已读接口失败时，顶部提醒在当前会话内仍保持关闭，避免用户重复点击。
- 如果旧服务端响应没有 `runtimeNotifications` 字段，桌面端继续使用第十一阶段派生逻辑。

## 测试策略

服务端测试：

- `RuntimeNotificationServiceTest` 覆盖待审批、本地成功、本地失败、取消、Compose 成功、Compose 失败提醒同步。
- 覆盖同一稳定 ID 重复同步不会覆盖 `readAt`。
- 覆盖每项目最多保留 50 条，最近列表返回 10 条。
- `WorkbenchControllerTest` 覆盖工作台响应包含 `runtimeNotifications`。
- `WorkbenchControllerTest` 覆盖已读接口能写入 `readAt`，重复已读幂等，不存在 ID 返回 HTTP 400。

桌面端测试：

- `runtimeNotifications.test.ts` 覆盖优先使用服务端提醒，旧响应缺失时 fallback。
- `App.test.tsx` 覆盖关闭顶部提醒会调用 `markRuntimeNotificationRead`。
- `App.test.tsx` 覆盖刷新后已读提醒不再作为顶部提醒出现，但仍留在右侧列表。
- `App.test.tsx` 覆盖 SSE 收到 `RUNTIME_NOTIFICATION_READ` 后自动刷新。

浏览器验证：

- 提交 `git status` 待审批命令，页面出现未读提醒。
- 关闭顶部提醒，刷新页面后顶部不再显示同一提醒。
- 右侧提醒列表仍显示该提醒，并标记为已读。
- 提交并批准 `sleep 3`，成功提醒作为新的未读提醒出现。
- 控制台无 error，服务端和 Vite 停止后端口释放。

## 验收标准

- 工作台接口返回服务端生成的 `runtimeNotifications`。
- 关闭顶部提醒会调用服务端已读接口。
- 同一提醒刷新页面后不再作为顶部提醒出现。
- 已读提醒仍在右侧提醒列表中可见，并显示已读状态。
- 新的运行态提醒仍能通过 SSE 刷新后出现。
- 所有新增和既有服务端、桌面端测试通过，文档包含第十二阶段验证路径。

## 后续阶段入口

第十三阶段可以继续扩展以下方向：

- 把运行态提醒收件箱落到数据库，支持服务重启恢复。
- 引入用户级已读状态，区分不同成员的提醒处理。
- 增加批量已读、过滤和多项目聚合提醒。
- 接入系统级通知和通知偏好。
