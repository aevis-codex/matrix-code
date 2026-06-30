# 本地执行与审批审计正式 MySQL 仓储设计

## 背景

第 21、28、29 阶段已经把角色智能体配置、文档中心和 Bug 缺陷闭环迁移到正式 MySQL 领域表优先。本地执行和审批审计仍通过 `LocalExecutionStateStore` 写入 `matrixcode_state_snapshots` 的 `local-execution` 切片，这会让编码智能体的命令任务、任务日志和审批记录缺少上线级查询与审计边界。

## 目标

在 JDBC 模式下，让本地执行状态优先读写正式领域表，并保留旧 `local-execution` 快照回填能力：

- 工作区授权写入 `matrixcode_local_workspaces`。
- 执行任务写入 `matrixcode_local_execution_tasks`。
- 执行日志写入 `matrixcode_local_task_logs`。
- 审批审计写入 `matrixcode_audit_records`。
- 正式表为空、旧快照存在时，自动从旧快照恢复并回填正式表。

## 非目标

- 不在本阶段迁移文件操作历史和 Git diff 摘要；它们仍归 `WorkbenchStateStore` 负责。
- 不改变当前 API 请求/响应结构。
- 不引入 Redis 或 RocketMQ 运行依赖。
- 不引入用户身份模型；审批责任人仍以现有 `actorId`、`approverId` 字符串表达。

## 方案

沿用现有 `LocalExecutionStateStore` 接口，改造 `JdbcLocalExecutionStateStore` 的内部实现：

1. `load()` 优先读取正式表，组装 `LocalExecutionSnapshot`。
2. 正式表为空时读取旧 `matrixcode_state_snapshots` 的 `local-execution` 切片。
3. 旧快照有数据时调用正式表保存逻辑回填。
4. `saveWorkspaces`、`saveTasks`、`saveAuditRecords` 分别 upsert 对应正式表。

数据表补充：

- 新增 `V30_1__extend_local_execution_for_domain_store.sql`。
- 新增 `matrixcode_local_workspaces`。
- 新增 `matrixcode_local_task_logs`。
- 扩展 `matrixcode_local_execution_tasks`，补齐 actor、工具类型、审批结果、输出摘要、耗时、审批备注、取消信息等字段。
- 扩展 `matrixcode_audit_records`，补齐 `task_id`、`tool_type`、`workspace_path`、`occurred_at`。

由于 `AuditRecord` 当前没有 `projectId`，写入审计表时优先通过 `taskId` 从任务表反查项目；找不到任务时使用系统项目 `local-execution` 并自动创建项目行。这样保持现有领域模型不扩散，同时满足表上的项目外键。

## 测试策略

- `JdbcLocalExecutionStateStoreTest` 增加正式表持久化断言：保存工作区、任务、日志、审计后重建仓储，可从正式表恢复；同时正式表有记录，旧快照不新增 `local-execution` 切片。
- `JdbcLocalExecutionStateStoreTest` 增加旧快照回填断言：先用 `JdbcSnapshotRepository` 写入旧切片，再重建正式仓储，确认恢复并回填正式表。
- `JdbcPersistenceSpringTest` 增加 Spring JDBC 回归断言：服务上下文使用正式本地执行仓储，重启后工作区、任务和审计记录从正式表恢复，并确认正式表计数。
- 跑 `JdbcLocalExecutionStateStoreTest`、`LocalTaskStoreTest`、`ApprovalPolicyTest`、`JdbcPersistenceSpringTest` 和服务端全量测试。

## 回溯对齐

- 对齐多人实时协作智能体控制台目标：编码智能体命令执行和审批审计必须可恢复、可追责、可查询。
- 对齐真实基础设施决策：业务状态继续落 MySQL；Redis/RocketMQ 只按真实多人协作需要再接入。
- 对齐安全边界：本阶段只替换持久化，不放宽审批策略、不新增自动执行权限、不记录密钥明文。
