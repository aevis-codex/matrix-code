# MatrixCode 第十三阶段运行态提醒操作中心设计规格

## 背景

第十二阶段已经把运行态提醒升级为服务端内存收件箱，并支持关闭顶部提醒时写入已读状态。当前右侧「运行态提醒」列表仍偏展示型：用户可以看到未读和已读，但不能快速聚焦未读，也不能一次性处理多条提醒。

第十三阶段把右侧列表升级为轻量操作中心，解决“提醒多起来后需要快速清理和聚焦”的问题。范围控制在当前项目、当前服务进程内，不引入新的持久化设施。

## 目标

- 右侧提醒列表展示未读数量。
- 右侧列表支持「全部」和「未读」两个视图。
- 用户可以在右侧一次性把当前项目的提醒全部标记为已读。
- 全部已读后，顶部提醒消失，右侧列表保留提醒并显示「已读」。
- 旧的单条已读、SSE 刷新、前端兜底派生逻辑继续可用。

## 非目标

- 不新增数据库、文件落盘或跨服务重启恢复。
- 不做多用户独立已读状态。
- 不做提醒删除、批量删除或多项目聚合。
- 不做复杂搜索、按来源筛选、时间范围筛选或系统通知。
- 不改造第十二阶段的稳定提醒 ID 规则。

## 推荐方案

采用“服务端批量已读接口 + 桌面端受控筛选”的方案：

- 服务端在 `RuntimeNotificationService` 中新增 `markAllRead(projectId)`。
- 工作台控制器新增 `POST /api/projects/{projectId}/runtime-notifications/read-all`。
- 批量已读成功后发布 `RUNTIME_NOTIFICATIONS_READ` 事件，桌面端通过现有 SSE 刷新。
- 桌面端 `App` 持有提醒筛选状态：`all` 或 `unread`。
- `InspectorPanel` 接收筛选状态、未读数量、筛选切换回调和全部已读回调，保持组件可测试。

备选方案一是在右侧列表每条提醒增加单独已读按钮。它粒度更细，但会让列表变拥挤；第十二阶段顶部关闭已经覆盖单条场景。备选方案二是直接做数据库提醒表和用户偏好，能力更完整，但超出当前 MVP 操作闭环。当前推荐方案最小、直观，并且能复用现有内存收件箱。

## 服务端设计

`RuntimeNotificationService.markAllRead(projectId)`：

- 项目不存在或没有提醒时返回空列表。
- 项目存在时遍历全部提醒，未读提醒写入同一批次的 `readAt` 时间。
- 已读提醒保留原 `readAt`，保证幂等。
- 返回 `recent(projectId)`，便于接口调用方立即拿到最新列表。

`WorkbenchService.markAllRuntimeNotificationsRead(projectId)`：

- 校验项目编号。
- 调用提醒服务批量已读。
- 发布 `RUNTIME_NOTIFICATIONS_READ` 项目事件，消息为「运行态提醒已全部已读」。
- 返回最近提醒列表。

`WorkbenchController` 新增接口：

```text
POST /api/projects/{projectId}/runtime-notifications/read-all
```

接口语义：

- 成功返回最近提醒列表。
- 空项目返回空数组。
- 重复调用保持幂等，不更新已读提醒原有 `readAt`。

## 桌面端设计

`desktop/src/api/client.ts` 新增：

- `markAllRuntimeNotificationsRead(projectId, serverUrl)`。
- `runtimeSyncEventTypes` 增加 `RUNTIME_NOTIFICATIONS_READ`。

`desktop/src/App.tsx` 新增：

- `runtimeNotificationFilter` 状态，取值为 `all` 或 `unread`。
- `runtimeNotificationActionBusy` 状态，用于全部已读按钮防重复提交。
- `handleMarkAllRuntimeNotificationsRead()`：调用批量已读接口，成功后刷新工作台；失败时展示现有同步错误提示。
- 把筛选状态和操作回调传给 `InspectorPanel`。

`desktop/src/components/InspectorPanel.tsx` 调整：

- 顶部显示「未读 N」。
- 显示两个分段按钮：「全部」和「未读」。
- 「全部已读」按钮在未读数量为 0 时禁用。
- 当前筛选为「未读」且没有未读提醒时显示「暂无未读提醒」。
- 当前筛选为「全部」且没有提醒时继续显示「暂无运行态提醒」。

## 数据流

1. 工作台响应携带最近提醒。
2. App 计算未读数量，并按筛选状态生成展示列表。
3. 用户切换「全部」或「未读」，只影响前端展示，不请求服务端。
4. 用户点击「全部已读」，App 调用服务端批量已读接口。
5. 服务端写入未读提醒的 `readAt` 并发布 `RUNTIME_NOTIFICATIONS_READ`。
6. App 刷新工作台，顶部提醒消失，右侧列表保留已读提醒。

## 错误处理

- 批量已读接口对空项目返回空数组，不抛错。
- 桌面端批量已读失败时保留当前页面状态，并显示「同步最新工作台失败，请稍后重试」。
- 批量已读按钮提交期间禁用，避免重复点击。
- 旧服务端缺少提醒字段时，右侧列表仍显示前端派生提醒；批量已读按钮只在存在服务端提醒字段时启用。

## 测试策略

服务端测试：

- `RuntimeNotificationServiceTest` 覆盖批量已读会写入所有未读提醒。
- 覆盖重复批量已读不会覆盖已读提醒原有 `readAt`。
- `WorkbenchControllerTest` 覆盖批量已读接口返回已读提醒，并发布后续可被工作台读取。
- 覆盖空项目批量已读返回空数组。

桌面端测试：

- `client.test.ts` 覆盖批量已读 API 路径和 SSE 事件订阅。
- `App.test.tsx` 覆盖右侧未读数量、筛选切换和空未读状态。
- `App.test.tsx` 覆盖点击「全部已读」会调用 API、刷新工作台、顶部提醒消失。
- `App.test.tsx` 覆盖 API 失败时保留页面并显示同步错误。

浏览器验证：

- 生成两条未读提醒，右侧显示「未读 2」。
- 切换「未读」只显示未读提醒。
- 点击「全部已读」后顶部提醒消失，右侧未读数量变为 0。
- 切回「全部」仍能看到已读提醒。
- 控制台无 error，服务端和 Vite 停止后端口释放。

## 验收标准

- 服务端提供当前项目批量已读接口，且重复调用幂等。
- 桌面端右侧提醒列表支持全部和未读筛选。
- 全部已读会同步服务端状态，并通过刷新移除顶部未读提醒。
- 全部提醒已读后，右侧列表保留记录并显示已读状态。
- 服务端、桌面端测试和浏览器验证全部通过。

## 后续阶段入口

第十四阶段可继续评估提醒收件箱持久化，优先考虑项目级轻量存储或正式数据库表；也可以扩展用户级已读状态，让不同成员拥有独立提醒处理视图。
