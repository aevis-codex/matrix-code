# MatrixCode 第 48 阶段规格：部署目标 MyBatis-Plus 仓储迁移

## 背景

第 32 阶段已将部署目标迁移到正式 MySQL 表 `matrixcode_deployment_targets`，但 Spring Bean 主路径仍由 `JdbcDeploymentTargetRepository` 通过手写 JDBC 读写。第 46、47 阶段已证明单表仓储可以按低风险模板迁移到 MyBatis-Plus：新增实体、Mapper 和正式仓储，旧 JDBC 实现退出 Bean，仅保留直接单测。

第 48 阶段继续沿用该模板，迁移部署目标仓储。该模块只有一张主表，API 契约稳定，真实接口在第 32 和第 34 阶段已经验证，适合作为下一批 MyBatis-Plus 收敛目标。

## 目标

- JDBC 模式下 `DeploymentTargetRepository` Bean 使用 `MybatisPlusDeploymentTargetRepository`。
- 保留旧 `JdbcDeploymentTargetRepository` 作为历史兼容实现和直接单测对象，但不再作为 Spring Bean。
- 保持部署目标 API、Workbench API、桌面端和部署运行态服务契约不变。
- 保持写入前补齐项目外键的行为。
- 继续满足正式业务数据使用 MySQL、正式 ORM 使用 MyBatis-Plus、H2 仅用于测试的约定。

## 非目标

- 不修改 `DeploymentTarget` 领域模型。
- 不新增 Flyway DDL。
- 不迁移部署操作、部署健康检查、Compose 环境和 Compose 操作仓储；这些仍归后续阶段处理。
- 不引入 Redis、RocketMQ 业务依赖。

## 设计

### 实体

新增 `DeploymentTargetEntity`：

- 表：`matrixcode_deployment_targets`
- 主键：`id`
- 字段：`projectId`、`name`、`environmentKey`、`provider`、`status`、`endpointUrl`、`sshAddress`、`deployNote`、`healthCheckUrl`、`rollbackNote`、`remoteExecuted`、`createdAt`、`updatedAt`
- `fromDomain(DeploymentTarget)`：集中把领域对象转换为数据库实体。
- `toDomain()`：集中把数据库实体转换回领域对象，兼容空 `healthCheckUrl`、空文本字段和空时间。

### Mapper

新增 `DeploymentTargetMapper extends BaseMapper<DeploymentTargetEntity>`。

### Repository

新增 `MybatisPlusDeploymentTargetRepository implements DeploymentTargetRepository`：

- `load()`：按 `projectId asc, name asc, id asc` 稳定排序读取正式表。
- `save()`：事务内逐条 upsert，保持现有 `save(List<DeploymentTarget>)` 的增量写入语义，不清空表。
- 写入前通过 `MatrixProjectMapper` 补齐项目外键。

### 兼容

- `JdbcDeploymentTargetRepository` 移除 `@Repository`、`@ConditionalOnProperty` 和 `@Autowired`，避免 JDBC 模式下出现两个 `DeploymentTargetRepository` Bean。
- 旧仓储直接单测保留，验证历史兼容逻辑。

## 验证

- 红灯：新增 `MybatisPlusDeploymentTargetRepositoryTest`，初始应失败，因为 JDBC 模式当前 Bean 仍为 `JdbcDeploymentTargetRepository`。
- file 模式回归：不创建 `DataSource`，且没有 `DeploymentTargetRepository` Bean。
- 局部绿灯：新 MyBatis-Plus 仓储测试和旧 JDBC 仓储测试通过。
- 关联回归：`DeploymentTargetServiceTest`、`WorkbenchControllerTest`、`JdbcPersistenceSpringTest`。
- 全量回归：服务端全量测试。
- 真实验证：`RealRuntimeIntegrationTest`、真实后端 health、部署目标 API 写读。
- 静态检查：`git diff --check`、敏感信息扫描、正式 H2 口径扫描。

## 回溯对齐

- 对齐用户要求：正式 ORM 使用 MyBatis-Plus；正式业务数据使用 MySQL；H2 只用于测试。
- 对齐最初需求：部署目标是运维角色和多人协作上线流程的基础数据，不改变 API 和桌面端契约。
- 对齐上线目标：通过单表仓储替换继续降低手写 JDBC 技术债，同时保留旧实现的直接测试作为回归基线。
