# MatrixCode 项目邀请生命周期治理设计

## 目标

第 141 阶段补齐项目邀请创建和接受之后的治理闭环：项目管理者可以撤销待处理邀请、重发邀请并轮换一次性令牌、批量清理已过期待处理邀请。

## 范围

- 新增身份服务方法：
  - `revokeInvitation(...)`
  - `reissueInvitation(...)`
  - `expirePendingInvitations(...)`
- 新增身份 API：
  - `POST /api/projects/{projectId}/identity/invitations/{invitationId}/revoke`
  - `POST /api/projects/{projectId}/identity/invitations/{invitationId}/reissue`
  - `POST /api/projects/{projectId}/identity/invitations:expire`
- JDBC 身份仓储新增按 ID 读取邀请、带 token hash 替换邀请和过期待处理邀请查询。
- 桌面端配置中心成员页在待处理邀请列表中新增撤销、重发和清理过期邀请按钮。

## 非目标

- 不新增邀请表结构。
- 不把明文 token 写入数据库、日志、审计或 Obsidian。
- 不放宽项目管理角色权限。
- 不把邀请接受变成匿名入口；接受邀请仍要求当前登录身份与被邀请用户一致。

## 安全边界

- 撤销、重发和清理过期邀请必须由项目管理角色发起。
- 撤销保留旧 token hash，旧令牌再次接受时返回“项目邀请不可用”，便于前端区分“治理下线”和“不存在”。
- 重发会替换 token hash，旧令牌再次接受时返回“项目邀请不存在”。
- 已接受邀请不能重发，避免重复创建成员或复用已完成邀请。
- 过期清理只处理当前项目中 `PENDING` 且 `expires_at <= now` 的邀请。

## 验证

- 红灯：
  - 服务测试确认旧实现缺少撤销、重发和过期清理方法。
  - JDBC 测试确认旧仓储缺少按 ID 读取、token hash 替换和过期查询。
  - 桌面 API 测试确认旧客户端缺少三个生命周期函数。
- 目标绿灯：
  - `ProjectIdentityServiceTest,ProjectIdentityControllerTest,JdbcProjectIdentityRepositoryTest` 共 33 条通过。
  - `desktop/src/api/client.test.ts` 共 73 条通过。
  - `desktop/src/test/App.test.tsx` 共 58 条通过。
- 全量门禁：
  - 完成第 141 阶段后执行服务端全量、桌面端全量、桌面构建、生产就绪聚合门禁、静态检查和敏感扫描。

## 回溯

- 第 140 阶段已经补齐邀请创建、待处理列表、一次性 token 接受和成员批量治理；第 141 阶段关闭待处理邀请的治理缺口。
- 第 85、93、140 阶段形成的项目管理权限和最后 ACTIVE 管理成员保护不变。
- 第 82 到 116 阶段形成的 Sa-Token 登录态、会话治理和桌面端 Bearer token 透传不变。
- 第 140 阶段的 `matrixcode_project_invitations` 已具备表和字段注释，本阶段不新增迁移，降低上线变更面。

## 下一步

- 发布脚本审计导入平台部署记录已在第 142 阶段补齐。
- 告警平台适配和升级排班已在第 143 阶段补齐。
- 剩余手写 JDBC 仓储迁移 MyBatis-Plus。
- 长期模型成本趋势分析和供应商 cache 策略复盘。
