# MatrixCode Agent 运行与模型请求强关联设计

## 背景

第 59 阶段的运行中心已经能聚合最新 Agent 运行、工具事件、失败摘要和最近模型请求缓存线索。但“最近模型请求”只是项目级线索，不能作为审计强关联。真实上线后，一个项目内会并行存在多个角色智能体和多次模型调用，必须能判断某次模型请求是否明确属于某次 Agent 运行。

## 目标

模型请求从命令入口、领域记录、正式表持久化到桌面端展示都携带可选 `agentRunId`。只有请求记录显式带有 `agentRunId` 且匹配当前运行时，前端才展示“已关联运行”。

## 推荐方案

- 在 `ModelRequestCommand` 和 `ModelRequestRecord` 增加可选 `agentRunId`，保留旧构造器兼容现有调用。
- 在 `ModelGatewayService` 创建模型请求记录时写入 `command.agentRunId()`。
- 在 `matrixcode_model_requests` 增加 `agent_run_id` 字段和项目维度索引，字段注释说明只保存低敏关联 ID。
- 在 JDBC 仓储和 MyBatis-Plus 实体中保存/恢复 `agent_run_id`。
- 在 `ModelGatewayController` 请求体中允许传入 `agentRunId`。
- 在桌面端 `ModelRequestRecord` 类型中暴露 `agentRunId`，审计详情优先展示与最新运行匹配的模型请求；没有匹配项时仍展示最近请求线索，但不标记强关联。

## 替代方案与取舍

- 方案 A：在模型请求表增加可选 `agent_run_id`。实现成本低、查询直接、兼容历史数据，适合当前阶段。
- 方案 B：新增 `agent_run_model_requests` 关联表。适合一个模型请求关联多个运行的复杂场景，但当前模型请求天然由一次运行触发，建关联表会增加写入和迁移复杂度。
- 方案 C：只通过事件 payload 记录模型请求 ID。成本最低，但审计查询必须解析 JSON，且无法保证持久化层一致性。

推荐方案 A。它把强关联放在低敏、结构化、可索引字段上，符合上线审计和后续查询扩展需求。

## 边界

- `agent_run_id` 可为空，历史模型请求不回填，不强行建立外键。
- 不存储 prompt、模型响应正文、工具输出、向量召回正文、API Key、数据库密码或异常堆栈全文。
- 本阶段只建立模型请求到运行的结构化关联，不改变 Agent Runtime 的调度模型。
- 前端显示“已关联”只基于 `request.agentRunId === latestAgentRun.id`。

## 验收标准

- `ModelGatewayService` 使用带 `agentRunId` 的命令后，最近模型请求记录包含该运行 ID。
- JDBC 仓储保存并恢复 `agentRunId`。
- MyBatis-Plus 仓储保存并恢复 `agentRunId`。
- Flyway 新增 `agent_run_id` 字段，字段带详细注释并通过迁移测试。
- 桌面端审计详情显示 `模型请求 request-1 · 已关联 run-1 · PROVIDER · prefix fp-cache-001`。
- 无显式匹配时，前端仍只展示普通“模型请求线索”。
- 服务端全量测试、桌面端全量测试与构建、真实集成、迁移、静态检查和密钥扫描通过。
