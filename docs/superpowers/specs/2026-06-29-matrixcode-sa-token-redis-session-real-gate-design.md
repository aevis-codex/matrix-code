# MatrixCode 第 153 阶段：Sa-Token Redis Session 真实门禁

## 背景

第 92 阶段已经接入 Sa-Token Redis Session，第 111 阶段已把 Redis Session 作为生产门禁配置要求，但 `check-real-runtime.sh` 的协议级测试仍只覆盖真实 MySQL/Flyway 和 Milvus 写入召回。多人实时协作控制台上线前，需要把 Redis Session 的真实写读删能力纳入同一个可重复执行的真实运行门禁。

## 目标

- 当 `MATRIXCODE_PROTOCOL_CHECK=true` 且 `MATRIXCODE_AUTH_SESSION_STORE=redis` 时，真实运行脚本必须执行 `RealSaTokenRedisSessionIntegrationTest`。
- 保持本地轻量检查行为不变：未开启协议检查或显式跳过连通性时，不启动 Maven 集成测试。
- 不新增 DDL，不改变 Sa-Token 登录、续期、踢下线和项目权限语义。

## 实现

- `scripts/check-real-runtime.sh` 在 Redis Session 模式下追加 `RealSaTokenRedisSessionIntegrationTest#真实Redis可承载SaToken会话数据`。
- `scripts/check-real-runtime-production-test.sh` 使用 fake `nc` 和 fake Maven 验证协议选择器，避免脚本单测依赖真实外部服务。

## 验证

- 红灯：`bash scripts/check-real-runtime-production-test.sh` 先失败，输出缺少 `RealSaTokenRedisSessionIntegrationTest#真实Redis可承载SaToken会话数据`。
- 绿灯：`bash scripts/check-real-runtime-production-test.sh` 通过。
- 生产门禁：`bash scripts/verify-production-readiness.sh` 17/17 通过。
- 真实协议：`MATRIXCODE_PROTOCOL_CHECK=true ./scripts/check-real-runtime.sh .env.local` 3 条通过，其中 `RealSaTokenRedisSessionIntegrationTest` 1 条、`RealRuntimeIntegrationTest` 2 条；真实 MySQL `matrix_code` Flyway 维持 v150.1，MySQL/Milvus/Redis/RocketMQ 连通。

## 回溯结论

跨节点 Sa-Token/Redis Session 的上线缺口从“有真实集成测试但未进入统一门禁”收敛为“生产协议门禁自动覆盖”。模型请求和编码智能体交付回溯用户归属已分别在第 40、156 阶段补齐，后续重点转向真实发布演练和生产观测闭环。
