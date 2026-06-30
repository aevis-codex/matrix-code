# MatrixCode 第 65 阶段计划：运行成本趋势与模型请求分页查询

## 目标

把 Agent 运行的模型请求从工作台全量 recentRequests 本地聚合，推进到后端按运行过滤、分页、趋势聚合的低敏查询接口，让运行中心具备持续运营成本的基础能力。

## 步骤

- [x] 1. 红测：后端按运行分页接口、趋势聚合、前端 API client 和运行中心展示。
- [x] 2. 后端领域：新增模型请求分页结果与成本趋势点对象。
- [x] 3. 后端服务/API：`ModelGatewayService` 按 `agentRunId` 查询，控制器暴露只读分页接口。
- [x] 4. 前端实现：client 类型与请求函数，运行中心按选中 Agent 运行加载分页结果并展示趋势。
- [x] 5. 验证：目标测试、全量测试、构建、真实集成、浏览器核验、静态检查和密钥扫描。
- [x] 6. 第二大脑：更新项目首页、阶段索引、模块地图、技术栈、模型网关专题和风险。
- [x] 7. 提交并推送到 `origin master`。

## 回溯检查点

- 多人实时协作智能体控制台：运行中心继续承担成本复盘和 Agent 审计，不改变角色工作台主流程。
- 每个角色独立智能体：查询按 `agentRunId` 过滤，天然归属具体角色智能体运行。
- 成本优化主线：承接第 51 到 64 阶段的 cache usage、cache policy、Prompt 分区 trace。
- 安全边界：只返回低敏模型请求摘要、用量和趋势，不返回 prompt 正文、响应正文、向量正文、工具输出或密钥。

## 完成验证记录

- 目标测试：`ModelGatewayControllerTest` 通过；`App.test.tsx,client.test.ts` 通过。
- 桌面全量：`Tests 95 passed`。
- 桌面构建：`npm --prefix desktop run build` 通过。
- 服务端全量：`tests=372 failures=0 errors=0 skipped=7`。
- 真实集成：`RealRuntimeIntegrationTest` 退出码 0，真实 MySQL `matrix_code` 保持 Flyway `v64.1`。
- 真实 API：运行 `b9e4b8a5-3abe-4db3-8195-21c5a07ca889` 返回 `total=2`、`trendCount=2`。
- 浏览器核验：运行中心展示 `请求分页 共 2 · 第 1 页 · 每页 20` 和 `成本趋势 ... PROVIDER`。
