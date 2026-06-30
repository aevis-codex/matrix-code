# MatrixCode 签名身份令牌设计

## 背景

第 75 阶段已经把用户责任审计接口从裸查询参数收紧为服务端校验 `X-MatrixCode-User-Id`。但该请求头仍由桌面端直接传入，不能证明调用方确实拥有该用户身份。第 76 阶段需要补一个可上线的过渡身份凭证：服务端签发、服务端验证、可过期、可逐步强制启用。

## 目标

- 增加 HMAC 签名 actor token，令牌中包含用户 ID 和过期时间。
- `RequestActorResolver` 优先解析 `Authorization: Bearer <token>`。
- 当开启强制签名模式时，裸 `X-MatrixCode-User-Id` 不再被接受。
- 当令牌用户和 `X-MatrixCode-User-Id` 不一致时返回 403。
- 提供受 bootstrap token 保护的签发接口，便于上线部署时生成用户令牌。
- 桌面端读取用户责任审计时，如果本地配置了 actor token，则自动携带 `Authorization`。

## 非目标

- 不实现完整密码登录、OAuth、SSO、刷新令牌或用户邀请流程。
- 不新增数据库表；本阶段令牌为无状态 HMAC 令牌。
- 不把所有接口一次性切换为强制认证，避免破坏已有工作台主链路。
- 不在仓库、日志、图谱或测试快照中保存真实密钥。

## 推荐方案

新增 `matrixcode.auth` 配置：

- `require-signed-actor-token`：是否强制请求使用签名 actor token，默认关闭。
- `actor-token-secret`：HMAC 签名密钥，仅从环境变量注入。
- `bootstrap-token`：签发接口的 bootstrap 令牌，仅从环境变量注入。
- `default-token-ttl-seconds`：默认令牌有效期。

签名格式：

```text
v1.<base64url(userId)>.<expiresAtEpochSecond>.<base64url(hmacSha256(payload, secret))>
```

`RequestActorResolver` 解析顺序：

1. 如果存在 `Authorization: Bearer <token>`，验证签名和过期时间。
2. 如果同时存在 `X-MatrixCode-User-Id`，要求它与令牌用户一致。
3. 如果没有 token 且 `require-signed-actor-token=true`，返回 401。
4. 如果没有 token 且未强制签名，保留第 75 阶段的请求头兼容模式。

签发接口：

```text
POST /api/projects/{projectId}/identity/auth/actor-token
X-MatrixCode-Bootstrap-Token: <bootstrap-token>
```

请求体包含 `userId` 和可选 `ttlSeconds`。服务端要求目标用户是项目成员，避免为无关用户签发项目身份令牌。

## 测试策略

- `SignedActorTokenServiceTest`：
  - 可签发并验证令牌。
  - 篡改令牌会失败。
  - 过期令牌会失败。
- `RequestActorResolverTest`：
  - 签名令牌可解析当前用户。
  - 强制签名模式拒绝裸用户头。
  - 令牌用户和用户头不一致返回 403。
  - 非强制模式保留第 75 阶段兼容行为。
- `IdentityAuthControllerTest`：
  - bootstrap token 正确且用户是成员时签发令牌。
  - 缺少 bootstrap token 返回 401。
  - 非成员用户返回 403。
- 桌面端 `client.test.ts`：
  - 本地存在 actor token 时，用户责任审计请求带 `Authorization: Bearer ...`。

## 回溯对齐

- 对齐最初需求：多人实时协作控制台必须把用户责任从前端选择器升级为可信身份上下文。
- 对齐第 38 到 41 阶段：复用用户和项目成员基础，不创建重复身份模型。
- 对齐第 75 阶段：用户责任审计接口继续使用 `RequestActorResolver`，但从可伪造请求头升级到可验证令牌。
- 对齐上线目标：先提供可配置、可过期、可强制启用的过渡凭证；后续再接入完整登录和成员邀请。
