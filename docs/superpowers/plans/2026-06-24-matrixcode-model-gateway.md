# MatrixCode 第三阶段模型网关与缓存优化实现计划

> **面向 AI 代理的工作者：** 必需子技能：使用 superpowers:subagent-driven-development（推荐）或 superpowers:executing-plans 逐任务实现此计划。步骤使用复选框（`- [ ]`）语法来跟踪进度。

**目标：** 构建第三阶段模型网关纵切，让项目支持模型供应商配置、角色模型选择、稳定提示词契约、上下文清单、prompt cache 估算、token 成本统计，并在桌面工作台展示这些指标。

**架构：** 服务端新增 `modelgateway` 模块，默认使用本地确定性适配器，真实供应商先以 OpenAI 兼容配置形态预留。`WorkbenchService` 通过模型网关生成产品草稿并聚合模型指标；桌面端扩展 API 类型和右侧指标栏。

**技术栈：** Java 21、Spring Boot 3.5.15、JUnit 5、React 19.2.7、TypeScript 6.0.3、Vitest 4.1.9、本地 Maven `/Users/Masons/Ai/Maven` 和仓库 `/Users/Masons/Ai/Maven_Ai_Store`。

---

## 范围检查

本计划只实现模型网关可运行纵切，不做真实流式模型调用、密钥加密、数据库持久化、登录鉴权、供应商重试限流或本地执行代理。OpenAI 兼容供应商只进入配置和校验，不进入默认请求执行路径。所有验证必须能在无 API Key、无外网时通过。

## 文件结构

```text
server/src/main/java/com/matrixcode/modelgateway/
├── api/
│   └── ModelGatewayController.java
├── application/
│   ├── DeterministicModelAdapter.java
│   ├── ModelGatewayService.java
│   ├── ModelProviderRegistry.java
│   ├── PromptCacheEstimator.java
│   ├── PromptContractBuilder.java
│   └── RoleModelBindingService.java
└── domain/
    ├── ModelGatewayConfig.java
    ├── ModelGatewayMetrics.java
    ├── ModelGatewaySummary.java
    ├── ModelProtocol.java
    ├── ModelProvider.java
    ├── ModelRequestCommand.java
    ├── ModelRequestRecord.java
    ├── ModelResponse.java
    ├── ModelRole.java
    ├── PromptContract.java
    ├── PromptEstimate.java
    └── RoleModelBinding.java

server/src/test/java/com/matrixcode/modelgateway/
├── ModelGatewayControllerTest.java
├── ModelGatewayServiceTest.java
├── ModelProviderRegistryTest.java
├── PromptContractBuilderTest.java
└── RoleModelBindingServiceTest.java

修改：
- server/src/main/java/com/matrixcode/workbench/application/WorkbenchService.java
- server/src/main/java/com/matrixcode/workbench/domain/ProjectWorkbench.java
- server/src/main/java/com/matrixcode/workbench/domain/WorkbenchMetrics.java
- server/src/test/java/com/matrixcode/workbench/WorkbenchServiceTest.java
- server/src/test/java/com/matrixcode/workbench/WorkbenchControllerTest.java
- desktop/src/api/client.ts
- desktop/src/api/client.test.ts
- desktop/src/components/InspectorPanel.tsx
- desktop/src/test/App.test.tsx
- docs/development/local-run.md
- docs/superpowers/plans/2026-06-24-matrixcode-model-gateway.md
```

---

### 任务 1：实现模型供应商注册与校验

**文件：**

- 创建：`server/src/test/java/com/matrixcode/modelgateway/ModelProviderRegistryTest.java`
- 创建：`server/src/main/java/com/matrixcode/modelgateway/domain/ModelProtocol.java`
- 创建：`server/src/main/java/com/matrixcode/modelgateway/domain/ModelProvider.java`
- 创建：`server/src/main/java/com/matrixcode/modelgateway/application/ModelProviderRegistry.java`

- [x] **步骤 1：编写供应商注册失败测试**

创建 `server/src/test/java/com/matrixcode/modelgateway/ModelProviderRegistryTest.java`：

```java
package com.matrixcode.modelgateway;

import com.matrixcode.modelgateway.application.ModelProviderRegistry;
import com.matrixcode.modelgateway.domain.ModelProtocol;
import com.matrixcode.modelgateway.domain.ModelProvider;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ModelProviderRegistryTest {

    @Test
    void 默认包含本地确定性供应商() {
        var registry = new ModelProviderRegistry();

        var provider = registry.require("local-deterministic");

        assertThat(provider.name()).isEqualTo("本地确定性模型");
        assertThat(provider.protocol()).isEqualTo(ModelProtocol.LOCAL);
        assertThat(provider.enabled()).isTrue();
    }

    @Test
    void OpenAI兼容供应商必须使用Https地址且不能接收明文密钥() {
        var registry = new ModelProviderRegistry();

        assertThatThrownBy(() -> registry.upsert(new ModelProvider(
                "openai-compatible", "OpenAI 兼容", ModelProtocol.OPENAI_COMPATIBLE,
                "http://example.com/v1", "MATRIXCODE_OPENAI_KEY", true
        ))).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("供应商基础地址必须使用 https");

        assertThatThrownBy(() -> registry.upsert(new ModelProvider(
                "openai-compatible", "OpenAI 兼容", ModelProtocol.OPENAI_COMPATIBLE,
                "https://example.com/v1", "sk-live-key", true
        ))).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("只允许填写环境变量名");
    }

    @Test
    void 禁用供应商仍可查询但不能作为可用供应商() {
        var registry = new ModelProviderRegistry();
        registry.upsert(new ModelProvider(
                "disabled-provider", "停用供应商", ModelProtocol.OPENAI_COMPATIBLE,
                "https://example.com/v1", "MATRIXCODE_DISABLED_KEY", false
        ));

        assertThat(registry.require("disabled-provider").enabled()).isFalse();
        assertThat(registry.enabledProviders()).extracting(ModelProvider::id)
                .containsExactly("local-deterministic");
    }
}
```

- [x] **步骤 2：运行测试验证失败**

运行：

```bash
/Users/Masons/Ai/Maven/bin/mvn -Dmaven.repo.local=/Users/Masons/Ai/Maven_Ai_Store -q -pl server -Dtest=ModelProviderRegistryTest test
```

预期：编译失败，错误包含 `ModelProviderRegistry`、`ModelProtocol` 或 `ModelProvider` 不存在。

- [x] **步骤 3：实现供应商领域对象和注册表**

创建 `server/src/main/java/com/matrixcode/modelgateway/domain/ModelProtocol.java`：

```java
package com.matrixcode.modelgateway.domain;

public enum ModelProtocol {
    LOCAL,
    OPENAI_COMPATIBLE
}
```

创建 `server/src/main/java/com/matrixcode/modelgateway/domain/ModelProvider.java`：

```java
package com.matrixcode.modelgateway.domain;

public record ModelProvider(
        String id,
        String name,
        ModelProtocol protocol,
        String baseUrl,
        String apiKeySource,
        boolean enabled
) {
    public ModelProvider {
        id = requireText(id, "供应商 ID 不能为空");
        name = requireText(name, "供应商名称不能为空");
        if (protocol == null) {
            throw new IllegalArgumentException("供应商协议不能为空");
        }
        baseUrl = baseUrl == null ? "" : baseUrl.trim();
        apiKeySource = apiKeySource == null || apiKeySource.isBlank() ? "NONE" : apiKeySource.trim();
        if (protocol == ModelProtocol.OPENAI_COMPATIBLE && !baseUrl.startsWith("https://")) {
            throw new IllegalArgumentException("供应商基础地址必须使用 https");
        }
        if (apiKeySource.startsWith("sk-")) {
            throw new IllegalArgumentException("供应商 API Key 只允许填写环境变量名");
        }
    }

    private static String requireText(String value, String message) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(message);
        }
        return value.trim();
    }
}
```

创建 `server/src/main/java/com/matrixcode/modelgateway/application/ModelProviderRegistry.java`：

```java
package com.matrixcode.modelgateway.application;

import com.matrixcode.modelgateway.domain.ModelProtocol;
import com.matrixcode.modelgateway.domain.ModelProvider;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class ModelProviderRegistry {

    private final Map<String, ModelProvider> providers = new ConcurrentHashMap<>();

    public ModelProviderRegistry() {
        upsert(new ModelProvider("local-deterministic", "本地确定性模型", ModelProtocol.LOCAL, "", "NONE", true));
    }

    public ModelProvider upsert(ModelProvider provider) {
        providers.put(provider.id(), provider);
        return provider;
    }

    public ModelProvider require(String providerId) {
        var provider = providers.get(providerId);
        if (provider == null) {
            throw new IllegalArgumentException("模型供应商不存在：" + providerId);
        }
        return provider;
    }

    public List<ModelProvider> all() {
        return providers.values().stream()
                .sorted((left, right) -> left.id().compareTo(right.id()))
                .toList();
    }

    public List<ModelProvider> enabledProviders() {
        return all().stream()
                .filter(ModelProvider::enabled)
                .toList();
    }
}
```

- [x] **步骤 4：运行测试验证通过**

运行：

```bash
/Users/Masons/Ai/Maven/bin/mvn -Dmaven.repo.local=/Users/Masons/Ai/Maven_Ai_Store -q -pl server -Dtest=ModelProviderRegistryTest test
```

预期：`ModelProviderRegistryTest` 通过。

- [x] **步骤 5：提交**

```bash
git add server/src/main/java/com/matrixcode/modelgateway server/src/test/java/com/matrixcode/modelgateway/ModelProviderRegistryTest.java
git commit -m "feat: 添加模型供应商注册表"
```

---

### 任务 2：实现角色模型绑定服务

**文件：**

- 创建：`server/src/test/java/com/matrixcode/modelgateway/RoleModelBindingServiceTest.java`
- 创建：`server/src/main/java/com/matrixcode/modelgateway/domain/ModelRole.java`
- 创建：`server/src/main/java/com/matrixcode/modelgateway/domain/RoleModelBinding.java`
- 创建：`server/src/main/java/com/matrixcode/modelgateway/application/RoleModelBindingService.java`

- [x] **步骤 1：编写角色绑定失败测试**

创建 `server/src/test/java/com/matrixcode/modelgateway/RoleModelBindingServiceTest.java`：

```java
package com.matrixcode.modelgateway;

import com.matrixcode.modelgateway.application.ModelProviderRegistry;
import com.matrixcode.modelgateway.application.RoleModelBindingService;
import com.matrixcode.modelgateway.domain.ModelProtocol;
import com.matrixcode.modelgateway.domain.ModelProvider;
import com.matrixcode.modelgateway.domain.ModelRole;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RoleModelBindingServiceTest {

    @Test
    void 四个角色默认绑定本地模型() {
        var service = new RoleModelBindingService(new ModelProviderRegistry());

        assertThat(service.bindings("demo")).hasSize(4);
        assertThat(service.require("demo", ModelRole.PRODUCT).model()).isEqualTo("matrixcode-local-product");
        assertThat(service.require("demo", ModelRole.DEVELOPER).contextBudgetTokens()).isEqualTo(32_000);
    }

    @Test
    void 可以为角色绑定已启用供应商模型() {
        var registry = new ModelProviderRegistry();
        registry.upsert(new ModelProvider("qwen", "Qwen 兼容", ModelProtocol.OPENAI_COMPATIBLE,
                "https://dashscope.aliyuncs.com/compatible-mode/v1", "MATRIXCODE_QWEN_KEY", true));
        var service = new RoleModelBindingService(registry);

        var binding = service.bind("demo", ModelRole.TESTER, "qwen", "qwen-max", "CNY", 0.15, 1.5, 6.0, 48_000, "tools-v2");

        assertThat(binding.providerId()).isEqualTo("qwen");
        assertThat(binding.model()).isEqualTo("qwen-max");
        assertThat(service.require("demo", ModelRole.TESTER).toolContractVersion()).isEqualTo("tools-v2");
    }

    @Test
    void 不能绑定禁用供应商或空角色模型() {
        var registry = new ModelProviderRegistry();
        registry.upsert(new ModelProvider("disabled", "停用供应商", ModelProtocol.OPENAI_COMPATIBLE,
                "https://example.com/v1", "MATRIXCODE_DISABLED_KEY", false));
        var service = new RoleModelBindingService(registry);

        assertThatThrownBy(() -> service.bind("demo", ModelRole.PRODUCT, "disabled", "model", "CNY", 0.1, 1.0, 2.0, 32_000, "tools-v1"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("模型供应商未启用");
        assertThatThrownBy(() -> service.bind("demo", ModelRole.PRODUCT, "local-deterministic", " ", "CNY", 0.0, 0.0, 0.0, 32_000, "tools-v1"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("模型名称不能为空");
    }
}
```

- [x] **步骤 2：运行测试验证失败**

运行：

```bash
/Users/Masons/Ai/Maven/bin/mvn -Dmaven.repo.local=/Users/Masons/Ai/Maven_Ai_Store -q -pl server -Dtest=RoleModelBindingServiceTest test
```

预期：编译失败，错误包含 `RoleModelBindingService`、`ModelRole` 或 `RoleModelBinding` 不存在。

- [x] **步骤 3：实现角色枚举和绑定对象**

创建 `server/src/main/java/com/matrixcode/modelgateway/domain/ModelRole.java`，枚举值为 `PRODUCT`、`DEVELOPER`、`TESTER`、`OPERATIONS`，每个值包含中文显示名和英文 slug。实现 `fromPath(String value)`，支持 `product/developer/tester/operations/ops` 和 `产品/开发/测试/运维`。

创建 `server/src/main/java/com/matrixcode/modelgateway/domain/RoleModelBinding.java`，字段为 `projectId`、`role`、`providerId`、`model`、`currency`、`cacheHitPerMillion`、`cacheMissInputPerMillion`、`outputPerMillion`、`contextBudgetTokens`、`toolContractVersion`，构造器校验项目、模型、币种、工具契约不能为空，预算必须大于 0，价格不能为负数。

- [x] **步骤 4：实现角色绑定服务**

创建 `server/src/main/java/com/matrixcode/modelgateway/application/RoleModelBindingService.java`。构造器注入 `ModelProviderRegistry`。`require(projectId, role)` 在没有显式绑定时返回默认绑定；`bindings(projectId)` 返回四个角色绑定；`bind(...)` 校验供应商已启用后保存绑定。

默认模型：

```text
PRODUCT    -> matrixcode-local-product
DEVELOPER  -> matrixcode-local-developer
TESTER     -> matrixcode-local-tester
OPERATIONS -> matrixcode-local-ops
```

默认价格全部为 `CNY, 0.0, 0.0, 0.0`，上下文预算 `32000`，工具契约版本 `tools-v1`。

- [x] **步骤 5：运行测试验证通过**

运行：

```bash
/Users/Masons/Ai/Maven/bin/mvn -Dmaven.repo.local=/Users/Masons/Ai/Maven_Ai_Store -q -pl server -Dtest=ModelProviderRegistryTest,RoleModelBindingServiceTest test
```

预期：两个测试类通过。

- [x] **步骤 6：提交**

```bash
git add server/src/main/java/com/matrixcode/modelgateway server/src/test/java/com/matrixcode/modelgateway/RoleModelBindingServiceTest.java
git commit -m "feat: 添加角色模型绑定服务"
```

---

### 任务 3：实现稳定提示词契约和缓存估算

**文件：**

- 创建：`server/src/test/java/com/matrixcode/modelgateway/PromptContractBuilderTest.java`
- 创建：`server/src/main/java/com/matrixcode/modelgateway/domain/PromptContract.java`
- 创建：`server/src/main/java/com/matrixcode/modelgateway/domain/PromptEstimate.java`
- 创建：`server/src/main/java/com/matrixcode/modelgateway/application/PromptContractBuilder.java`
- 创建：`server/src/main/java/com/matrixcode/modelgateway/application/PromptCacheEstimator.java`

- [x] **步骤 1：编写提示词契约和缓存估算失败测试**

创建 `server/src/test/java/com/matrixcode/modelgateway/PromptContractBuilderTest.java`：

```java
package com.matrixcode.modelgateway;

import com.matrixcode.modelgateway.application.PromptCacheEstimator;
import com.matrixcode.modelgateway.application.PromptContractBuilder;
import com.matrixcode.modelgateway.domain.ModelRole;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class PromptContractBuilderTest {

    @Test
    void 用户输入变化不会改变稳定前缀哈希() {
        var builder = new PromptContractBuilder();

        var first = builder.build(ModelRole.PRODUCT, "matrixcode-local-product", "tools-v1");
        var second = builder.build(ModelRole.PRODUCT, "matrixcode-local-product", "tools-v1");

        assertThat(first.stablePrefixHash()).isEqualTo(second.stablePrefixHash());
        assertThat(first.systemPrefix()).contains("MatrixCode", "产品");
        assertThat(first.estimatedStablePrefixTokens()).isGreaterThan(0);
    }

    @Test
    void 工具契约变化会改变稳定前缀哈希() {
        var builder = new PromptContractBuilder();

        var first = builder.build(ModelRole.TESTER, "matrixcode-local-tester", "tools-v1");
        var second = builder.build(ModelRole.TESTER, "matrixcode-local-tester", "tools-v2");

        assertThat(first.stablePrefixHash()).isNotEqualTo(second.stablePrefixHash());
    }

    @Test
    void 同一角色会话第二次请求命中稳定前缀缓存() {
        var builder = new PromptContractBuilder();
        var estimator = new PromptCacheEstimator();
        var contract = builder.build(ModelRole.DEVELOPER, "matrixcode-local-developer", "tools-v1");

        var first = estimator.estimate("demo:DEVELOPER", contract, List.of("FROZEN_PRD", "CURRENT_EVENT"), "实现失败重试", "开发建议");
        var second = estimator.estimate("demo:DEVELOPER", contract, List.of("FROZEN_PRD", "CURRENT_EVENT"), "补充自测", "开发建议");

        assertThat(first.cacheHitTokens()).isZero();
        assertThat(first.cacheMissInputTokens()).isGreaterThan(contract.estimatedStablePrefixTokens());
        assertThat(second.cacheHitTokens()).isEqualTo(contract.estimatedStablePrefixTokens());
        assertThat(second.cacheMissInputTokens()).isLessThan(first.cacheMissInputTokens());
    }
}
```

- [x] **步骤 2：运行测试验证失败**

运行：

```bash
/Users/Masons/Ai/Maven/bin/mvn -Dmaven.repo.local=/Users/Masons/Ai/Maven_Ai_Store -q -pl server -Dtest=PromptContractBuilderTest test
```

预期：编译失败，错误包含 `PromptContractBuilder`、`PromptCacheEstimator`、`PromptContract` 或 `PromptEstimate` 不存在。

- [x] **步骤 3：实现提示词契约生成器**

`PromptContractBuilder.build(ModelRole role, String model, String toolContractVersion)` 生成：

- `systemPrefix`：包含 MatrixCode 平台规则、角色中文名称、输出契约、只读工具契约说明。
- `stablePrefixHash`：对 `role.name()`、`model`、`toolContractVersion`、`systemPrefix` 使用 SHA-256，输出前 16 字节十六进制。
- `estimatedStablePrefixTokens`：使用 `Math.max(1, systemPrefix.length() / 2)` 估算中文 token。

- [x] **步骤 4：实现缓存估算器**

`PromptCacheEstimator` 使用内存 `Set<String>` 记录 `roleSessionId + stablePrefixHash`。第一次请求稳定前缀计入未命中，后续相同角色会话和哈希计入命中。动态 token 由上下文块类型、用户指令和输出文本估算，永远计入未命中输入或输出。

- [x] **步骤 5：运行测试验证通过**

运行：

```bash
/Users/Masons/Ai/Maven/bin/mvn -Dmaven.repo.local=/Users/Masons/Ai/Maven_Ai_Store -q -pl server -Dtest=PromptContractBuilderTest test
```

预期：`PromptContractBuilderTest` 通过。

- [x] **步骤 6：提交**

```bash
git add server/src/main/java/com/matrixcode/modelgateway server/src/test/java/com/matrixcode/modelgateway/PromptContractBuilderTest.java
git commit -m "feat: 添加稳定提示词契约和缓存估算"
```

---

### 任务 4：实现模型网关服务和本地确定性适配器

**文件：**

- 创建：`server/src/test/java/com/matrixcode/modelgateway/ModelGatewayServiceTest.java`
- 创建：`server/src/main/java/com/matrixcode/modelgateway/domain/ModelRequestCommand.java`
- 创建：`server/src/main/java/com/matrixcode/modelgateway/domain/ModelResponse.java`
- 创建：`server/src/main/java/com/matrixcode/modelgateway/domain/ModelRequestRecord.java`
- 创建：`server/src/main/java/com/matrixcode/modelgateway/domain/ModelGatewayMetrics.java`
- 创建：`server/src/main/java/com/matrixcode/modelgateway/application/DeterministicModelAdapter.java`
- 创建：`server/src/main/java/com/matrixcode/modelgateway/application/ModelGatewayService.java`

- [x] **步骤 1：编写模型网关服务失败测试**

创建 `server/src/test/java/com/matrixcode/modelgateway/ModelGatewayServiceTest.java`：

```java
package com.matrixcode.modelgateway;

import com.matrixcode.agent.application.LocalProductDraftAgent;
import com.matrixcode.context.application.ContextEngine;
import com.matrixcode.context.domain.ContextBlock;
import com.matrixcode.modelgateway.application.*;
import com.matrixcode.modelgateway.domain.ModelRequestCommand;
import com.matrixcode.modelgateway.domain.ModelRole;
import com.matrixcode.realtime.application.ProjectEventBus;
import com.matrixcode.usage.application.UsageCalculator;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ModelGatewayServiceTest {

    private final ProjectEventBus events = new ProjectEventBus();
    private final ModelProviderRegistry providers = new ModelProviderRegistry();
    private final RoleModelBindingService bindings = new RoleModelBindingService(providers);
    private final ModelGatewayService service = new ModelGatewayService(
            providers,
            bindings,
            new PromptContractBuilder(),
            new PromptCacheEstimator(),
            new UsageCalculator(),
            new ContextEngine(),
            new DeterministicModelAdapter(new LocalProductDraftAgent()),
            events
    );

    @Test
    void 产品模型请求返回中文草稿上下文清单用量和事件() {
        var response = service.request(new ModelRequestCommand(
                "demo",
                ModelRole.PRODUCT,
                "支付失败后允许用户重新发起支付。",
                List.of(
                        new ContextBlock("PROJECT_RULE", "保持中文输出", true),
                        new ContextBlock("PRODUCT_CHAT", "未冻结长对话", false)
                )
        ));

        assertThat(response.answer()).contains("产品需求草稿", "验收标准草稿", "初始界面说明草稿");
        assertThat(response.contextManifest().blocks()).extracting(ContextBlock::type)
                .containsExactly("PROJECT_RULE");
        assertThat(response.contextManifest().omittedTypes()).containsExactly("PRODUCT_CHAT");
        assertThat(response.usage().roleSessionId()).isEqualTo("demo:PRODUCT");
        assertThat(events.recent("demo")).extracting("type").contains("MODEL_REQUEST_COMPLETED");
    }

    @Test
    void 同一角色连续请求会提升累计缓存命中率() {
        service.request(new ModelRequestCommand("demo", ModelRole.PRODUCT, "第一次请求", List.of()));
        service.request(new ModelRequestCommand("demo", ModelRole.PRODUCT, "第二次请求", List.of()));

        var metrics = service.metrics("demo");

        assertThat(metrics.cacheHitTokens()).isGreaterThan(0);
        assertThat(metrics.cacheHitRate()).isGreaterThan(0.0);
        assertThat(metrics.requestCount()).isEqualTo(2);
        assertThat(service.recentRequests("demo")).hasSize(2);
    }
}
```

- [x] **步骤 2：运行测试验证失败**

运行：

```bash
/Users/Masons/Ai/Maven/bin/mvn -Dmaven.repo.local=/Users/Masons/Ai/Maven_Ai_Store -q -pl server -Dtest=ModelGatewayServiceTest test
```

预期：编译失败，错误包含 `ModelGatewayService`、`ModelRequestCommand`、`ModelResponse` 或 `DeterministicModelAdapter` 不存在。

- [x] **步骤 3：实现模型请求领域记录**

创建 `ModelRequestCommand`、`ModelResponse`、`ModelRequestRecord` 和 `ModelGatewayMetrics`。`ModelGatewayMetrics.empty()` 返回 0 指标，`from(List<ModelRequestRecord>)` 聚合 cache hit、cache miss、output、费用、请求数和最近上下文类型。

- [x] **步骤 4：实现本地确定性适配器**

`DeterministicModelAdapter.complete(ModelRole role, String instruction)`：

- 产品角色复用 `LocalProductDraftAgent`，把三份草稿拼成一个 markdown 字符串。
- 开发、测试、运维返回稳定中文摘要，包含角色名和用户指令。
- 不访问网络，不读取环境变量。

- [x] **步骤 5：实现模型网关服务**

`ModelGatewayService.request(command)`：

1. 读取角色绑定。
2. 生成提示词契约。
3. 使用 `ContextEngine` 过滤上下文。
4. 调用本地确定性适配器。
5. 使用 `PromptCacheEstimator` 和 `UsageCalculator` 生成 `UsageRecord`。
6. 保存 `ModelRequestRecord` 到内存列表。
7. 发布 `MODEL_REQUEST_COMPLETED` 事件。
8. 返回 `ModelResponse`。

- [x] **步骤 6：运行测试验证通过**

运行：

```bash
/Users/Masons/Ai/Maven/bin/mvn -Dmaven.repo.local=/Users/Masons/Ai/Maven_Ai_Store -q -pl server -Dtest=ModelGatewayServiceTest,PromptContractBuilderTest,RoleModelBindingServiceTest,ModelProviderRegistryTest test
```

预期：四个模型网关测试类通过。

- [x] **步骤 7：提交**

```bash
git add server/src/main/java/com/matrixcode/modelgateway server/src/test/java/com/matrixcode/modelgateway/ModelGatewayServiceTest.java
git commit -m "feat: 实现模型网关请求编排"
```

---

### 任务 5：接入工作台服务和 REST 接口

**文件：**

- 创建：`server/src/test/java/com/matrixcode/modelgateway/ModelGatewayControllerTest.java`
- 创建：`server/src/main/java/com/matrixcode/modelgateway/domain/ModelGatewayConfig.java`
- 创建：`server/src/main/java/com/matrixcode/modelgateway/domain/ModelGatewaySummary.java`
- 创建：`server/src/main/java/com/matrixcode/modelgateway/api/ModelGatewayController.java`
- 修改：`server/src/main/java/com/matrixcode/workbench/application/WorkbenchService.java`
- 修改：`server/src/main/java/com/matrixcode/workbench/domain/ProjectWorkbench.java`
- 修改：`server/src/main/java/com/matrixcode/workbench/domain/WorkbenchMetrics.java`
- 修改：`server/src/test/java/com/matrixcode/workbench/WorkbenchServiceTest.java`
- 修改：`server/src/test/java/com/matrixcode/workbench/WorkbenchControllerTest.java`

- [x] **步骤 1：编写接口和工作台失败测试**

创建 `server/src/test/java/com/matrixcode/modelgateway/ModelGatewayControllerTest.java`，使用 `@WebMvcTest(ModelGatewayController.class)` 和 mocked service，覆盖：

- `GET /api/projects/demo/model-gateway/config` 返回默认供应商和四角色绑定。
- `POST /api/projects/demo/roles/product/model-requests` 返回中文 answer、usage、contextManifest。
- `POST /api/projects/demo/roles/tester/model-binding` 能绑定 tester 模型。

修改 `WorkbenchServiceTest`，在服务构造器中注入 `ModelGatewayService`，并新增断言：

```java
var workbench = service.get("demo");
assertThat(workbench.modelGateway().bindings()).hasSize(4);
assertThat(workbench.modelGateway().metrics().requestCount()).isGreaterThanOrEqualTo(1);
```

- [x] **步骤 2：运行测试验证失败**

运行：

```bash
/Users/Masons/Ai/Maven/bin/mvn -Dmaven.repo.local=/Users/Masons/Ai/Maven_Ai_Store -q -pl server -Dtest=ModelGatewayControllerTest,WorkbenchServiceTest,WorkbenchControllerTest test
```

预期：编译失败或注入失败，错误指向 `ModelGatewayController`、`ProjectWorkbench.modelGateway()` 或工作台构造器参数不存在。

- [x] **步骤 3：实现模型网关配置 DTO 和控制器**

`ModelGatewayConfig` 包含 `providers`、`bindings`、`metrics`、`recentRequests`。`ModelGatewaySummary` 包含 `bindings`、`metrics`、`recentRequests`。

`ModelGatewayController` 提供：

- `GET /api/projects/{projectId}/model-gateway/config`
- `POST /api/projects/{projectId}/model-gateway/providers`
- `POST /api/projects/{projectId}/roles/{role}/model-binding`
- `POST /api/projects/{projectId}/roles/{role}/model-requests`

请求命令记录作为 controller 内部 record，绑定价格和预算字段必须完整传入。

- [x] **步骤 4：让产品草稿生成走模型网关**

修改 `WorkbenchService.createProductDrafts`：

1. 调用 `modelGatewayService.request(...)` 创建产品模型请求。
2. 继续使用 `LocalProductDraftAgent` 或模型网关结果中的草稿拆分创建三份文档。
3. 保留 `PRODUCT_DRAFT_CREATED` 事件。

工作台 `get` 方法把 `modelGatewayService.summary(projectId)` 放入 `ProjectWorkbench`，并把 `WorkbenchMetrics.cacheHitRate`、`sessionTokens` 从模型网关累计指标中取值。

- [x] **步骤 5：运行测试验证通过**

运行：

```bash
/Users/Masons/Ai/Maven/bin/mvn -Dmaven.repo.local=/Users/Masons/Ai/Maven_Ai_Store -q -pl server -Dtest=ModelGatewayControllerTest,WorkbenchServiceTest,WorkbenchControllerTest test
```

预期：接口测试和工作台测试通过。

- [x] **步骤 6：提交**

```bash
git add server/src/main/java/com/matrixcode/modelgateway server/src/main/java/com/matrixcode/workbench server/src/test/java/com/matrixcode/modelgateway server/src/test/java/com/matrixcode/workbench
git commit -m "feat: 接入模型网关工作台接口"
```

---

### 任务 6：扩展桌面 API 类型和运行指标栏

**文件：**

- 修改：`desktop/src/api/client.ts`
- 修改：`desktop/src/api/client.test.ts`
- 修改：`desktop/src/components/InspectorPanel.tsx`
- 修改：`desktop/src/test/App.test.tsx`

- [x] **步骤 1：编写桌面失败测试**

修改 `desktop/src/api/client.test.ts`，新增：

```ts
import { bindRoleModel, createRoleModelRequest, loadModelGatewayConfig } from './client';

it('加载模型网关配置', async () => {
  const fetchMock = vi.fn().mockResolvedValue({
    ok: true,
    json: async () => ({
      providers: [{ id: 'local-deterministic', name: '本地确定性模型', protocol: 'LOCAL', baseUrl: '', apiKeySource: 'NONE', enabled: true }],
      bindings: [],
      metrics: { requestCount: 0, cacheHitTokens: 0, cacheMissInputTokens: 0, outputTokens: 0, cacheHitRate: 0, estimatedCost: 0, currency: 'CNY', recentContextTypes: [] },
      recentRequests: []
    })
  });
  vi.stubGlobal('fetch', fetchMock);

  const config = await loadModelGatewayConfig('demo', 'http://localhost:8080');

  expect(fetchMock).toHaveBeenCalledWith('http://localhost:8080/api/projects/demo/model-gateway/config', {
    headers: { Accept: 'application/json' }
  });
  expect(config.providers[0].name).toBe('本地确定性模型');
});
```

修改 `desktop/src/test/App.test.tsx` 的工作台 mock，让 `workbench` 带 `modelGateway` 字段，并新增断言：

```ts
expect(await screen.findByText(/当前模型/)).toBeInTheDocument();
expect(screen.getByText(/matrixcode-local-product/)).toBeInTheDocument();
expect(screen.getByText(/最近上下文/)).toBeInTheDocument();
```

- [x] **步骤 2：运行测试验证失败**

运行：

```bash
cd desktop && npm test
```

预期：TypeScript 或 Vitest 失败，错误包含新增 API 导出、`modelGateway` 类型或 UI 文案不存在。

- [x] **步骤 3：扩展桌面类型和客户端函数**

在 `desktop/src/api/client.ts` 增加类型：

- `ModelProtocol`
- `ModelProvider`
- `ModelRole`
- `RoleModelBinding`
- `ModelGatewayMetrics`
- `ModelRequestRecord`
- `ModelGatewayConfig`
- `ModelGatewaySummary`

新增函数：

- `loadModelGatewayConfig(projectId, serverUrl)`
- `bindRoleModel(projectId, role, input, serverUrl)`
- `createRoleModelRequest(projectId, role, input, serverUrl)`

并在 `ProjectWorkbench` 类型上增加 `modelGateway: ModelGatewaySummary`。

- [x] **步骤 4：扩展右侧运行指标栏**

修改 `InspectorPanel`：

- props 增加 `modelGateway`。
- 展示第一条产品绑定或最近请求模型，标题为 `当前模型`。
- 展示 `缓存命中 token`、`未命中输入 token`、`输出 token`、`估算费用`。
- 展示 `最近上下文`，无上下文时显示 `暂无模型上下文`。

修改 `App.tsx` 调用 `InspectorPanel` 的地方，传入 `workbench.modelGateway`。

- [x] **步骤 5：运行桌面测试和构建验证通过**

运行：

```bash
cd desktop && npm test && npm run build
```

预期：桌面端测试和构建通过。

- [x] **步骤 6：提交**

```bash
git add desktop/src/api/client.ts desktop/src/api/client.test.ts desktop/src/components/InspectorPanel.tsx desktop/src/test/App.test.tsx desktop/src/App.tsx
git commit -m "feat: 展示模型网关运行指标"
```

---

### 任务 7：第三阶段整体验证和文档更新

**文件：**

- 修改：`docs/development/local-run.md`
- 修改：`docs/superpowers/plans/2026-06-24-matrixcode-model-gateway.md`

- [x] **步骤 1：运行服务端全量测试**

运行：

```bash
/Users/Masons/Ai/Maven/bin/mvn -Dmaven.repo.local=/Users/Masons/Ai/Maven_Ai_Store -q -pl server test
```

预期：服务端全部测试通过。

- [x] **步骤 2：运行桌面端测试和构建**

运行：

```bash
cd desktop && npm test && npm run build && npm run tauri:build -- --help
```

预期：桌面端测试、TypeScript 构建、Vite 构建和 Tauri 命令入口通过。

- [x] **步骤 3：启动服务并验证模型网关接口**

启动服务端：

```bash
/Users/Masons/Ai/Maven/bin/mvn -Dmaven.repo.local=/Users/Masons/Ai/Maven_Ai_Store -pl server spring-boot:run
```

另一个终端运行：

```bash
curl -sS http://localhost:8080/api/projects/demo/model-gateway/config
curl -sS -X POST http://localhost:8080/api/projects/demo/roles/product/model-requests \
  -H 'Content-Type: application/json' \
  -d '{"instruction":"支付失败后允许用户重新发起支付。","contextBlocks":[{"type":"PROJECT_RULE","summary":"保持中文输出","allowedByGate":true}]}'
curl -sS http://localhost:8080/api/projects/demo/workbench
```

预期：

- 配置接口返回默认供应商和四角色绑定。
- 请求接口返回中文 answer、contextManifest 和 usage。
- 工作台返回 `modelGateway` 字段，指标里 requestCount 大于 0。

- [x] **步骤 4：更新本地运行文档**

在 `docs/development/local-run.md` 增加：

```markdown
## 第三阶段模型网关验证

服务端启动后可以查看默认模型配置：

```bash
curl -sS http://localhost:8080/api/projects/demo/model-gateway/config
```

可以创建一次产品角色模型请求：

```bash
curl -sS -X POST http://localhost:8080/api/projects/demo/roles/product/model-requests \
  -H 'Content-Type: application/json' \
  -d '{"instruction":"支付失败后允许用户重新发起支付。","contextBlocks":[{"type":"PROJECT_RULE","summary":"保持中文输出","allowedByGate":true}]}'
```

连续执行两次后，第二次相同角色会话应出现稳定前缀缓存命中 token。
```

- [x] **步骤 5：检查文档中文和 Maven 命令**

运行：

```bash
rg -n "T(O)DO|T[B]D|F[I]XME|\\bplace(holder)\\b|\\bS[u]mmary\\b|\\bG[o]als\\b|\\bAcceptance C[r]iteria\\b" docs || true
rg --pcre2 -n "(?<!bin/)\\bm[v]n\\b" docs || true
git diff --check
```

预期：未发现占位内容和未按本机路径书写的 Maven 命令；`git diff --check` 没有输出。若命中 `ModelGatewaySummary`，属于类型名误报。

- [x] **步骤 6：勾选计划状态并提交**

勾选本计划完成步骤，追加验证记录，并提交：

```bash
git add docs/development/local-run.md docs/superpowers/plans/2026-06-24-matrixcode-model-gateway.md
git commit -m "docs: 记录第三阶段模型网关验证"
```

---

## 自检记录

- 规格覆盖：供应商配置、角色模型选择、稳定提示词前缀、上下文清单、prompt cache、token 成本、工作台指标和桌面展示均有对应任务。
- 范围控制：真实流式调用、密钥加密、数据库持久化、登录鉴权、多供应商重试限流和本地执行代理不进入本计划。
- 类型一致性：服务端使用 `ModelRole`、`RoleModelBinding`、`ModelGatewayMetrics`、`ModelGatewaySummary`；桌面端使用同名 TypeScript 类型。
- 验证命令：服务端命令均使用 `/Users/Masons/Ai/Maven/bin/mvn -Dmaven.repo.local=/Users/Masons/Ai/Maven_Ai_Store`。
- 第三阶段验证结果：
  - 服务端全量测试：`/Users/Masons/Ai/Maven/bin/mvn -Dmaven.repo.local=/Users/Masons/Ai/Maven_Ai_Store -q -pl server test` 通过，Surefire 汇总为 108 个测试、0 失败、0 错误、0 跳过。
  - 桌面端验证：`cd desktop && npm test && npm run build && npm run tauri:build -- --help` 通过，Vitest 汇总为 31 个测试通过，TypeScript、Vite 构建和 Tauri 命令入口通过。
  - 模型网关配置接口：`GET /api/projects/demo/model-gateway/config` 返回 `local-deterministic` 默认供应商，以及 `PRODUCT`、`DEVELOPER`、`TESTER`、`OPERATIONS` 四个角色绑定。
  - 产品角色模型请求：首次 `POST /api/projects/demo/roles/product/model-requests` 返回中文草稿、`PROJECT_RULE` 上下文清单和 usage，其中缓存命中 0、未命中输入 87、输出 390。
  - 缓存验证：第二次相同请求返回缓存命中 73、未命中输入 14、输出 390，缓存命中率 0.84。
  - 工作台摘要：`GET /api/projects/demo/workbench` 返回 `modelGateway`，指标为 `requestCount=2`、`cacheHitTokens=73`、`cacheHitRate=0.42`、`sessionTokens=954`，事件流包含 `MODEL_REQUEST_COMPLETED`。
