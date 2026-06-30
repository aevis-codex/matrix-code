# MatrixCode 第 64 阶段设计：Prompt 编排器与稳定前缀分区治理

## 背景

第 63 阶段已经把缓存策略下沉到角色智能体配置，并在运行中心按 Agent 运行展示模型用量与缓存命中。当前缺口是：`PromptContract` 仍只暴露完整 `systemPrefix` 与稳定前缀 hash，缺少可机读的 Prompt 分区结构。

参考 DeepSeek-Reasonix 和 DeepSeek KV Cache 的核心思路，下一步应该把“稳定前缀”和“动态后缀”变成明确、可测试、可观测的工程边界，而不是只依赖字符串拼接约定。

## 推荐方案

第 64 阶段采用低风险增量方案：

1. 新增 `PromptPartition` 领域对象，描述分区 key、名称、稳定性、是否可缓存和估算 token。
2. 扩展 `PromptContract`：
   - `promptPartitionPolicyId`
   - `promptPartitionFingerprint`
   - `partitions`
   - `stablePartitionCount`
   - `volatilePartitionCount`
3. `PromptContractBuilder` 固定输出稳定区和动态区：
   - 稳定区：平台稳定规则、工具契约版本。
   - 动态区：角色系统提示词、上下文清单槽位、用户指令槽位。
4. `UsageRecord` 和 `matrixcode_model_requests` 保存低敏分区 trace：
   - 分区策略 ID
   - 分区指纹
   - 稳定分区数
   - 动态分区数
5. 模型请求进入 Agent Runtime trace 时带上分区 trace 元数据。
6. 桌面端只展示低敏分区策略和 fingerprint，不展示完整 prompt。

## 边界

- 不保存完整 prompt、系统提示词正文、用户指令、向量召回正文、工具输出、模型响应正文或密钥。
- 不改变 OpenAI-compatible 请求格式；供应商仍只收到现有 system/user messages。
- 不引入 Redis/RocketMQ 新运行依赖。
- 不改变角色配置 UI 的主要布局，只在运行中心补充短分区线索。

## 验收

- TDD 红绿覆盖 Prompt 分区结构、分区 fingerprint 稳定性、模型网关 usage trace、JDBC/MyBatis-Plus 持久化和 Agent Runtime trace。
- 前端测试覆盖运行中心展示 Prompt 分区线索。
- 全量前端测试、构建、服务端测试、真实集成测试通过。
- 第二大脑记录第 64 阶段，并回溯确认未偏离“一线可上线智能体控制台”目标。
