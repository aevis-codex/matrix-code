# MatrixCode 本地执行命令接口鉴权设计

## 背景

第 78 阶段已把 Agent Runtime 写接口纳入可信身份校验。本地执行代理是更高风险边界，可以提交本地命令、审批命令和取消任务；这些接口当前只信任请求体里的 `actorId`，缺少服务端请求身份校验。

## 推荐方案

先收紧带操作者字段的命令链路：

- `POST /api/projects/{projectId}/local-execution/commands`
- `POST /api/projects/{projectId}/local-execution/commands/{taskId}/approval`
- `POST /api/projects/{projectId}/local-execution/commands/{taskId}/cancel`

控制器通过 `RequestActorResolver` 解析请求身份，并要求请求身份与请求体 `actorId` 一致。

## 本阶段不做

- 不修改工作区授权、文件读写、文件列表、Git diff 的请求体结构。
- 不新增 DDL。
- 不改变审批策略、命令安全策略或任务队列执行方式。
- 不执行真实命令验收，只验证控制器权限边界和桌面端 API 透传。

## 后续阶段

文件读写和 Git diff 当前没有 actor 字段，需要单独设计 `actorId` 或可信上下文来源，再纳入统一鉴权。

## 验证标准

- 缺少身份头提交命令返回 401。
- 请求身份与提交命令 `actorId` 不一致返回 403。
- 请求身份与审批/取消 `actorId` 不一致返回 403。
- 桌面端 `submitLocalCommand(...)`、`decideLocalCommandApproval(...)`、`cancelLocalExecutionTask(...)` 透传 `X-MatrixCode-User-Id` 和可选 Bearer token。
