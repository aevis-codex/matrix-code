# 运行态提醒正式 MySQL 仓储设计

## 背景

第 28、29、30 阶段已经把文档中心、Bug 缺陷闭环、本地执行与审批审计迁移到正式 MySQL 领域表优先。运行态提醒当前仍通过 `JdbcRuntimeNotificationStore` 写入 `matrixcode_state_snapshots` 的 `runtime-notifications` 切片。

运行态提醒已经在第 18 阶段具备正式表 `matrixcode_runtime_notifications`。因此本阶段只需要切换 JDBC Store 的主读写路径，不需要改变提醒生成逻辑、工作台 API 或桌面端展示。

## 目标

在 JDBC 模式下，让运行态提醒优先读写正式领域表：

- `JdbcRuntimeNotificationStore.save()` 写入 `matrixcode_runtime_notifications`。
- `JdbcRuntimeNotificationStore.load()` 优先从正式表组装 `RuntimeNotificationSnapshot`。
- 正式表为空且旧 `runtime-notifications` 快照存在时，从旧快照恢复并回填正式表。
- 正式表有数据时，不再读取旧快照作为主数据源。
- 后续同步、单条已读、全部已读仍由 `RuntimeNotificationService` 保持现有行为。

## 非目标

- 不改运行态提醒 API。
- 不改桌面端提醒 UI。
- 不引入 Redis 或 RocketMQ 运行依赖。
- 不新增用户级已读模型；当前 `user_id` 仍为空，后续用户身份阶段再接入。
- 不迁移部署、Compose、模型请求或项目事件仓储。

## 方案

改造 `JdbcRuntimeNotificationStore`：

1. 复用 `RuntimeNotificationStore` 接口。
2. 通过 `JdbcSnapshotRepository.properties()` 取得 JDBC 配置，直连正式表。
3. `load()` 先查 `matrixcode_runtime_notifications`，按项目聚合为 `RuntimeNotificationSnapshot`。
4. 如果正式表为空，读取旧 `runtime-notifications` 快照；旧快照有效且非空时，批量写入正式表。
5. `save()` 以事务方式替换当前所有运行态提醒记录，并确保项目存在。
6. 保留旧快照读取逻辑只用于回填和损坏快照兼容。

字段映射：

- `RuntimeNotification.id` -> `matrixcode_runtime_notifications.id`
- `projectId` -> `project_id`
- `level` -> `level_key`
- `sourceType` -> `source_type`
- `sourceId` -> `source_id`
- `title` -> `title`
- `message` -> `message`
- `occurredAt` -> `created_at`
- `readAt` -> `read_at`
- `updated_at` 使用 `readAt`，为空时使用 `occurredAt`
- `user_id` 暂为空

## 测试策略

- `JdbcRuntimeNotificationStoreTest` 增加正式表持久化断言：保存提醒和已读时间后重建 Store，可从正式表恢复；正式表有记录；旧 `runtime-notifications` 快照不新增。
- `JdbcRuntimeNotificationStoreTest` 增加旧快照回填断言：先写旧快照，再通过新 Store 读取，确认恢复并回填正式表。
- `JdbcRuntimeNotificationStoreTest` 保留损坏旧快照返回空快照断言。
- `JdbcPersistenceSpringTest` 增加 JDBC 模式端到端断言：工作台触发提醒生成后，重启仍可恢复提醒；`matrixcode_state_snapshots` 不再包含 `runtime-notifications` 和 `local-execution`。
- 运行 `JdbcRuntimeNotificationStoreTest`、`RuntimeNotificationServiceTest`、`JdbcPersistenceSpringTest` 和服务端全量测试。

## 回溯对齐

- 对齐多人实时协作智能体控制台目标：待审批、任务终态和 Compose 结果提醒需要跨重启恢复，并能被正式数据库查询。
- 对齐真实基础设施决策：业务提醒状态进入 MySQL；Redis/RocketMQ 继续只按真实多人协作需求接入。
- 对齐安全边界：本阶段只改变运行态提醒持久化，不改变审批策略、本地执行权限或模型供应商配置。
- 对齐阶段 11、13、14 的验收结果：本阶段是提醒中心从 JSON/JDBC 快照到正式表的升级，不否定既有提醒中心能力。
