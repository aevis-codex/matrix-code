# MatrixCode Agent Runtime 用户责任审计权限设计

## 背景

第 73 阶段已提供用户责任审计后端能力，第 74 阶段已在桌面端运行中心展示审计报告。当前接口仍完全信任 `userId` 查询参数，任何调用方只要知道项目 ID 和用户 ID，就能读取该用户在项目内的责任运行摘要。虽然返回内容已低敏，但这仍不符合多人实时协作控制台的可上线权限边界。

## 目标

- 为 `GET /api/projects/{projectId}/agent-runs/user-audit` 增加服务端访问控制。
- 请求方必须通过 `X-MatrixCode-User-Id` 声明当前用户。
- 当前用户只能读取自己的责任审计。
- 项目 OWNER、ADMIN、MAINTAINER 可以读取同项目其他用户的责任审计，用于项目治理和验收。
- 桌面端读取当前操作者审计时自动携带身份头。

## 非目标

- 不一次性引入完整登录、OAuth、JWT、Session 或 Spring Security。
- 不改变既有运行、模型、文档、Bug 等接口的访问方式。
- 不增加数据库表或 Flyway 迁移。
- 不暴露 prompt、模型响应、工具输出、命令输出、向量正文或密钥。

## 推荐方案

采用轻量请求身份解析器：

- 后端新增 `RequestActorResolver`，从 `X-MatrixCode-User-Id` 读取并归一化当前用户。
- 用户责任审计控制器在调用业务服务前执行权限检查。
- 缺失身份头返回 401。
- 身份头与查询 `userId` 一致时允许。
- 身份头与查询 `userId` 不一致时，读取项目成员列表，仅 OWNER、ADMIN、MAINTAINER 允许。
- 其他成员或非项目成员返回 403。

该方案把最敏感的新审计入口先收紧，同时保持项目现有本地开发、测试和桌面端主链路稳定。

## 数据流

1. 桌面端当前操作者选择器得到 `actorUserId`。
2. `loadAgentRunUserAudit(projectId, actorUserId, limit)` 请求服务端，并设置 `X-MatrixCode-User-Id: actorUserId`。
3. 控制器读取 `X-MatrixCode-User-Id`。
4. 控制器校验当前用户是否可读取目标 `userId`。
5. 权限通过后调用 `AgentRuntimeUserAuditService.audit(...)` 返回低敏报告。

## 错误语义

- 401：未提供 `X-MatrixCode-User-Id`。
- 403：当前用户不是目标用户，也不是项目 OWNER、ADMIN、MAINTAINER。
- 400：项目 ID、用户 ID 等参数为空。

## 测试策略

- 后端控制器测试：
  - 未带身份头返回 401。
  - 本人查询返回 200。
  - OWNER 查询他人返回 200。
  - 普通成员查询他人返回 403。
- 桌面端 API client 测试：
  - 请求用户责任审计时带 `X-MatrixCode-User-Id`。
- 回归验证：
  - 服务端全量测试。
  - 桌面端全量测试和构建。
  - 真实运行配置检查。
  - 真实 MySQL/Milvus 集成测试。
  - 旧地址、旧 collection 和密钥扫描。

## 回溯对齐

- 对齐最初需求：多人实时协作的智能体控制台必须能区分用户责任和项目管理权限。
- 对齐第 38 到 41 阶段：复用身份成员、用户级审计和当前操作者上下文。
- 对齐第 73 到 74 阶段：责任审计保持低敏，但不能继续只依赖前端选择器。
- 对齐上线目标：先补服务端强制边界，再逐步推进完整认证体系。
