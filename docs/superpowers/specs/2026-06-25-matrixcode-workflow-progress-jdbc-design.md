# MatrixCode 第 37 阶段：工作流与验收投影正式 MySQL 仓储设计

## 背景

第 33 至 36 阶段已经完成真实基础设施接入、部署运行态正式仓储、外部模型真实调用、项目活动正式仓储。当前剩余的 `workbench-state` 依赖中，工作流状态和产品验收结论仍以 JSON 快照保存，不利于真实上线后的查询、迁移和排障。

用户最新约束：

- MySQL schema/database 使用 `matrix_code`。
- Milvus database 使用 `matrix_code`。
- 继续自动执行阶段闭环，不再等待确认。
- 每阶段完成后更新 Obsidian `MatrixCode` 项目图谱。

## 目标

将工作流状态与验收投影迁移到正式 MySQL 表：

- `matrixcode_workflow_items`
- `matrixcode_workflow_events`
- `matrixcode_acceptance_states`

服务层优先读取正式仓储；正式仓储为空且旧 `workbench-state` 有数据时，自动回填正式表。非 JDBC 模式保持现有 `WorkbenchStateStore` 行为。

## 推荐方案

采用“新增专用仓储 + 空表回填”的保守演进方案。

### 方案对比

1. 继续扩展 `WorkbenchStateStore`
   - 优点：改动少。
   - 缺点：仍然是 JSON 快照，不满足真实上线查询和迁移要求。

2. 将工作流和验收合并进 `ProjectActivityRepository`
   - 优点：少一个接口。
   - 缺点：活动流、模型请求、工作流状态、验收状态边界混乱。

3. 新增 `WorkbenchProgressRepository`
   - 优点：边界清晰，专门承接流程进度和验收投影；旧快照可作为兼容回填源。
   - 缺点：新增一个仓储和一组迁移表。

采用方案 3。

## 架构

新增应用层接口 `WorkbenchProgressRepository`，由 JDBC 实现 `JdbcWorkbenchProgressRepository` 提供真实 MySQL 持久化。

`WorkflowService`：

- 有正式仓储时：从 `WorkbenchProgressRepository` 读取工作项和事件。
- 正式仓储为空时：读取旧 `WorkbenchStateStore` 并回填正式表。
- 写入时：有正式仓储则只写正式表；否则保持写旧快照。

`WorkbenchService`：

- 有正式仓储时：从 `WorkbenchProgressRepository` 读取验收状态。
- 正式仓储为空时：读取旧 `WorkbenchStateStore` 并回填正式表。
- 提交验收时：有正式仓储则只写正式表；否则保持写旧快照。

## 数据表

`matrixcode_workflow_items`：

- `id`
- `project_id`
- `title`
- `state`
- `created_at`
- `updated_at`

`matrixcode_workflow_events`：

- `id`
- `project_id`
- `item_id`
- `event_type`
- `from_state`
- `to_state`
- `actor_id`
- `occurred_at`

`matrixcode_acceptance_states`：

- `project_id`
- `document_id`
- `accepted`
- `return_to_role`
- `updated_at`

## 数据兼容

初次启用 JDBC 正式表时，如果正式表为空：

- 工作流从 `workbench-state.workflowItems` 和 `workbench-state.workflowEvents` 回填。
- 验收投影从 `workbench-state.acceptances` 回填。

回填后新写入只进入正式表，避免继续扩大 JSON 快照依赖。

## 测试策略

- JDBC 仓储测试：验证三张表可保存、恢复、自动补项目记录。
- 服务接入测试：验证正式仓储优先、空仓储回填旧快照、写入不再污染旧快照。
- 回归测试：运行工作流、工作台、真实运行态相关测试。
- 真实验证：Flyway 在 MySQL `matrix_code` 上迁移到第 37 阶段，真实服务 health 正常。

## 风险与约束

- `WorkflowEvent` 没有 `projectId` 字段，JDBC 写事件时需要通过工作项映射补齐 `project_id`。
- 历史旧快照中的孤儿事件如果找不到工作项，将不写入正式表，避免违反项目外键。
- 工作流当前没有公开列表接口，本阶段只迁移持久化，不扩大 API 面。
