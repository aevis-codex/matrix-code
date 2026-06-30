# MatrixCode Sa-Token 登录 API 设计

## 背景

第 82 阶段已经把 token 发行和请求身份解析基座切到 Sa-Token。当前桌面端身份页仍叫「身份令牌」，且服务端只有历史 `/actor-token` 签发路径。为了进入真实登录权限控制，需要提供更明确的登录、登出和会话查询 API。

## 目标

- 新增 `POST /api/projects/{projectId}/identity/auth/login`。
- 新增 `POST /api/projects/{projectId}/identity/auth/logout`。
- 新增 `GET /api/projects/{projectId}/identity/auth/session`。
- 登录仍要求用户是项目成员，且用 bootstrap 凭证保护，作为没有账号密码表前的最小可信登录方式。
- 登出和会话查询复用 `RequestActorResolver`，因此可走 Sa-Token 登录态，也可在兼容模式下临时使用旧身份头。

## 非目标

- 本阶段不新增账号密码表。
- 本阶段不改桌面端 UI。
- 本阶段不接 Redis 分布式 Session。
- 本阶段不删除历史 `/actor-token` 路径。

## API 设计

### 登录

```http
POST /api/projects/{projectId}/identity/auth/login
X-MatrixCode-Bootstrap-Token: <bootstrap-token>
Content-Type: application/json

{"userId":"user-dev","ttlSeconds":86400}
```

响应沿用现有 token 结构：

```json
{"userId":"user-dev","token":"<sa-token>","expiresAt":"2026-06-28T00:00:00Z"}
```

### 登出

```http
POST /api/projects/{projectId}/identity/auth/logout
Authorization: Bearer <sa-token>
X-MatrixCode-User-Id: user-dev
```

响应：`204 No Content`。

### 当前会话

```http
GET /api/projects/{projectId}/identity/auth/session
Authorization: Bearer <sa-token>
X-MatrixCode-User-Id: user-dev
```

响应：

```json
{"authenticated":true,"userId":"user-dev"}
```

## 安全边界

- 登录接口继续不保存 bootstrap token。
- 登出接口先解析当前身份，再调用 Sa-Token 登出适配器。
- 会话接口只返回低敏用户 ID，不返回 token、权限列表或密钥。
- 本阶段不新增 DDL。

## 验证

- `IdentityAuthControllerTest` 覆盖登录成功、登出调用终止器、会话返回当前用户。
- 目标测试、服务端全量、真实运行检查、静态检查和敏感扫描。
