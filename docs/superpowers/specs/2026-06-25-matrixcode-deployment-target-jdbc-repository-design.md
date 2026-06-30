# 部署目标正式 MySQL 仓储设计

## 背景

第 28-31 阶段已经把文档中心、Bug 缺陷闭环、本地执行与审批审计、运行态提醒迁移到正式 MySQL 领域表优先。部署目标仍由 `DeploymentTargetService` 通过 `WorkbenchStateStore.saveDeploymentTargets()` 写入 `workbench-state` 快照。

第 18 阶段已经创建 `matrixcode_deployment_targets` 表，但现有表字段只覆盖环境名称、环境 key、provider、状态和 endpoint，不足以完整恢复 `DeploymentTarget` 的 SSH 地址、部署说明、健康检查地址、回滚说明和 remoteExecuted 标记。因此本阶段需要先扩展部署目标表，再切换服务层仓储。

## 目标

- 在 JDBC 模式下，让部署目标优先读写正式 `matrixcode_deployment_targets` 表。
- 正式表为空且旧 `workbench-state` 快照中存在部署目标时，从快照恢复并回填正式表。
- 正式表有数据时，不再以 `workbench-state` 中的部署目标作为主数据源。
- 保持 `DeploymentTargetService` 对外 API 不变。
- 保持部署操作、健康检查、Compose 环境和 Compose 操作仍由 `workbench-state` 承载，后续阶段继续拆分。

## 非目标

- 不迁移部署操作记录。
- 不迁移部署健康检查记录。
- 不迁移 Compose 环境或 Compose 操作记录。
- 不改变桌面端部署/运维页面。
- 不引入 Redis 或 RocketMQ 运行依赖。
- 不做真实 SSH、Docker Compose 或远程部署执行。

## 方案

新增 `DeploymentTargetRepository`：

- `List<DeploymentTarget> load()`
- `void save(List<DeploymentTarget> targets)`

改造 `DeploymentTargetService`：

1. 增加可选 `DeploymentTargetRepository` 注入。
2. 首次访问时延迟加载。
3. 仓储非空时优先使用仓储结果。
4. 仓储为空时读取 `stateStore.load().deploymentTargets()`。
5. 如果仓储存在且旧快照非空，则把旧快照回填到正式表。
6. 保存时如果仓储存在则写仓储，否则继续写 `WorkbenchStateStore`。

新增 `JdbcDeploymentTargetRepository`：

- 使用 `matrixcode_deployment_targets` 读写部署目标。
- 写入时 upsert：已有记录 update，不存在 insert。
- 自动确保 `matrixcode_projects` 项目记录存在。
- 通过 `DatabaseMigrationService` 保证 Flyway 在业务读取前执行。

新增 Flyway 迁移 `V32_1__extend_deployment_targets_for_domain_store.sql`：

- `ssh_address varchar(500)`
- `deploy_note text`
- `health_check_url varchar(500)`
- `rollback_note text`
- `remote_executed boolean`

字段映射：

- `DeploymentTarget.id` -> `id`
- `projectId` -> `project_id`
- `environmentName` -> `name`
- `environmentName` 的稳定 slug -> `environment_key`
- `environmentUrl` -> `endpoint_url`
- `sshAddress` -> `ssh_address`
- `deployNote` -> `deploy_note`
- `healthCheckUrl` -> `health_check_url`
- `rollbackNote` -> `rollback_note`
- `status` -> `status`
- `remoteExecuted` -> `remote_executed`
- `updatedAt` -> `updated_at`
- `provider` 暂写 `manual`

## 测试策略

- 新增 `JdbcDeploymentTargetRepositoryTest`：保存部署目标后能从正式表恢复完整字段，并自动创建项目记录。
- 改造 `DeploymentTargetServiceTest`：增加仓储优先测试，确认服务不会写旧 `WorkbenchStateStore` 快照。
- 增加旧快照回填测试，确认仓储为空时服务从 `WorkbenchStateStore` 恢复部署目标并回填正式表。
- 更新 `JdbcPersistenceSpringTest`：确认 Spring JDBC 重启后部署目标从正式表恢复，`matrixcode_deployment_targets` 有记录。
- 运行部署目标、仓储、Spring JDBC 关联测试和服务端全量测试。

## 回溯对齐

- 对齐真实 MySQL 目标：部署目标是运维和 Compose 运行态的根数据，应进入正式业务表。
- 对齐多人实时协作智能体控制台：产品、开发、测试、运维角色都需要稳定引用同一部署目标。
- 对齐安全边界：本阶段只迁移配置数据，不执行远程部署，不放宽审批和本地命令策略。
- 对齐 Redis/RocketMQ 决策：当前不需要跨节点事件流和缓存，不引入运行依赖。

