# MatrixCode 第 149 阶段角色模型绑定 MyBatis-Plus 仓储迁移设计规格

## 背景

角色模型绑定此前随 `workbench-state.modelBindings` 保存。该快照适合早期 MVP 恢复，但不适合作为真实上线后的业务主数据。MatrixCode 需要让每个角色智能体独立配置模型供应商、模型名称、费用参数、上下文预算和工具契约版本，因此需要正式 MySQL 表和 MyBatis-Plus 仓储主路径。

## 目标

- JDBC 模式下角色模型绑定优先读写正式表。
- 正式表为空时自动从旧 `workbench-state.modelBindings` 回填。
- 新增 Flyway DDL 必须包含表注释和字段注释。
- 不改变模型绑定接口权限、模型请求链路、Prompt 编排和缓存策略。
- 不保存 prompt 正文、响应正文、向量正文、工具输出或密钥。

## 数据模型

新增 `matrixcode_role_model_bindings`：

- `id`：稳定主键，格式为 `projectId::role`。
- `project_id`：项目 ID，外键到 `matrixcode_projects.id`。
- `role_key`：角色键。
- `provider_id`：模型供应商 ID。
- `model_name`：模型名称。
- `currency`：费用币种。
- `cache_hit_per_million`：缓存命中输入 token 每百万价格。
- `cache_miss_input_per_million`：缓存未命中输入 token 每百万价格。
- `output_per_million`：输出 token 每百万价格。
- `context_budget_tokens`：上下文预算。
- `tool_contract_version`：工具契约版本。
- `created_at`、`updated_at`：创建和更新时间。

唯一约束：`project_id + role_key`。

## 实现

- `RoleModelBindingRepository` 定义正式仓储接口。
- `RoleModelBindingEntity` 负责领域对象和正式表实体转换。
- `RoleModelBindingMapper` 复用 MyBatis-Plus `BaseMapper`。
- `MybatisPlusRoleModelBindingRepository` 在 `matrixcode.persistence.mode=jdbc` 时注册。
- `RoleModelBindingService` 通过 `ObjectProvider<RoleModelBindingRepository>` 获取正式仓储。
- `RoleModelBindingService.loadInitialBindings()` 优先读取正式表；正式表为空且旧快照存在时回填。
- `RoleModelBindingService.saveBindings()` 在正式仓储可用时写正式表，否则保留文件/内存模式行为。

## 验收

- 角色模型绑定写入正式表后，`matrixcode_state_snapshots` 不再新增 `workbench-state` 角色绑定快照。
- 服务重启后可以从正式表恢复绑定。
- 旧 `workbench-state.modelBindings` 可在正式表为空时回填到正式表。
- Flyway 全量迁移包含 `matrixcode_role_model_bindings`。
- JDBC Spring 上下文中 `RoleModelBindingRepository` 主 Bean 是 MyBatis-Plus 实现。
- 服务端全量、桌面端全量、桌面端构建、生产就绪门禁和真实协议级检查通过。

## 回滚

代码回滚后，旧 `workbench-state.modelBindings` 兼容路径仍可被历史版本读取。数据库侧不自动删除 `matrixcode_role_model_bindings`，真实环境如需回滚数据库，应按生产备份恢复手册执行人工恢复。
