# MatrixCode 第 152 阶段：项目级长期模型成本趋势

## 背景

第 65 阶段已经支持按单次 Agent 运行分页查看模型请求和成本趋势，但项目首页右侧指标仍缺少跨运行、跨角色的长期模型成本视角。用户要求参考 DeepSeek-Reasonix 等项目持续压榨模型缓存命中机制，因此需要把 `matrixcode_model_requests` 中已有的 usage 和 cache trace 聚合成项目级趋势。

## 目标

- 新增项目级模型成本趋势 API，支持按最近 N 天聚合。
- 返回总 usage 指标、UTC 日粒度趋势、角色维度、供应商维度和模型维度分组。
- 桌面端右侧运行指标展示 30 天费用、请求数、最近日趋势和主要角色成本。
- 不新增 DDL，不保存 prompt、模型响应正文、向量召回正文、工具输出或密钥。

## 实现

- `ModelGatewayController` 新增 `GET /api/projects/{projectId}/model-gateway/cost-trends?days=30`。
- `ModelGatewayService.projectCostTrends(...)` 复用已持久化的 `ModelRequestRecord` 聚合，窗口限制为 1 到 180 天。
- 新增 `ModelCostTrendReport`、`ModelCostTrendBucket` 和 `ModelCostBreakdown` 低敏 DTO。
- 桌面端新增 `loadProjectModelCostTrends(...)`，右侧 `InspectorPanel` 拉取并展示项目级趋势摘要。

## 验证

- 红灯：`ModelGatewayControllerTest` 新增 3 条测试先因接口不存在返回 404。
- 绿灯：`ModelGatewayControllerTest,ModelGatewayServiceTest` 36 条通过。
- 桌面端：`npm test` 139 条通过；`npm run build` 通过。
- 服务端全量：`mvn -pl server test` 624 条通过、0 failures、0 errors、8 skipped。
- 生产门禁：`verify-production-readiness.sh` 17/17 通过；`git diff --check` 通过；真实凭据精确扫描通过。
- 真实协议：`MATRIXCODE_PROTOCOL_CHECK=true ./scripts/check-real-runtime.sh .env.local` 2 条通过，真实 MySQL `matrix_code` Flyway 维持 v150.1，MySQL/Milvus/Redis/RocketMQ 连通。

## 回溯结论

长期模型成本趋势缺口已从“仅按单次 Agent Run 复盘”推进到“项目级可观测”。跨节点 Sa-Token/Redis Session 已在第 153 阶段进入真实门禁，模型请求和编码交付回溯用户归属已分别在第 40、156 阶段补齐，后续重点转向真实发布演练和生产观测闭环。
