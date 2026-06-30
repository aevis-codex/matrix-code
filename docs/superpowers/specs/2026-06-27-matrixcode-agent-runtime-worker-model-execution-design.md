# MatrixCode Agent Runtime Worker 受控模型执行设计

## 背景

第 71 阶段已经能为当前认领人和有效租约的 `RUNNING` 运行生成执行计划。下一步不能直接开放命令、文件写入或 Patch；应先把“由 Worker 触发一次模型网关请求，并把请求强关联回 Agent Run”落地为受控、低敏、可审计的运行切片。

## 推荐方案

采用独立应用服务 `AgentRuntimeWorkerModelExecutionService`：

- 先复用 `AgentRuntimeWorkerService.prepareExecution(...)` 校验项目、运行状态、认领人和租约。
- 阻塞时返回 `executed=false` 和阻塞原因，不调用模型，不写模型请求完成事件。
- 可执行时读取运行主记录，把 `roleKey` 映射为 `ModelRole`，使用运行目标和摘要生成模型请求指令。
- 调用 `ModelGatewayService.request(...)`，并传入 `actorUserId=workerId`、`agentRunId=runId`，复用已有模型请求强关联、缓存 trace、项目事件和 `TOOL_TRACE` 写入链路。
- 模型请求完成后追加低敏 `WORKER_MODEL_REQUEST_COMPLETED` 事件，只记录 requestId、providerId、modelName、cacheSource 和 answerSummary。

## API

新增：

```http
POST /api/projects/{projectId}/agent-runs/{runId}/worker-model-request?workerId=...
```

返回 `AgentRuntimeWorkerModelExecutionResult`：

- `projectId`
- `runId`
- `workerId`
- `executed`
- `blockedReason`
- `requestId`
- `providerId`
- `modelName`
- `answerSummary`

## 边界

- 不新增 Flyway DDL。
- 不执行命令、不读写文件、不应用 Patch。
- 不保存 prompt 正文、模型响应全文、向量正文、命令输出、文件内容、API Key 或数据库密码。
- 不把模型请求成功等同于整个 Agent Run 成功；本阶段只写模型请求完成事件，不自动 `markSucceeded(...)`。
- Redis 和 RocketMQ 本阶段仍不引入业务运行依赖。

## 验收

- 当前认领人且租约有效时，Worker 能触发模型请求，返回 requestId/provider/model/answerSummary。
- 模型请求记录的 `agentRunId` 等于当前运行 ID。
- Agent Runtime 事件包含模型网关已有 `TOOL_TRACE` 和新增 `WORKER_MODEL_REQUEST_COMPLETED`。
- 非认领人或过期租约返回阻塞结果，不调用模型，不写完成事件。
- HTTP 端点返回同一结果结构。
- 服务端全量、桌面端全量、桌面构建、真实运行检查、真实集成、静态检查和敏感信息扫描通过。
