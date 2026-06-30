# MatrixCode 第 47 阶段规格：运行态提醒 MyBatis-Plus 仓储迁移

## 背景

第 31 阶段已将运行态提醒迁移到正式 MySQL 表 `matrixcode_runtime_notifications`，但正式 Spring Bean 主路径仍由 `JdbcRuntimeNotificationStore` 通过手写 JDBC 读写。

第 46 阶段已完成角色智能体配置仓储 MyBatis-Plus 迁移，证明现有 `MatrixProjectEntity`、`MatrixUserEntity`、Mapper 和条件 DataSource 机制可以复用。第 47 阶段继续沿用同一模板，把运行态提醒单表读写迁移到 MyBatis-Plus。

## 目标

- JDBC 模式下 `RuntimeNotificationStore` Bean 使用 `MybatisPlusRuntimeNotificationStore`。
- 保留旧 `JdbcRuntimeNotificationStore` 作为历史兼容实现和直接单测对象，但不再作为 Spring Bean。
- 保留正式表为空时从旧 `runtime-notifications` 快照回填正式表的能力。
- 保持现有 `RuntimeNotificationService`、Workbench API 和桌面端契约不变。

## 非目标

- 不改变运行态提醒领域模型。
- 不新增 Redis/RocketMQ 运行依赖。
- 不改变用户级已读、批量已读和工作台事件规则。
- 不修改历史 Flyway SQL。

## 设计

### 实体

新增 `RuntimeNotificationEntity`：

- 表：`matrixcode_runtime_notifications`
- 主键：`id`
- 字段：`projectId`、`userId`、`levelKey`、`sourceType`、`sourceId`、`title`、`message`、`readAt`、`createdAt`、`updatedAt`
- `fromDomain(RuntimeNotification)`：集中把领域对象转换为数据库实体。
- `toDomain()`：集中把数据库实体转换回领域对象，兼容空 `sourceId` 和空 `userId`。

### Mapper

新增 `RuntimeNotificationMapper extends BaseMapper<RuntimeNotificationEntity>`。

### Store

新增 `MybatisPlusRuntimeNotificationStore implements RuntimeNotificationStore`：

- `load()`：读取正式表，按 `projectId asc, createdAt desc, id asc` 排序并聚合为 `RuntimeNotificationSnapshot`。
- 正式表为空时读取旧 `JdbcSnapshotRepository` 中的 `runtime-notifications` 切片，成功后写回正式表。
- `save()`：事务内清空正式表，再批量写入快照中的所有提醒。
- 写入每条提醒前补齐项目和已读用户外键。

### 兼容

- `JdbcRuntimeNotificationStore` 移除 `@Service` 和 `@ConditionalOnProperty`，避免 JDBC 模式下出现两个 `RuntimeNotificationStore` Bean。
- 旧 store 的直接单测保留，继续验证历史兼容逻辑。

## 验证

- 红灯：新增 `MybatisPlusRuntimeNotificationStoreTest`，初始应失败，因为 JDBC 模式当前 Bean 仍为 `JdbcRuntimeNotificationStore`。
- file 模式回归：不创建 `DataSource`，`RuntimeNotificationStore` 仍为文件实现。
- 局部绿灯：新 MyBatis-Plus store 测试和旧 JDBC store 测试通过。
- 关联回归：`RuntimeNotificationServiceTest`、`WorkbenchControllerTest`、`JdbcPersistenceSpringTest`。
- 全量回归：服务端全量测试。
- 真实验证：`RealRuntimeIntegrationTest`、真实后端 health、真实提醒已读 API 写读。
- 静态检查：`git diff --check`、敏感信息扫描、正式 H2 口径扫描。

## 回溯对齐

- 对齐用户要求：正式 ORM 使用 MyBatis-Plus；正式业务数据使用 MySQL。
- 对齐最初需求：运行态提醒是多人实时协作控制台的协作反馈链路，迁移不改变 UI 和业务契约。
- 对齐上线目标：降低手写 JDBC 技术债，同时保留旧快照回填能力，减少真实上线迁移风险。
