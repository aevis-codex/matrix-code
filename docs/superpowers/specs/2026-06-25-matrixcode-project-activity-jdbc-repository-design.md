# 项目活动正式 MySQL 仓储设计

## 背景

第 34 阶段已将部署运行态迁移到正式 MySQL 表。当前模型请求记录和项目事件仍保存在 `workbench-state` 快照中；真实多人实时协作控制台上线后，这两类数据分别承载模型审计、成本统计、工作区事件流，不能继续依赖单个快照切片。

## 目标

- 为模型请求记录和项目事件建立正式 MySQL 表。
- JDBC 模式下服务优先读写正式表；正式表为空时从旧 `workbench-state` 回填。
- 保持现有 API、事件流、模型网关摘要和桌面端字段不变。
- 保持文件模式和内存测试模式兼容。

## 推荐方案

新增 `ProjectActivityRepository` 聚合仓储，覆盖两类项目活动数据：

- `matrixcode_model_requests`：本阶段新增，用于模型请求审计、成本和 token 统计。
- `matrixcode_project_events`：复用第 18 阶段核心迁移中已有的正式事件表，避免重复建表。

理由：

- 模型请求完成后会立即发布项目事件，两者在运行态审计上属于同一条项目活动链路。
- 服务层仍保持现有职责：`ModelGatewayService` 维护模型请求、`ProjectEventBus` 维护事件发布和订阅。
- 聚合仓储可以复用最小项目记录 upsert、迁移触发、空表回填和按项目替换逻辑。

## 数据流

1. Spring JDBC 模式启用时注册 `JdbcProjectActivityRepository`。
2. `ModelGatewayService` 启动时：
   - 正式表有模型请求则加载正式表；
   - 正式表为空则从旧快照加载并写入正式表。
3. `ProjectEventBus` 启动时：
   - 正式表有项目事件则加载正式表；
   - 正式表为空则从旧快照加载并写入正式表。
4. 新增模型请求或项目事件时：
   - JDBC 模式写正式表；
   - 非 JDBC 模式继续写 `WorkbenchStateStore`。

## 错误处理

- JDBC URL 为空或 SQL 失败时抛出带中文上下文的 `IllegalStateException`。
- `project_id` 不存在时仓储自动创建最小项目记录。
- `context_types` 使用 JSON 文本保存；用量字段拆列，便于后续按成本和 token 统计。
- 项目事件映射到已有 `matrixcode_project_events`：`type -> event_type`、`message -> title/payload`、`occurredAt -> created_at/updated_at`。

## 验证

- 新增 JDBC 仓储测试，覆盖模型请求和项目事件完整字段保存恢复。
- 新增服务接入测试，覆盖正式仓储优先、旧快照回填、模型请求写正式仓储、事件订阅不受影响。
- 运行局部测试、服务端全量测试、真实基础设施集成、diff 检查、敏感信息扫描和旧 schema 扫描。
