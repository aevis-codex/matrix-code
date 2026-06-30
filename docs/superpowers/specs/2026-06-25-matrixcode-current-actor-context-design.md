# 当前操作者上下文设计

## 背景

第 40 阶段已经让模型请求和运行态提醒 API 支持用户归属字段，但桌面端主工作区仍有部分操作使用固定 actor 常量或不传 actor。多人实时协作控制台需要在用户执行操作时有清晰的当前操作者上下文。

## 目标

- 桌面端从项目成员 API 加载成员列表。
- 侧边栏提供当前操作者选择入口。
- 当前角色有项目成员时，默认使用该角色成员；没有成员时使用角色默认用户 ID 兜底。
- 已有 actor 字段的工作台操作改用当前操作者，包括提醒已读、本地审批/取消、开发编码智能体和运维运行态动作。
- 保持无登录认证、无 Token、无权限扩展。

## 非目标

- 不做完整登录、注册、SSO 或权限系统。
- 不改变服务端审批策略、本地执行路径守卫或命令安全边界。
- 不新增 Redis/RocketMQ 业务依赖。
- 不强制项目必须预先配置所有角色成员。

## 方案

### 桌面端状态

`App` 的 ready state 增加 `projectMembers`。刷新工作台时，在拿到 `projectId` 后并行加载角色智能体配置和项目成员。成员加载失败时退化为空列表，不阻断主工作台。

### 当前操作者解析

当前操作者解析顺序：

1. 用户手动选择并存入 `localStorage` 的 `matrixcode.currentActorUserId`。
2. 当前角色对应的项目成员，例如开发角色优先 `DEVELOPER` 成员。
3. 角色默认用户 ID：产品 `user-product`、开发 `user-dev`、测试 `user-tester`、运维 `user-ops`。

### 操作接入

以下已有 actor 字段改为使用当前操作者：

- `markRuntimeNotificationRead` 和 `markAllRuntimeNotificationsRead`。
- `decideLocalCommandApproval` 和 `cancelLocalExecutionTask`。
- `prepareCodingAgentExecution`、`applyCodingAgentPatch`、`recordCodingAgentHandoff`。
- `runDeploymentHealthCheck`、`recordDeploymentOperation`、`validate/start/stop/captureLogs` Compose 动作。

## 验证

- 桌面端测试：当前操作者选择渲染、切换后提醒已读带 `actorUserId`。
- 桌面端现有开发/运维测试仍应使用角色匹配或角色默认 actor。
- 桌面端全量测试和构建通过。
- 服务端无需新增迁移。
