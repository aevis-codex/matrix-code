# MatrixCode 第 46 阶段规格：角色智能体配置 MyBatis-Plus 仓储迁移

## 背景

用户要求正式上线使用 MySQL，ORM 框架使用 MyBatis-Plus。第 42 阶段已经用 MyBatis-Plus 完成 Agent Runtime 仓储，第 45 阶段补齐历史表字段注释后，下一步应按风险顺序迁移仍在手写 JDBC 的正式仓储。

角色智能体配置是四个角色的模型、提示词、工具契约、颜色、字体和启停状态入口。它的数据量小、表结构稳定、测试独立，适合作为 Agent Runtime 之后的第二个 MyBatis-Plus 迁移样板。

## 目标

- 在 JDBC 模式下，让 `RoleAgentConfigRepository` 使用 MyBatis-Plus 实现。
- 保持现有业务行为不变：加载全部配置、批量保存配置、保存前补齐项目外键。
- 保留旧 `JdbcRoleAgentConfigRepository` 作为直接单测覆盖的兼容类，但不再作为 Spring Bean 注入。
- 不修改现有 Flyway 表结构，不新增 Redis/RocketMQ 运行依赖。

## 非目标

- 不把 `temperature` 暴露到 `RoleAgentConfig` 领域对象。
- 不迁移文档、Bug、本地执行、提醒等其他 JDBC 仓储。
- 不改角色智能体配置 API 和桌面端 UI。

## 方案

采用“新增 MyBatis-Plus 实现，旧 JDBC 实现退出 Bean”的方式：

- 新增 `RoleAgentConfigEntity` 映射 `matrixcode_role_agent_configs`。
- 新增 `RoleAgentConfigMapper extends BaseMapper<RoleAgentConfigEntity>`。
- 新增 `MybatisPlusRoleAgentConfigRepository implements RoleAgentConfigRepository`。
- `save()` 在事务内先用 `MatrixProjectMapper` touch/insert 项目，再对每条配置执行 update-by-id，更新不到再 insert。
- `load()` 按 `project_id, sort_order, role_key` 排序恢复领域对象。
- 旧 `JdbcRoleAgentConfigRepository` 去掉 Spring 注解，保留直接实例化测试。

## 成功标准

- 新增 Spring 上下文测试证明 JDBC 模式下 `RoleAgentConfigRepository` Bean 是 MyBatis-Plus 实现。
- MyBatis-Plus 仓储能保存并恢复角色智能体配置。
- 保存配置时能补齐 `matrixcode_projects` 项目行。
- 默认 file 模式不创建 DataSource，也不创建 `RoleAgentConfigRepository` Bean。
- 服务端全量测试、真实集成测试、静态检查和敏感信息扫描通过。

## 风险与边界

- Spring Bean 冲突风险：旧 JDBC 仓储必须退出自动装配。
- MyBatis-Plus 字段映射风险：实体字段必须与表列一致，特别是 `model_provider`、`model_name`、`tool_contract_version`。
- 项目外键风险：保存前必须补齐项目行，不能破坏现有角色配置写入路径。
- 真实库风险：不修改 DDL，只读写既有 `matrixcode_role_agent_configs` 表。
