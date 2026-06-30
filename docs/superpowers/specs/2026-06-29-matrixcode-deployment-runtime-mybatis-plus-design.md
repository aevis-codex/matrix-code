# MatrixCode 第 144 阶段设计：部署运行态 MyBatis-Plus 仓储迁移

## 背景

部署目标已在第 48 阶段迁移到 MyBatis-Plus，但部署操作、部署健康检查、Compose 环境和 Compose 操作仍由 Spring 主路径上的手写 JDBC 仓储读写。该状态与“正式上线 ORM 使用 MyBatis-Plus”的约束不一致。

## 推荐方案

新增 MyBatis-Plus 实体、Mapper 和 `MybatisPlusDeploymentRuntimeRepository`，在 `matrixcode.persistence.mode=jdbc` 时作为 `DeploymentRuntimeRepository` 主 Bean。旧 `JdbcDeploymentRuntimeRepository` 去掉 Spring Bean 注解，保留直接构造测试，作为迁移兼容参照。

本阶段不新增 DDL，不修改表结构。写入语义保持旧仓储行为：

- 部署操作和部署健康检查按项目快照替换。
- Compose 环境按 ID upsert。
- Compose 操作按项目快照替换。
- 写入前确保 `matrixcode_projects` 中存在项目兜底记录。

## 验收标准

- Spring JDBC 模式下 `DeploymentRuntimeRepository` Bean 类名包含 `MybatisPlusDeploymentRuntimeRepository`。
- 四类正式表字段可完整保存并恢复。
- Compose 环境重复保存同一 ID 不重复插入。
- 默认 file 模式不创建 DataSource 和部署运行态仓储 Bean。
- `JdbcPersistenceSpringTest` 保持重启恢复行为通过。

## 回溯

- 对齐用户要求：正式业务数据使用 MySQL，正式 ORM 使用 MyBatis-Plus，H2 只用于测试。
- 对齐第 34 阶段：继续复用既有部署运行态正式表和注释。
- 对齐第 142 阶段：发布脚本审计导入仍写入 `matrixcode_deployment_operations`，只替换仓储实现，不改变 API 契约。
