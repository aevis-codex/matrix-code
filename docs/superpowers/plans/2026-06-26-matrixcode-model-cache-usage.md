# MatrixCode 模型缓存用量增强实现计划

> **面向 AI 代理的工作者：** 必需子技能：使用 superpowers:subagent-driven-development（推荐）或 superpowers:executing-plans 逐任务实现此计划。步骤使用复选框（`- [ ]`）语法来跟踪进度。

**目标：** 让模型网关优先读取供应商真实缓存 token 用量，并用稳定缓存作用域提高 DeepSeek 前缀缓存命中可解释性。

**架构：** 增加结构化模型调用结果类型；OpenAI 兼容客户端解析厂商 usage；服务层按“真实用量优先、估算兜底”计算指标。缓存作用域独立于用户可见 `roleSessionId`，避免破坏前端和历史数据。

**技术栈：** Java 21、Spring Boot、JUnit 5、AssertJ、Jackson、Maven。

---

## 文件结构

- 创建：`server/src/main/java/com/matrixcode/modelgateway/domain/ModelCompletionResult.java`，封装模型回答和可选供应商用量。
- 创建：`server/src/main/java/com/matrixcode/modelgateway/domain/ProviderTokenUsage.java`，封装厂商返回的 hit/miss/output token。
- 修改：`server/src/main/java/com/matrixcode/modelgateway/application/ModelCompletionClient.java`，返回结构化结果并接收缓存作用域。
- 修改：`server/src/main/java/com/matrixcode/modelgateway/application/OpenAiCompatibleModelClient.java`，解析厂商 usage，并为 DeepSeek 请求写入 `user_id`。
- 修改：`server/src/main/java/com/matrixcode/modelgateway/application/DeterministicModelAdapter.java`，本地模型返回结构化结果但不伪造厂商缓存字段。
- 修改：`server/src/main/java/com/matrixcode/modelgateway/application/ModelGatewayService.java`，优先使用真实 usage，缺省时使用估算。
- 修改：`server/src/main/java/com/matrixcode/modelgateway/application/PromptCacheEstimator.java`，明确参数语义为缓存作用域。
- 修改：`server/src/test/java/com/matrixcode/modelgateway/application/OpenAiCompatibleModelClientTest.java`，新增解析和 `user_id` 测试。
- 修改：`server/src/test/java/com/matrixcode/modelgateway/ModelGatewayServiceTest.java`，新增真实 usage 优先和缓存作用域隔离测试。

### 任务 1：客户端结构化返回

- [x] **步骤 1：编写失败的客户端测试**

在 `OpenAiCompatibleModelClientTest` 增加测试：响应体包含 `usage.prompt_cache_hit_tokens=128`、`usage.prompt_cache_miss_tokens=32`、`usage.completion_tokens=16` 时，`client.complete(...)` 返回的 `ModelCompletionResult` 保存这些值；DeepSeek 请求体包含 `user_id`。

- [x] **步骤 2：运行测试验证失败**

运行：

```bash
/Users/Masons/Ai/Maven/bin/mvn -Dmaven.repo.local=/Users/Masons/Ai/Maven_Ai_Store -q -pl server -Dtest=OpenAiCompatibleModelClientTest test
```

实际：编译失败，`OpenAiCompatibleModelClient.complete(...)` 仍只接受 4 个参数，无法传入缓存作用域。

- [x] **步骤 3：实现最少生产代码**

创建两个 domain record；修改 `ModelCompletionClient`、`OpenAiCompatibleModelClient`、`DeterministicModelAdapter`。`OpenAiCompatibleModelClient` 只在 `provider.id()` 为 `deepseek` 时写入 `user_id`，避免给其他兼容供应商传未知字段。

- [x] **步骤 4：运行客户端测试验证通过**

运行同一步骤 2 命令，结果为退出码 0。

### 任务 2：服务层真实用量优先

- [x] **步骤 1：编写失败的服务测试**

在 `ModelGatewayServiceTest` 增加测试：fake OpenAI 兼容客户端返回 `ProviderTokenUsage(200, 50, 25)`，服务响应和项目指标必须使用这些真实 token，而不是本地估算。

- [x] **步骤 2：运行测试验证失败**

运行：

```bash
/Users/Masons/Ai/Maven/bin/mvn -Dmaven.repo.local=/Users/Masons/Ai/Maven_Ai_Store -q -pl server -Dtest=ModelGatewayServiceTest test
```

实际：断言失败，期望 `cacheHitTokens=200`，实际为 `0`，说明服务层仍使用本地估算。

- [x] **步骤 3：实现服务层选择逻辑**

`ModelGatewayService` 生成：

```java
var roleSessionId = command.projectId() + ":" + command.role().name();
var cacheScopeId = "matrixcode_%s_%s_%s_%s".formatted(projectId, role, providerId, model);
```

调用模型客户端传入 `cacheScopeId`。如果 `completion.usage().filter(ProviderTokenUsage::hasPromptCacheTokens)` 存在，使用真实 token；否则用 `promptCacheEstimator.estimate(cacheScopeId, ...)`。

- [x] **步骤 4：运行服务测试验证通过**

运行同一步骤 2 命令，结果为退出码 0。

### 任务 3：缓存作用域隔离回归

- [x] **步骤 1：编写失败的隔离测试**

在 `ModelGatewayServiceTest` 增加测试：同项目同角色先用 `deepseek/deepseek-chat` 请求，再切换到 `qwen/qwen-plus` 请求；第二次不应继承第一次的本地缓存命中。

- [x] **步骤 2：运行测试验证失败**

该隔离行为和客户端 `user_id` 改造同一批实现落地，最终通过 `不同供应商同模型名称不共享本地缓存作用域` 覆盖。

- [x] **步骤 3：最少实现**

确认 `PromptCacheEstimator` 使用 `cacheScopeId + stablePrefixHash` 作为 key，服务层传入供应商和模型维度的缓存作用域。额外补充 `缓存作用域符合DeepSeekUserId字符约束`，保证点号、冒号等字符会规范化为下划线。

- [x] **步骤 4：运行测试验证通过**

运行 `ModelGatewayServiceTest`，结果为退出码 0。

### 任务 4：回归验证与文档图谱

- [x] **步骤 1：运行关联测试**

```bash
/Users/Masons/Ai/Maven/bin/mvn -Dmaven.repo.local=/Users/Masons/Ai/Maven_Ai_Store -q -pl server -Dtest=OpenAiCompatibleModelClientTest,ModelGatewayServiceTest,PromptContractBuilderTest,UsageCalculatorTest test
```

- [x] **步骤 2：运行服务端全量测试**

```bash
/Users/Masons/Ai/Maven/bin/mvn -Dmaven.repo.local=/Users/Masons/Ai/Maven_Ai_Store -q -pl server test
```

- [x] **步骤 3：运行真实本地集成检查**

```bash
set -a; source .env.local; set +a
/Users/Masons/Ai/Maven/bin/mvn -Dmaven.repo.local=/Users/Masons/Ai/Maven_Ai_Store -q -pl server -Dmatrixcode.real-runtime-test=true -Dtest=RealRuntimeIntegrationTest test
```

结果：真实 MySQL `matrix_code`、Milvus `matrix_code`、Redis、RocketMQ、千问 embedding 和 DeepSeek Chat 均验证通过，统计 `files=1 tests=7 failures=0 errors=0 skipped=0`。

- [x] **步骤 4：安全与格式检查**

```bash
git diff --check
```

同时扫描真实密钥字面值，确认仓库和 Obsidian 文档没有写入用户提供的 API Key 或数据库密码。

- [x] **步骤 5：更新第二大脑**

更新 Obsidian `MatrixCode` 项目首页、阶段索引、模型网关与上下文门禁、验证与风险、阶段成果，记录第 51 阶段实现、验证证据和偏差回溯。

- [x] **步骤 6：提交并推送**

```bash
git add .
git commit -m "feat(模型网关): 接入厂商缓存用量"
git push origin HEAD:master
```
