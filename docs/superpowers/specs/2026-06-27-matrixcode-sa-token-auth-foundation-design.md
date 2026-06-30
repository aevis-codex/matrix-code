# MatrixCode Sa-Token 认证基座设计

## 背景

用户明确要求登录、token 和权限控制统一使用 Sa-Token。现有第 76 阶段的 HMAC actor token 已经为敏感接口提供过渡身份校验，但它不应继续扩展为正式登录体系。

## 目标

- 引入 Sa-Token Spring Boot 3 starter，作为正式登录态和 token 解析框架。
- 保留现有 `POST /api/projects/{projectId}/identity/auth/actor-token` 入口，但签发结果改为 Sa-Token token。
- `RequestActorResolver` 优先读取 Sa-Token 当前登录用户。
- 过渡期继续兼容旧 HMAC actor token 和 `X-MatrixCode-User-Id`，避免一次性破坏已有工作台链路。
- 增加强制 Sa-Token 配置，开启后拒绝旧 HMAC token 和裸身份头。

## 非目标

- 本阶段不做完整登录 UI。
- 本阶段不接入 Redis 分布式 Session 存储，只预留 Sa-Token 配置；后续 Redis 阶段再接。
- 本阶段不新增 DDL。
- 本阶段不删除旧 HMAC actor token 类和测试，避免破坏历史兼容链路。

## 技术方案

### 依赖与配置

- 后端新增 `cn.dev33:sa-token-spring-boot3-starter:1.45.0`。
- `application.yml` 新增 `sa-token` 基础配置：
  - token 名称使用 `Authorization`。
  - token 前缀使用 `Bearer`。
  - 允许从请求头读取，不从 body 或 cookie 读取。
  - 超时时间从环境变量读取，默认 86400 秒。
- `matrixcode.auth` 新增 `require-sa-token`，默认关闭。

### 请求身份解析

新增轻量适配接口 `SaTokenActorSession`：

- 生产实现通过 `StpUtil.isLogin()` 和 `StpUtil.getLoginIdAsString()` 读取当前登录用户。
- 单元测试使用 fake 实现，避免测试直接绑定 Sa-Token Web 上下文。

`RequestActorResolver` 解析顺序：

1. 如果 Sa-Token 当前已登录，返回 Sa-Token 用户 ID。
2. 如果同时存在 `X-MatrixCode-User-Id` 且与 Sa-Token 用户不一致，返回 403。
3. 如果开启 `require-sa-token` 且未登录，返回 401。
4. 兼容模式下继续回退旧 HMAC actor token。
5. 仍无 token 时回退 `X-MatrixCode-User-Id`。

### Token 签发

新增 `ActorTokenIssuer` 接口：

- 生产实现 `SaTokenActorTokenIssuer` 调用 `StpUtil.login(userId, ttlSeconds)` 后返回 `StpUtil.getTokenValue()`。
- 控制器只依赖接口，不直接调用静态工具，便于测试。
- 响应结构保持 `{ userId, token, expiresAt }`，桌面端无需立即改 API。

## 安全边界

- bootstrap token 仍只用于受控签发入口，不进入前端持久化。
- Sa-Token token 仍只保存在本地 `matrixcode.actorToken` 过渡存储位，后续登录 UI 可复用或迁移。
- 本阶段不把 token、密钥、数据库密码、模型密钥写入仓库、日志或 Obsidian。

## 验证

- `RequestActorResolverTest` 覆盖 Sa-Token 优先、Sa-Token 与用户头不一致 403、强制 Sa-Token 拒绝旧 token/header。
- `IdentityAuthControllerTest` 覆盖签发接口使用 Sa-Token issuer。
- `mvn -pl server -Dtest=RequestActorResolverTest,IdentityAuthControllerTest test`。
- 服务端全量、桌面端全量、桌面构建、真实运行检查、静态检查和敏感扫描。
