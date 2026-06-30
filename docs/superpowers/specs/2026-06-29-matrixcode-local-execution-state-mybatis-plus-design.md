# MatrixCode 第 148 阶段设计：本地执行状态 MyBatis-Plus 仓储迁移

## 背景

本地执行状态承载授权工作区、执行任务、任务日志和本地执行审批审计。当前 JDBC 模式 Spring 主路径仍由 `JdbcLocalExecutionStateStore` 手写 SQL 读写，和“正式上线 ORM 使用 MyBatis-Plus”的约束不一致。

同时，旧实现保存本地执行审计时会整体替换共享 `matrixcode_audit_records` 表的数据。该表也承载身份域和成员治理审计，因此本阶段需要在迁移 ORM 主路径时收窄本地执行审计的写入范围。

## 方案

新增本地工作区、执行任务、任务日志和本地执行审计实体与 Mapper，并以 `MybatisPlusLocalExecutionStateStore` 作为 `LocalExecutionStateStore` 的 JDBC 模式主 Bean。旧 `JdbcLocalExecutionStateStore` 去掉 Spring Bean 注解，保留直接构造测试，确保旧兼容路径仍可对照验证。

本阶段不新增 DDL，继续复用第 18、30、45 阶段已经形成并补齐注释的正式表。

## 行为保持

- 工作区、执行任务和任务日志仍采用整体替换语义，保持与旧状态快照一致。
- 正式表为空时，继续从旧 `local-execution` 快照回填，保证历史本地开发数据可平滑迁移。
- 审计读取只返回 `target_type = LOCAL_EXECUTION_TASK` 的本地执行审计。
- 审计保存只替换 `target_type = LOCAL_EXECUTION_TASK` 的记录，不再清理身份域、成员治理和会话治理审计。
- 不改变本地执行控制器、审批策略、路径守卫和 Sa-Token 权限边界。

## 验收

- Spring JDBC 模式下 `LocalExecutionStateStore` Bean 类名包含 `MybatisPlusLocalExecutionStateStore`。
- 正式表可保存并读取工作区、执行任务、任务日志和本地执行审计。
- 旧 `local-execution` 快照在正式表为空时可以回填。
- 共享审计表中的身份域审计不会被本地执行审计保存覆盖。
- `JdbcLocalExecutionStateStoreTest` 保持通过，证明旧实现兼容路径仍可用。
- `JdbcPersistenceSpringTest` 确认完整 Spring 上下文使用 MyBatis-Plus 本地执行状态仓储。
- 服务端全量、桌面端测试、桌面端构建、生产就绪聚合门禁和真实凭据精确扫描通过。

## 回溯

- 对齐第 30 阶段：不改变本地执行正式表结构，只替换主仓储实现。
- 对齐第 38、113、140 阶段：共享审计表不能被单一业务切片整体清空，本地执行写入必须限制在本地执行审计类型。
- 对齐第 144 到 147 阶段：沿用“新 MyBatis-Plus 主 Bean + 旧 JDBC 兼容测试”的低风险迁移模式。
- 对齐最初上线要求：正式业务数据继续使用 MySQL，正式 ORM 主路径继续收敛到 MyBatis-Plus，H2 仅用于测试。
