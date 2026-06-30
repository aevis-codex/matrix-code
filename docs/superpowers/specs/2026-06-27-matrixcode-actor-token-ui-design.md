# MatrixCode 身份令牌配置入口设计

## 背景

第 76 阶段已经完成服务端签名 actor token、bootstrap token 签发接口和责任审计 Bearer token 透传。当前剩余缺口是桌面端没有可操作入口，用户仍只能手工写入 `localStorage.matrixcode.actorToken`。这不符合真实上线前的最小可用标准。

## 推荐方案

采用「配置中心身份页」方案：

- 在现有项目配置弹窗中新增「身份」页签，保持入口集中，不扩大主工作区布局。
- 用户选择项目成员、输入一次性 bootstrap token 和 TTL 后调用 `POST /api/projects/{projectId}/identity/auth/actor-token`。
- 签发成功后只保存 actor token、userId 和 expiresAt 到浏览器本地，不保存 bootstrap token。
- 允许清除本地令牌，便于切换操作者或排查鉴权问题。

## 不采用的方案

- 不新增独立登录页：当前项目还没有密码、OAuth 或统一身份源，独立登录页会制造伪完整账号体系。
- 不把 bootstrap token 写入环境变量暴露到前端：bootstrap token 是运维级签发凭证，只能由用户手动输入并一次性使用。
- 不在第 77 阶段扩大所有敏感接口鉴权：该工作独立且风险更高，后续阶段按接口类别逐步收紧。

## 数据与安全边界

- 本地存储键：`matrixcode.actorToken`、`matrixcode.actorTokenUserId`、`matrixcode.actorTokenExpiresAt`。
- bootstrap token 只存在于表单状态中，提交成功或清除时立即清空。
- 签发接口仍由服务端校验项目成员身份和 token TTL 上限。
- 前端后续调用责任审计时继续通过第 76 阶段 `actorHeaders(...)` 自动携带 Bearer token。

## 验证标准

- API client 测试覆盖签发接口 URL、请求体和 `X-MatrixCode-Bootstrap-Token` 头。
- 桌面端测试覆盖配置中心「身份」页签签发、保存本地令牌元数据、清除令牌。
- 桌面端全量测试、桌面构建、服务端目标测试、静态扫描和敏感信息扫描通过。
- 完成后更新 Obsidian 项目图谱，并回溯第 75、76 阶段鉴权链路没有偏离真实上线目标。
