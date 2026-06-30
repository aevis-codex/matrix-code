# MatrixCode 模型缓存 trace 可观测性设计

## 背景

第 51 阶段已经让模型网关优先读取供应商真实 prompt cache usage，并在 DeepSeek 请求中使用规范化缓存作用域。当前仍存在上线缺口：工作台和模型请求记录只能看到 token 数字，看不到这些数字来自供应商真实字段还是本地估算，也看不到稳定前缀 fingerprint，后续很难持续优化缓存命中。

## 目标

- 在模型请求用量中保存缓存来源、缓存作用域、稳定前缀 hash 和是否使用供应商真实 usage。
- 在正式 MySQL `matrixcode_model_requests` 中持久化这些字段，新增字段必须带注释。
- 在工作台右侧“模型网关”指标中展示缓存来源、fingerprint 和最近请求缓存命中率。
- 保持前端现有布局清爽，不新增大块页面，不把密钥、完整 prompt 或敏感上下文写入持久化。

## 方案

推荐方案：扩展 `UsageRecord` 为模型缓存 trace 的承载对象。

- `UsageRecord` 新增字段：
  - `cacheSource`：`PROVIDER` 或 `ESTIMATED`。
  - `cacheScopeId`：MatrixCode 内部缓存作用域，已规范化，不含密钥。
  - `stablePrefixHash`：提示词契约的稳定前缀 hash。
  - `providerUsageAvailable`：本次是否使用供应商真实 prompt cache 字段。
- 保留旧构造器，避免历史测试和旧代码大面积改动。
- `ModelGatewayService` 在构建 usage 时写入元数据。
- `matrixcode_model_requests` 新增同名字段，通过 Flyway 迁移补注释，并同步 MyBatis-Plus 与旧 JDBC 兼容仓储。
- 桌面端 API 类型扩展字段；右侧模型网关卡片只展示三行高信号信息：缓存来源、最近命中率、prefix fingerprint。

## 非目标

- 不记录完整 system prompt、user prompt、API Key、数据库密码或模型响应全文。
- 不新增 Redis/RocketMQ 运行依赖。
- 不把 provider 专属缓存策略配置成复杂 UI；第 52 阶段只做可观测性。
- 不改变现有模型请求 API 路径和角色配置 UI。

## 数据流

1. `ModelGatewayService` 生成 `cacheScopeId` 和 `PromptContract.stablePrefixHash()`。
2. 模型客户端返回 `ModelCompletionResult`，其中可能包含供应商真实 `ProviderTokenUsage`。
3. 服务层根据真实 usage 是否存在构造 `UsageRecord`：
   - 有真实 usage：`cacheSource=PROVIDER`，`providerUsageAvailable=true`。
   - 无真实 usage：`cacheSource=ESTIMATED`，`providerUsageAvailable=false`。
4. `ModelRequestRecord` 进入正式仓储。
5. MyBatis-Plus/JDBC 仓储持久化新增 trace 字段。
6. 工作台接口返回 recentRequests，桌面端右侧模型网关卡片展示最近请求 trace。

## 验证标准

- `UsageRecord` 新增字段具备兼容构造器和非空默认值。
- `ModelGatewayServiceTest` 红绿覆盖真实 usage 记录 `PROVIDER`，本地估算记录 `ESTIMATED`。
- `MybatisPlusProjectActivityRepositoryTest` 红绿覆盖新增字段保存和恢复。
- `JdbcProjectActivityRepositoryTest` 红绿覆盖旧 JDBC 兼容仓储保存和恢复。
- `DatabaseMigrationCommentPolicyTest` 覆盖新增迁移字段注释。
- 桌面端 `App.test.tsx` 覆盖右侧模型网关 trace 展示。
- 服务端全量、桌面端测试、构建、真实集成、静态检查和密钥扫描通过。

## 回溯对齐

- 对齐最初目标：多人协作智能体控制台需要可追踪、可审计、可调优的模型运行链路。
- 对齐用户新增要求：参考 DeepSeek-Reasonix，通过稳定前缀和缓存可观测性降低 token 成本。
- 对齐工作台要求：右侧只显示运行指标和关键事件，本阶段只在现有模型网关卡片补高密度指标，不扩展混乱布局。
- 对齐上线约束：正式数据使用 MySQL 和 MyBatis-Plus；H2 仅用于测试；新增 DDL 字段写注释。
