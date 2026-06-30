# MatrixCode 项目邀请与成员批量治理设计

## 目标

第 140 阶段补齐多人实时协作控制台上线前的成员生命周期能力：项目管理者可以创建邀请、查看待处理邀请，被邀请用户可以用一次性令牌加入项目，管理者可以批量禁用、恢复或移除成员。

## 范围

- 新增项目邀请领域模型 `ProjectInvitation`。
- 新增身份服务方法：创建邀请、接受邀请、查询邀请、批量更新成员。
- 新增身份 API：
  - `GET /api/projects/{projectId}/identity/invitations`
  - `POST /api/projects/{projectId}/identity/invitations`
  - `POST /api/projects/{projectId}/identity/invitations/{token}/accept`
  - `PATCH /api/projects/{projectId}/identity/members:batch`
- 新增 Flyway 迁移 `V140_1__create_project_invitations.sql`，表和字段均带注释。
- 前端配置弹窗增加邀请创建、最新令牌展示、待处理邀请列表和批量成员状态按钮。

## 安全边界

- 创建邀请、查看邀请和批量治理只允许项目管理角色调用。
- 接受邀请不要求当前用户已是项目成员，但必须拥有有效登录身份，且当前用户 ID 必须等于邀请中的被邀请用户 ID。
- 数据库只保存邀请 token 的 SHA-256 哈希，明文 token 只在创建响应中返回一次。
- 邀请过期、跨项目、非 PENDING 状态或用户不匹配时拒绝接受。
- 批量治理复用单成员更新保护，仍保证项目至少保留一个 ACTIVE 管理成员。

## 数据结构

`matrixcode_project_invitations` 记录邀请生命周期，关键字段：

- `project_id`：项目隔离。
- `invitee_user_id`：被邀请用户。
- `role_key`：接受后授予的项目角色。
- `status`：`PENDING`、`ACCEPTED`、`REVOKED`、`EXPIRED`。
- `token_hash`：一次性邀请令牌哈希。
- `expires_at` / `accepted_at`：过期与接受时间。

## 验证

- `ProjectIdentityServiceTest` 覆盖邀请创建/接受、过期和用户不匹配、批量治理。
- `ProjectIdentityControllerTest` 覆盖 HTTP 权限、邀请接受、批量治理。
- `JdbcProjectIdentityRepositoryTest` 覆盖邀请持久化、按项目查询、按 token 哈希查询和接受后状态更新。
- `DatabaseMigrationCommentPolicyTest` 验证新建表具备表注释和字段注释。
- `desktop/src/api/client.test.ts` 覆盖前端 API 客户端请求。
- `DockerComposeRuntimeClientTest` 复验 Compose 子进程超时测试稳定性。
- `/Users/Masons/Ai/Maven/bin/mvn -pl server test -Dmaven.repo.local=/Users/Masons/Ai/Maven_Ai_Store`：595 条通过，0 failures，0 errors，8 skipped。
- `npm test -- --run`：桌面端 133 条通过。
- `npm run build`：桌面端 TypeScript 和 Vite 构建通过。

## 回溯

- 与最初“多人实时协作智能体控制台”目标一致：成员不再只能由管理员直接添加，已有可审计的邀请加入路径。
- 与 Sa-Token 主线一致：接受邀请仍依赖当前登录身份，避免匿名令牌直接扩权。
- 与安全要求一致：不保存明文邀请 token。
- 与 MySQL 上线要求一致：新增表通过 Flyway 管理，并补齐详细注释。

## 下一步

- 邀请撤销、重发、过期清理已在第 141 阶段补齐。
- 发布脚本审计导入平台部署记录已在第 142 阶段补齐。
- 剩余手写 JDBC 仓储迁移 MyBatis-Plus。
- 告警平台升级排班已在第 143 阶段补齐。
