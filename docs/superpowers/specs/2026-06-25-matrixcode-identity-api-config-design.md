# 第 39 阶段：身份成员 API 与配置入口设计

## 背景

第 38 阶段已经补齐 `matrixcode_users`、`matrixcode_project_members` 和 `matrixcode_audit_records.actor_user_id` 的正式仓储读写能力，但该能力还停留在服务端内部，桌面端无法配置项目成员，也无法按用户查看审计记录。MatrixCode 的长期目标是多人实时协作智能体控制台，因此身份、成员、角色智能体配置和审计记录需要进入同一个配置闭环。

MySQL schema/database 与 Milvus database 均使用 `matrix_code`。本阶段不新增 Redis 或 RocketMQ 运行依赖。

## 推荐方案

采用“后端稳定 API + 配置弹窗成员页”的方案：

- 后端新增 `ProjectIdentityController`，暴露项目成员、用户项目、用户审计查询和成员添加接口。
- 前端复用现有「配置」按钮与 `RoleAgentConfigDialog`，增加顶层标签「智能体 / 成员」。
- 成员页显示项目成员列表，支持按角色标签筛选，支持添加成员，并可查看选中成员的用户级审计记录。
- 右侧 Inspector 继续只承载运行指标和关键事件，不塞入配置表单。

## API 设计

- `GET /api/projects/{projectId}/identity/members`：返回项目成员列表。
- `POST /api/projects/{projectId}/identity/members`：确保用户存在并加入项目角色。
- `GET /api/projects/{projectId}/identity/users/{userId}/audit-records`：返回该用户在项目内的审计记录。
- `GET /api/projects/{projectId}/identity/users/{userId}/projects`：返回该用户参与的项目 ID 列表。

## 前端交互

- 配置弹窗标题调整为「项目配置」。
- 顶层标签：
  - 「智能体」：保留原角色智能体配置。
  - 「成员」：展示成员配置与审计。
- 成员页：
  - 角色标签使用 `PRODUCT / DEVELOPER / TESTER / OPERATIONS / OWNER`。
  - 添加成员字段：用户 ID、显示名、角色。
  - 选中成员后加载并展示最近审计记录。

## 测试策略

- 后端使用 MockMvc 写红灯测试，覆盖成员列表、成员添加、审计查询。
- 前端 API client 写红灯测试，覆盖新增 endpoint、请求体和响应类型。
- 前端 App 测试覆盖配置弹窗新增「成员」标签、添加成员和审计显示。
- 最后运行服务端相关测试、桌面端相关测试、真实运行预检和真实集成测试。

## 回溯对齐

- 对齐最初需求：多人实时协作控制台必须有成员身份和角色归属。
- 对齐角色智能体目标：每个角色智能体仍有独立配置，成员页不替代智能体配置。
- 对齐真实可上线目标：继续使用 MySQL `matrix_code` 正式表，不引入 mock 存储。
- 对齐第 11、13、14、15 阶段：本阶段不改变提醒、审批和本地执行行为，只为后续用户级归属铺 API 与 UI 基础。
