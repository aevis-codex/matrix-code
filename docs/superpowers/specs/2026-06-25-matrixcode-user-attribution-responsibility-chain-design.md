# 用户归属与责任链增强设计

## 背景

第 39 阶段已经完成项目成员 API 和桌面端配置入口。当前审批、本地执行和编码智能体已经有 `actorId` 或 `approverId`，但模型请求记录缺少操作者字段，运行态提醒已读也只有全局 `readAt`，无法回答“谁触发了模型请求”和“谁把提醒标为已读”。

## 目标

- 为模型请求增加 `actorUserId`，并在正式 MySQL 表 `matrixcode_model_requests` 中持久化。
- 为运行态提醒增加 `readByUserId`，复用现有 `matrixcode_runtime_notifications.user_id` 字段记录已读操作者。
- 前端 API client 支持传入模型请求操作者和提醒已读操作者。
- 保持现有本地执行审批与编码智能体 `actorId` 语义，不扩大执行权限。

## 非目标

- 不实现登录、注册、Token、SSO 或外部身份接入。
- 不改变现有审批策略、路径守卫和本地执行安全边界。
- 不引入 Redis 或 RocketMQ 业务依赖。
- 不做提醒按用户多份收件箱；本阶段只记录最后一次已读操作者。

## 方案

### 模型请求归属

`ModelRequestCommand` 和 `ModelRequestRecord` 增加可选 `actorUserId`。API body 支持 `actorUserId`，服务端归一化为空字符串兼容旧请求。正式表新增可空列 `actor_user_id`，有值时确保 `matrixcode_users` 存在，再写入模型请求。

### 提醒已读归属

`RuntimeNotification` 增加 `readByUserId`。`markRead` 和 `markAllRead` 新增 `actorUserId` 参数，有值时和 `readAt` 一起保存。旧无参数接口继续可用，使用空字符串兼容历史调用。

### 桌面端 API

`createRoleModelRequest` 的输入类型增加 `actorUserId`，提醒已读函数支持可选 `actorUserId` 并在有值时发送 JSON body。旧调用不传时仍发送原有请求。

## 验证

- 后端 TDD：
  - `ModelGatewayControllerTest` 断言模型请求后 `recentRequests[0].actorUserId` 等于请求体用户。
  - `WorkbenchControllerTest` 断言单条和批量提醒已读返回 `readByUserId`。
  - `JdbcProjectActivityRepositoryTest` 断言 `actorUserId` 可写入并恢复。
  - `RuntimeNotificationServiceTest` 断言重复同步保留 `readByUserId`。
- 前端 TDD：
  - `desktop/src/api/client.test.ts` 断言模型请求和提醒已读会发送用户归属。
- 回归：
  - 服务端相关测试、桌面端测试、构建、真实预检和真实集成。

## 回溯要求

第 40 阶段完成后必须更新 Obsidian `MatrixCode` 图谱，并回溯第 11、13、14、15、39 阶段：提醒已读、提醒操作中心、提醒持久化、本地执行持久化和项目成员配置入口应保持一致。
