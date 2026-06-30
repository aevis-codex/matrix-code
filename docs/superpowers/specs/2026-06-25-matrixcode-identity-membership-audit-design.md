# 第 38 阶段：身份、成员与用户级审计正式仓储设计

## 背景

MatrixCode 的目标是多人实时协作的智能体控制台。第 37 阶段已经把工作流状态和验收投影落到正式 MySQL 表，但用户、项目成员和审计查询仍缺少明确的业务仓储入口。已有 Flyway 表 `matrixcode_users`、`matrixcode_project_members` 和 `matrixcode_audit_records` 可以承载该能力。

运行时约束：

- MySQL database/schema 固定为 `matrix_code`。
- Milvus database/schema 固定为 `matrix_code`。
- Milvus collection 继续使用当前运行约定中的 `matrixcode_context_chunks_v2`。
- Redis 与 RocketMQ 当前只保留配置位置，不作为第 38 阶段上线阻塞项。

## 目标

第 38 阶段只补齐真实可用所需的身份基础，不引入完整登录认证：

- 提供用户与项目成员的领域模型和仓储接口。
- 在 JDBC 模式下读写 `matrixcode_users` 与 `matrixcode_project_members`。
- 支持按项目查询成员、按用户查询参与项目、确保项目 owner/member 存在。
- 提供用户级审计查询入口，从 `matrixcode_audit_records` 按 `actor_user_id` 和项目读取。
- 让本地执行审计写入时，如果 `actorId` 看起来是用户 ID，则写入 `actor_user_id`，同时确保该用户存在，避免外键失败。

## 非目标

- 不实现登录、JWT、OAuth 或会话鉴权。
- 不新增前端登录页面。
- 不强制所有现有 API 立即校验成员权限。
- 不引入 Redis/RocketMQ 的业务流转。

## 架构

新增 `identity` 模块，包含：

- `MatrixUser`：用户身份。
- `ProjectMember`：项目成员及角色。
- `ProjectIdentityRepository`：身份仓储端口。
- `ProjectIdentityService`：服务层，负责确保用户、项目成员和查询审计视图。

新增 JDBC 适配器：

- `JdbcProjectIdentityRepository`：直接读写正式表。
- 在 JDBC 模式启用，沿用当前仓储风格，通过 `PersistenceModeProperties` 获取连接。
- 启动或调用仓储时确保 Flyway 已迁移。

审计接入：

- `JdbcLocalExecutionStateStore` 写入 `matrixcode_audit_records` 时，将 `record.actorId()` 视为用户 ID 写入 `actor_user_id`。
- 同时补充 `actor_role` 为同一个值，兼容旧 UI 对 actor 字段的读取。
- 写入前通过最小 SQL 确保 `matrixcode_users` 存在。

## 数据流

1. 本地执行、审批或取消动作产生 `AuditRecord`。
2. JDBC 存储写入 `matrixcode_audit_records.actor_user_id`。
3. `ProjectIdentityService.auditRecordsForUser(projectId, userId)` 查询用户级审计视图。
4. 工作台或后续权限层可以基于 `ProjectIdentityService.members(projectId)` 获取项目成员。

## 测试策略

- `JdbcProjectIdentityRepositoryTest`：H2 MySQL 模式迁移后，覆盖用户、成员、项目归属和审计查询。
- `LocalExecutionIdentityAuditTest`：验证 JDBC 审计写入会创建用户并填充 `actor_user_id`。
- `RealRuntimeIntegrationTest`：真实 MySQL `matrix_code` 上增加身份/成员/审计写读验证。
- 回归运行服务端全量测试和真实运行预检。

## 回溯结论

与初始需求对齐：

- 多人实时协作需要用户和成员边界，第 38 阶段补齐基础。
- 每个角色都有智能体配置的能力已由角色配置仓储承载，本阶段为这些角色绑定真实成员关系做准备。
- 真实可上线要求可审计，本阶段将审计从普通字符串推进到用户级查询。
