# MatrixCode 模型网关稳定前缀治理设计

## 背景

用户要求模型网关参考 DeepSeek-Reasonix 的缓存命中设计，最大化利用各模型厂商的前缀缓存机制，降低 token 成本。DeepSeek 官方上下文缓存说明强调缓存依赖请求前缀稳定；Reasonix 的核心方向是把稳定规则和动态记忆分层，避免动态内容破坏缓存命中。

当前 MatrixCode 已完成第 51、52 阶段：能够解析供应商 prompt cache usage、生成缓存作用域、持久化缓存来源和 prefix fingerprint。但 `PromptContractBuilder` 仍把模型名和角色自定义系统提示词一起写入稳定前缀 hash。角色提示词是高频配置项，模型名也已经由 `cacheScopeId` 隔离，因此它们不应污染稳定前缀 fingerprint。

参考资料：

- DeepSeek Context Caching：`https://api-docs.deepseek.com/guides/kv_cache`
- DeepSeek-Reasonix：`https://github.com/esengine/DeepSeek-Reasonix`

## 目标

- 把“真正可长期稳定的 MatrixCode 平台系统前缀”和“角色自定义提示词”解耦。
- 保持发送给模型的 system prompt 语义不变：角色自定义系统提示词仍然进入 system prompt。
- `stablePrefixHash` 和 `estimatedStablePrefixTokens` 只基于稳定平台前缀，不受模型名和角色自定义提示词变化影响。
- 本地缓存估算在角色提示词调整后仍能命中稳定平台前缀，避免把所有输入都视为 cache miss。
- 不新增数据库迁移，不改变供应商 API Key、真实模型请求和上下文门禁边界。

## 非目标

- 不实现跨请求 prompt 压缩器。
- 不自动改写用户配置的角色系统提示词。
- 不把动态上下文、向量召回、工具输出或完整文件内容塞进稳定前缀。
- 不新增 Redis/RocketMQ 业务依赖。

## 方案

### 稳定前缀分层

`PromptContractBuilder` 生成两段内容：

- 稳定平台前缀：MatrixCode 平台规则、当前角色、上下文门禁规则、输出契约、工具契约版本和缓存策略版本。
- 角色配置后缀：用户在角色配置中心维护的系统提示词。

`PromptContract.systemPrefix()` 仍返回完整 system prompt：稳定平台前缀 + 角色配置后缀。模型行为保持一致。

`PromptContract.stablePrefixHash()` 和 `PromptContract.estimatedStablePrefixTokens()` 只使用稳定平台前缀。模型名不再进入 hash，因为供应商和模型已经由 `cacheScopeId` 隔离。

### 本地估算

`PromptCacheEstimator` 继续以 `cacheScopeId + stablePrefixHash` 作为本地估算 key。由于角色提示词变化不再改变 `stablePrefixHash`，同一项目、角色、供应商和模型下，角色配置微调后仍可估算稳定平台前缀命中。

### 安全边界

稳定前缀不得包含真实 API Key、数据库密码、完整 prompt、完整工具输出或向量召回正文。角色自定义系统提示词仍可进入 system prompt，但不进入稳定前缀 hash 计算。

## 测试策略

- `PromptContractBuilderTest`：
  - 角色提示词变化不改变 `stablePrefixHash`。
  - 模型名变化不改变 `stablePrefixHash`。
  - 完整 system prompt 仍包含角色提示词。
  - `estimatedStablePrefixTokens` 不随长角色提示词增加。
- `ModelGatewayServiceTest`：
  - 同一缓存作用域内，角色系统提示词变化后第二次请求仍命中稳定平台前缀。
  - `stablePrefixHash` 在两次请求中保持一致。

## 回溯对齐

- 对齐最初需求：多人实时协作智能体控制台中，每个角色都有独立配置；配置可变，但平台缓存前缀应稳定。
- 对齐 DeepSeek/Reasonix 思路：固定系统规则前置，动态角色配置和上下文后置，减少无意义 cache miss。
- 对齐第 51、52 阶段：继续复用供应商真实 usage 优先、本地估算兜底和 prefix fingerprint 可观测字段。
- 对齐上线要求：不新增 DDL，不引入 H2 正式依赖，不保存密钥。
