# MatrixCode 模型请求运行 Trace 设计

## 背景

第 60 阶段已经让模型请求记录携带 `agentRunId`，并让工作台能区分“强关联模型请求”和“项目级模型请求线索”。但这仍然是模型请求侧的关联：Agent Runtime 的事件时间线里还看不到这次模型调用。

上线后的多人协作控制台需要按一次 Agent 运行复盘完整过程：任务规划、工具调用、模型请求、缓存命中、失败恢复和交接输出都必须进入同一条低敏审计流。

## 目标

当模型网关收到带 `agentRunId` 的请求并完成模型调用后，自动在 Agent Runtime 中追加一条标准 `TOOL_TRACE` 事件。事件 payload 只保存模型请求 ID、供应商、模型、缓存来源、稳定前缀指纹和缓存策略等低敏摘要，不保存 prompt、响应正文、工具输出、向量召回正文或密钥。

## 推荐方案

- 在 `AgentRuntimeService` 增加 `appendModelRequestTrace(...)`，复用现有 `appendToolTrace(...)` 事件格式。
- 在 `ModelGatewayService` 注入可选 `AgentRuntimeService`。
- `ModelGatewayService.request(...)` 成功创建 `ModelResponse` 和 `ModelRequestRecord` 后，如果 `command.agentRunId()` 非空，则调用 `appendModelRequestTrace(...)`。
- 没有正式 Runtime 仓储时保持无副作用，文件模式和测试模式继续可运行；正式 MySQL 仓储存在时，trace 进入 `matrixcode_agent_run_events`。

## 边界

- 不新增 Flyway 迁移，复用现有 Agent Runtime 事件表。
- 不伪造编码智能体已经发起模型请求；本阶段只打通模型网关到运行时间线的真实桥接。
- `agentRunId` 为空时不写 Runtime 事件，避免把普通工作台模型请求误归属到某次运行。
- trace metadata 不写入 prompt、响应正文、API Key、数据库密码、完整命令输出或大体积上下文。

## DeepSeek-Reasonix 对齐点

Reasonix 的公开说明强调配置驱动、OpenAI 兼容供应商抽象、长会话稳定前缀缓存和执行/规划模型可组合。本阶段对应落地的是“缓存可观测”和“低敏执行日志”：每次模型请求都把 `cacheSource`、`stablePrefixHash`、`cachePolicyId`、`volatileSuffixStrategy` 写入运行 trace，为后续多模型 planner/executor 会话和缓存命中优化提供数据基础。

## 验收标准

- `AgentRuntimeService.appendModelRequestTrace(...)` 生成 `TOOL_TRACE`，payload 包含 `toolName=model-gateway.model-requests`、`action=complete-model-request`、模型供应商、模型、缓存来源、稳定前缀指纹和缓存策略。
- `ModelGatewayService.request(...)` 使用带 `agentRunId` 的命令后，Runtime 仓储出现引用本次 `requestId` 的模型 trace。
- `agentRunId` 为空时不追加模型 Runtime trace。
- 服务端目标测试、服务端全量测试、桌面端测试与构建、真实集成、静态检查和密钥扫描通过。
