# MatrixCode 第 150 阶段：本地工作区活动 MyBatis-Plus 仓储迁移设计

## 背景

MatrixCode 的本地执行链路已经完成工作区、任务、日志和审批审计的正式 MySQL + MyBatis-Plus 主路径迁移，但本地文件操作记录和 Git Diff 摘要仍由 `workbench-state` 聚合快照承载。

这两类数据属于开发智能体工作区审计与交付回溯证据，生产环境不应继续依赖聚合 JSON 快照作为主写入路径。

## 目标

- 新增正式 MySQL 表保存本地文件操作记录和 Git Diff 摘要。
- 使用 MyBatis-Plus 实体、Mapper 和仓储实现正式读写路径。
- JDBC 模式下优先读取正式表；正式表为空时从旧 `workbench-state` 对应字段回填。
- 文件模式与内存模式保持原兼容行为。
- 不保存 API Key、数据库密码、完整 prompt、模型响应正文、向量正文、命令输出或文件正文。

## 非目标

- 不调整本地执行权限、审批、路径守卫、文件大小限制和二进制拒绝策略。
- 不引入 Redis 或 RocketMQ 运行依赖。
- 不删除 `JdbcWorkbenchStateStore` 与 `JdbcSnapshotRepository` 兼容层。

## 数据模型

### `matrixcode_local_file_operations`

用于按项目保存本地文件操作记录，覆盖读文件、写文件、目录列表、受控 Patch 等低敏摘要。

关键字段：

- `id`：操作记录主键。
- `project_id`：所属项目。
- `workspace_id`：工作区 ID。
- `actor_id`：操作者 ID。
- `operation_type`：操作类型。
- `relative_path`：工作区内相对路径。
- `status`：执行状态。
- `summary`：低敏摘要。
- `sort_order`：项目内排序。
- `created_at` / `updated_at`：创建和更新时间。

### `matrixcode_local_git_diff_summaries`

用于保存每个项目最近一次 Git Diff 摘要。

关键字段：

- `project_id`：项目主键，同时作为摘要主键。
- `workspace_id`：工作区 ID。
- `base_ref`：基线引用。
- `head_ref`：目标引用。
- `changed_files_json`：变更文件低敏摘要 JSON。
- `summary`：Diff 摘要。
- `captured_at` / `updated_at`：采集和更新时间。

## 服务改造

- 新增 `LocalWorkspaceActivityRepository` 作为应用层端口。
- 新增 `MybatisPlusLocalWorkspaceActivityRepository` 作为 JDBC 模式主实现。
- `LocalFileService` 在存在正式仓储时优先读写 `matrixcode_local_file_operations`。
- `LocalGitDiffService` 在存在正式仓储时优先读写 `matrixcode_local_git_diff_summaries`。
- 正式表为空时，分别读取旧 `workbench-state.fileOperations` 和 `workbench-state.gitDiffSummaries` 并回填正式表。

## 验证标准

- TDD 红灯：新增正式仓储测试先因缺表失败。
- 目标测试覆盖正式表保存恢复、Git Diff 摘要保存恢复、旧快照回填和完整 JDBC 工作台流程。
- 服务端全量测试通过。
- 桌面端全量测试和生产构建通过。
- 生产就绪聚合门禁通过。
- 真实运行脚本在协议级模式下通过 MySQL/Flyway、Milvus、Redis 和 RocketMQ 检查。
- 精确密钥扫描确认真实凭据未进入仓库或 Obsidian 文档。

## 回溯结论

第 150 阶段完成后，完整 JDBC 工作台流程不再产生 `workbench-state`、`local-execution` 或 `runtime-notifications` 主快照写入；这些旧切片只保留为历史兼容与空表回填来源。
