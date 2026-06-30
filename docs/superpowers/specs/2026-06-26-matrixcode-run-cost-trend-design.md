# MatrixCode 第 65 阶段设计：运行成本趋势与模型请求分页查询

## 背景

第 64 阶段已经让模型请求携带 Prompt 分区 trace，运行中心可以按选中 Agent 运行展示成本和分区线索。当前缺口是运行中心仍依赖工作台 `recentRequests` 全量数组做本地聚合，随着模型请求增长会导致首屏负担变重，也缺少按运行分页查看请求明细和成本变化趋势的入口。

## 推荐方案

第 65 阶段采用只读增量接口，不新增表：

1. 后端新增 `ModelRunRequestPage`，包含：
   - `projectId`
   - `agentRunId`
   - `page`
   - `size`
   - `total`
   - `metrics`
   - `trend`
   - `requests`
2. 后端新增 `ModelCostTrendPoint`，按请求时间输出低敏趋势点：
   - `requestId`
   - `createdAt`
   - `cacheHitRate`
   - `estimatedCost`
   - `currency`
   - `cacheSource`
3. `ModelGatewayService` 增加按 `agentRunId` 过滤、按创建时间倒序分页的方法；趋势按创建时间升序输出最近 12 个请求。
4. 控制器新增：
   - `GET /api/projects/{projectId}/model-gateway/agent-runs/{agentRunId}/model-requests?page=0&size=20`
5. 桌面端运行中心按选中 Agent 运行加载分页结果，展示：
   - 明细总数、页码、每页数量
   - 成本趋势最近点
   - 最近分页请求 ID、缓存来源、命中率和费用

## 边界

- 不保存或返回 prompt 正文、模型响应正文、向量正文、工具输出、API Key 或数据库密码。
- 不新增 Redis/RocketMQ 业务依赖。
- 不新增 DDL；复用 `matrixcode_model_requests` 中已有运行关联、缓存 trace 和分区 trace。
- 工作台主摘要保持现状；运行中心只在需要查看具体 Agent 运行时按需加载分页数据。

## 验收

- 后端测试覆盖分页参数、按 `agentRunId` 过滤、趋势点和 metrics 聚合。
- 前端测试覆盖 API client URL 与运行中心展示。
- 桌面端目标测试、桌面端全量、构建、服务端目标测试、服务端全量、真实集成、浏览器核验、静态检查和密钥扫描通过。
