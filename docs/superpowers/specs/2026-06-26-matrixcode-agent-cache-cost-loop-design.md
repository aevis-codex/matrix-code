# MatrixCode 第 63 阶段设计：Agent 缓存策略与运行成本闭环

## 背景

MatrixCode 已经具备模型网关、供应商 usage 优先、本地稳定前缀缓存估算、模型请求 trace 和 Agent 运行关联。
当前缺口是：缓存策略仍由模型网关固定写入，角色智能体无法独立配置；运行中心能看到单条模型请求线索，但不能按选中 Agent 运行聚合 token、费用和缓存命中情况。

用户最新要求补充：

- 参考 DeepSeek-Reasonix 与 DeepSeek KV Cache 的思路，优先压榨供应商缓存命中能力，降低 token 成本。
- 每个角色智能体需要独立配置模型、提示词、颜色、字体和缓存相关策略。
- 真实可上线前必须能观测每次 Agent 运行的模型成本与缓存命中效果。

## 推荐方案

第 63 阶段采用“小步上线、强闭环”的方案：

1. `RoleAgentConfig` 增加 `cachePolicyId` 和 `volatileSuffixStrategy`。
   - `cachePolicyId` 表示稳定前缀/供应商 prompt cache 策略版本。
   - `volatileSuffixStrategy` 表示动态后缀处理方式，说明角色提示词、用户指令、向量召回和工具输出如何进入不稳定区。
2. MySQL 正式表 `matrixcode_role_agent_configs` 追加两个字段，并写表字段注释。
3. 模型网关生成 `UsageRecord` 时使用角色配置里的策略字段，不再硬编码。
4. 配置中心在角色智能体页暴露两个缓存字段，允许按角色独立调整。
5. 运行中心按选中 Agent 运行聚合关联模型请求：
   - 请求数
   - cache hit tokens
   - cache miss input tokens
   - output tokens
   - cache hit rate
   - estimated cost
   - cache source
   - policy/strategy

## 边界

- 本阶段只保存策略标识，不保存完整 prompt、模型响应、向量正文、工具输出或 API Key。
- 不改变供应商调用协议，不新增 Redis/RocketMQ 依赖。
- 继续保持 H2 仅测试，正式运行仍走 MySQL + Flyway。
- DeepSeek KV Cache 的关键点是“稳定前缀尽量固定、动态内容后置”，本阶段先把策略可配置和成本可观测补齐；后续可继续做 prompt 编排器把稳定前缀结构化锁定。

## 第 130 阶段增量

第 130 阶段在本设计上增加 `cacheScopeStrategy`，把缓存归因作用域也下沉到角色智能体配置：

- `provider-model`：默认策略，按项目、角色、供应商和模型隔离，避免跨模型误估算。
- `provider-role`：同项目、同角色、同供应商跨模型复用，用于同供应商模型变体的稳定前缀复用。
- `project-role`：同项目、同角色最大复用，只作为高级选项暴露。

该增量仍只保存低敏策略 ID，不保存 prompt、模型响应、向量正文、工具输出或密钥。

## 验收

- 后端红绿测试覆盖角色缓存策略默认值、更新恢复、JDBC/MyBatis-Plus 持久化、模型请求使用角色缓存策略。
- 前端红绿测试覆盖配置中心保存缓存策略，以及运行中心选中历史 Agent 运行后展示成本/缓存聚合。
- 全量前端测试、构建、后端测试、真实集成测试通过。
- 第二大脑更新第 63 阶段成果，并回溯确认最初需求未跑偏。
