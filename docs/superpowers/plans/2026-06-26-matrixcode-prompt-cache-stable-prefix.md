# MatrixCode 模型网关稳定前缀治理实现计划

> **面向 AI 代理的工作者：** 必需子技能：使用 superpowers:subagent-driven-development（推荐）或 superpowers:executing-plans 逐任务实现此计划。步骤使用复选框（`- [x]`）语法来跟踪进度。

**目标：** 让模型网关的 `stablePrefixHash` 只代表真正稳定的平台前缀，避免模型名和角色自定义提示词变化破坏缓存命中。

**架构：** `PromptContractBuilder` 分离稳定平台前缀和角色配置后缀；`PromptContract.systemPrefix()` 保持完整 system prompt，`stablePrefixHash` 和 `estimatedStablePrefixTokens` 只基于稳定平台前缀；`PromptCacheEstimator` 继续复用现有 key 机制。

**技术栈：** Java 21、JUnit 5、Spring Boot、现有模型网关领域模型。

---

## 文件结构

- 修改：`server/src/main/java/com/matrixcode/modelgateway/application/PromptContractBuilder.java`，实现稳定平台前缀与角色提示词后缀分层。
- 修改：`server/src/test/java/com/matrixcode/modelgateway/PromptContractBuilderTest.java`，新增稳定前缀 hash 和 token 估算测试。
- 修改：`server/src/test/java/com/matrixcode/modelgateway/ModelGatewayServiceTest.java`，新增角色提示词变化后本地缓存估算仍命中稳定前缀测试。
- 新增：`/Users/Masons/Documents/Obsidian/Aevis/MatrixCode/阶段成果/55 模型网关稳定前缀治理.md`，记录阶段成果、验证证据和回溯结论。

### 任务 1：PromptContractBuilder 稳定前缀分层

- [x] **步骤 1：编写失败的 PromptContractBuilder 测试**

在 `PromptContractBuilderTest` 新增：

```java
@Test
void 角色提示词变化不改变稳定前缀哈希且不增加稳定前缀Token() {
    var builder = new PromptContractBuilder();

    var base = builder.build(ModelRole.DEVELOPER, "deepseek-chat", "tools-v1", "先读代码再修改。");
    var changed = builder.build(ModelRole.DEVELOPER, "deepseek-chat", "tools-v1", "先读代码再修改。必须写测试。");

    assertThat(changed.systemPrefix()).contains("必须写测试");
    assertThat(changed.stablePrefixHash()).isEqualTo(base.stablePrefixHash());
    assertThat(changed.estimatedStablePrefixTokens()).isEqualTo(base.estimatedStablePrefixTokens());
}

@Test
void 模型名称不污染稳定前缀哈希() {
    var builder = new PromptContractBuilder();

    var deepseek = builder.build(ModelRole.DEVELOPER, "deepseek-chat", "tools-v1", "先读代码再修改。");
    var qwen = builder.build(ModelRole.DEVELOPER, "qwen-plus", "tools-v1", "先读代码再修改。");

    assertThat(deepseek.stablePrefixHash()).isEqualTo(qwen.stablePrefixHash());
    assertThat(deepseek.systemPrefix()).doesNotContain("deepseek-chat");
    assertThat(qwen.systemPrefix()).doesNotContain("qwen-plus");
}
```

- [x] **步骤 2：运行测试验证失败**

```bash
/Users/Masons/Ai/Maven/bin/mvn -Dmaven.repo.local=/Users/Masons/Ai/Maven_Ai_Store -q -pl server -Dtest=PromptContractBuilderTest test
```

预期：测试失败，现有 `stablePrefixHash` 仍受角色提示词和模型名影响。

实际：`PromptContractBuilderTest` 失败 2 条，分别证明角色提示词和模型名会改变 `stablePrefixHash`。

- [x] **步骤 3：实现最少生产代码**

在 `PromptContractBuilder` 中：

- 新增 `stablePlatformPrefix(...)`，只包含角色、平台规则、上下文门禁、输出契约、工具契约版本、缓存策略版本。
- `systemPrefix = stablePlatformPrefix + rolePromptSuffix`。
- `stablePrefixHash` 的 hashSource 使用 `role + toolContractVersion + stablePlatformPrefix`。
- `estimatedStablePrefixTokens` 使用 `stablePlatformPrefix`。

- [x] **步骤 4：运行测试验证通过**

运行步骤 2 命令，预期退出码 0。

实际：退出码 0。

### 任务 2：模型网关角色提示词变化后仍命中稳定前缀

- [x] **步骤 1：编写失败的模型网关测试**

在 `ModelGatewayServiceTest` 新增：

```java
@Test
void 角色提示词变化后同一缓存作用域仍命中稳定平台前缀() {
    providers.upsert(new ModelProvider(
            "deepseek",
            "DeepSeek",
            ModelProtocol.OPENAI_COMPATIBLE,
            "https://api.deepseek.com",
            "MATRIXCODE_DEEPSEEK_API_KEY",
            true
    ));
    var fakeClient = new ModelCompletionClient() {
        @Override
        public boolean supports(ModelProtocol protocol) {
            return protocol == ModelProtocol.OPENAI_COMPATIBLE;
        }

        @Override
        public ModelCompletionResult complete(
                ModelProvider provider,
                RoleModelBinding binding,
                PromptContract contract,
                String instruction,
                String cacheScopeId
        ) {
            return ModelCompletionResult.withoutProviderUsage("真实兼容模型返回");
        }
    };
    var gateway = new ModelGatewayService(
            providers,
            bindings,
            new PromptContractBuilder(),
            new PromptCacheEstimator(),
            new UsageCalculator(),
            new ContextEngine(),
            List.of(fakeClient),
            events,
            roleAgentConfigs,
            store
    );
    updateDeveloperConfig("demo", "deepseek", "deepseek-chat", "先读代码再修改。");
    var first = gateway.request(new ModelRequestCommand("demo", ModelRole.DEVELOPER, "第一次请求", List.of()));

    updateDeveloperConfig("demo", "deepseek", "deepseek-chat", "先读代码再修改。必须写测试。");
    var second = gateway.request(new ModelRequestCommand("demo", ModelRole.DEVELOPER, "第二次请求", List.of()));

    assertThat(second.usage().cacheHitTokens()).isGreaterThan(0);
    assertThat(second.usage().stablePrefixHash()).isEqualTo(first.usage().stablePrefixHash());
    assertThat(second.usage().cacheScopeId()).isEqualTo(first.usage().cacheScopeId());
}
```

- [x] **步骤 2：运行测试验证失败**

```bash
/Users/Masons/Ai/Maven/bin/mvn -Dmaven.repo.local=/Users/Masons/Ai/Maven_Ai_Store -q -pl server -Dtest=ModelGatewayServiceTest test
```

预期：测试失败，第二次请求的 `stablePrefixHash` 与第一次不同，导致本地估算没有命中。

实际：`ModelGatewayServiceTest` 失败 1 条，第二次请求 `cacheHitTokens=0`。

- [x] **步骤 3：实现或补齐测试辅助方法**

如果测试需要辅助方法，新增 `updateDeveloperConfig(...)`，只封装角色配置更新，不改变生产逻辑。

- [x] **步骤 4：运行测试验证通过**

运行步骤 2 命令，预期退出码 0。

实际：退出码 0。

### 任务 3：回归、第二大脑、提交

- [x] **步骤 1：运行模型网关关联回归**

```bash
/Users/Masons/Ai/Maven/bin/mvn -Dmaven.repo.local=/Users/Masons/Ai/Maven_Ai_Store -q -pl server -Dtest=PromptContractBuilderTest,ModelGatewayServiceTest,OpenAiCompatibleModelClientTest,DatabaseMigrationCommentPolicyTest,DatabaseMigrationServiceTest test
```

实际：退出码 0。

- [x] **步骤 2：运行服务端全量测试**

```bash
/Users/Masons/Ai/Maven/bin/mvn -Dmaven.repo.local=/Users/Masons/Ai/Maven_Ai_Store -q -pl server test
```

实际：退出码 0，Surefire 统计 `files=78 tests=366 failures=0 errors=0 skipped=7`。

- [x] **步骤 3：运行桌面端全量测试和构建**

```bash
npm --prefix desktop test
npm --prefix desktop run build
```

实际：桌面端全量 `Tests 93 passed`，Vite 构建通过。

- [x] **步骤 4：运行真实集成**

```bash
set -a; source .env.local; set +a
/Users/Masons/Ai/Maven/bin/mvn -Dmaven.repo.local=/Users/Masons/Ai/Maven_Ai_Store -q -pl server -Dmatrixcode.real-runtime-test=true -Dtest=RealRuntimeIntegrationTest test
```

实际：退出码 0，`RealRuntimeIntegrationTest files=1 tests=7 failures=0 errors=0 skipped=0`；真实 MySQL `matrix_code` 当前 Flyway `v53.1`，本阶段无新增 DDL。

- [x] **步骤 5：静态和安全检查**

```bash
git diff --check
```

同时精确扫描真实 API Key 和数据库密码字面值，确认仓库与 Obsidian 文档无泄漏。

实际：`git diff --check` 无输出；精确扫描真实 API Key 和数据库密码字面值无命中；第二大脑补丁符号扫描无命中。

- [x] **步骤 6：更新第二大脑并回溯对齐**

新增第 55 阶段成果，更新首页、总览、阶段索引、模块地图、验证与风险、模型网关专题，记录本阶段对 DeepSeek-Reasonix 缓存诉求和第 51、52 阶段缓存 trace 的回溯结论。

实际：已新增 `55 模型网关稳定前缀治理.md`，并更新项目首页、项目总览、阶段索引、模块地图、验证与风险、模型网关与上下文门禁、状态持久化与数据库迁移。

- [x] **步骤 7：提交并推送**

```bash
git add .
git commit -m "feat(模型网关): 稳定缓存前缀治理"
git push origin HEAD:master
```

## 2026-06-26 计划状态回填

- 本次回溯确认：该计划对应能力已在后续阶段完成，并已进入 Obsidian 阶段成果、验证与风险、模块地图和真实验证记录。
- 原未勾选项属于历史计划状态未回填，不代表当前功能缺失；已统一回填为完成，后续验收以对应阶段成果页和最新验证命令为准。
