package com.matrixcode.modelgateway;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.matrixcode.agentruntime.application.AgentRuntimeRepository;
import com.matrixcode.agentruntime.application.AgentRuntimeService;
import com.matrixcode.agentruntime.domain.AgentRunEventRecord;
import com.matrixcode.agentruntime.domain.AgentRunRecord;
import com.matrixcode.agent.application.LocalProductDraftAgent;
import com.matrixcode.context.application.ContextEngine;
import com.matrixcode.context.domain.ContextBlock;
import com.matrixcode.modelgateway.application.DeterministicModelAdapter;
import com.matrixcode.modelgateway.application.ModelCompletionClient;
import com.matrixcode.modelgateway.application.ModelGatewayService;
import com.matrixcode.modelgateway.application.ModelProviderRegistry;
import com.matrixcode.modelgateway.application.PromptCacheEstimator;
import com.matrixcode.modelgateway.application.PromptContractBuilder;
import com.matrixcode.modelgateway.application.RoleModelBindingService;
import com.matrixcode.modelgateway.application.VectorContextRetriever;
import com.matrixcode.modelgateway.domain.ModelCompletionResult;
import com.matrixcode.modelgateway.domain.ModelProtocol;
import com.matrixcode.modelgateway.domain.ModelProvider;
import com.matrixcode.modelgateway.domain.ModelRequestCommand;
import com.matrixcode.modelgateway.domain.ModelRole;
import com.matrixcode.modelgateway.domain.PromptContract;
import com.matrixcode.modelgateway.domain.ProviderTokenUsage;
import com.matrixcode.modelgateway.domain.RoleModelBinding;
import com.matrixcode.realtime.application.ProjectEventBus;
import com.matrixcode.roleagent.application.RoleAgentConfigCommand;
import com.matrixcode.roleagent.application.RoleAgentConfigService;
import com.matrixcode.usage.application.UsageCalculator;
import com.matrixcode.workbench.application.InMemoryWorkbenchStateStore;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class ModelGatewayServiceTest {

    private final ProjectEventBus events = new ProjectEventBus();
    private final ModelProviderRegistry providers = new ModelProviderRegistry();
    private final RoleModelBindingService bindings = new RoleModelBindingService(providers);
    private final InMemoryWorkbenchStateStore store = new InMemoryWorkbenchStateStore();
    private final RoleAgentConfigService roleAgentConfigs = new RoleAgentConfigService(store);
    private final ModelGatewayService service = new ModelGatewayService(
            providers,
            bindings,
            new PromptContractBuilder(),
            new PromptCacheEstimator(),
            new UsageCalculator(),
            new ContextEngine(),
            List.of(new DeterministicModelAdapter(new LocalProductDraftAgent())),
            events,
            roleAgentConfigs,
            store
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

    @Test
    void 模型请求记录保留触发它的Agent运行ID() {
        var response = service.request(new ModelRequestCommand(
                "demo",
                ModelRole.DEVELOPER,
                "user-dev",
                "run-1",
                "生成交接文档",
                List.of()
        ));

        assertThat(service.recentRequests("demo")).last().satisfies(record -> {
            assertThat(record.requestId()).isEqualTo(response.requestId());
            assertThat(record.agentRunId()).isEqualTo("run-1");
        });
    }

    @Test
    void 模型请求携带Agent运行ID时会追加运行时间线Trace() {
        var runtimeRepository = new RecordingAgentRuntimeRepository();
        var runtimeService = new AgentRuntimeService(Optional.of(runtimeRepository), new ObjectMapper(), java.time.Clock.systemUTC());
        var gateway = new ModelGatewayService(
                providers,
                bindings,
                new PromptContractBuilder(),
                new PromptCacheEstimator(),
                new UsageCalculator(),
                new ContextEngine(),
                List.of(new DeterministicModelAdapter(new LocalProductDraftAgent())),
                events,
                roleAgentConfigs,
                store,
                Optional.of(runtimeService)
        );

        var response = gateway.request(new ModelRequestCommand(
                "demo",
                ModelRole.DEVELOPER,
                "user-dev",
                "run-1",
                "生成交接文档",
                List.of()
        ));

        assertThat(runtimeRepository.events).singleElement().satisfies(event -> {
            assertThat(event.runId()).isEqualTo("run-1");
            assertThat(event.projectId()).isEqualTo("demo");
            assertThat(event.eventType()).isEqualTo("TOOL_TRACE");
            assertThat(event.eventPayload()).contains("\"toolName\":\"model-gateway.model-requests\"");
            assertThat(event.eventPayload()).contains("\"action\":\"complete-model-request\"");
            assertThat(event.eventPayload()).contains("\"referenceId\":\"%s\"".formatted(response.requestId()));
            assertThat(event.eventPayload()).contains("\"providerId\":\"%s\"".formatted(response.binding().providerId()));
            assertThat(event.eventPayload()).contains("\"modelName\":\"%s\"".formatted(response.binding().model()));
            assertThat(event.eventPayload()).contains("\"cacheSource\":\"ESTIMATED\"");
            assertThat(event.eventPayload()).contains("\"stablePrefixHash\":\"%s\"".formatted(response.usage().stablePrefixHash()));
            assertThat(event.eventPayload()).contains("\"cachePolicyId\":\"stable-platform-prefix-v1\"");
            assertThat(event.eventPayload()).contains("\"volatileSuffixStrategy\":\"role-prompt-and-dynamic-context\"");
        });
    }

    @Test
    void 服务重建后恢复最近模型请求和指标() {
        var store = new InMemoryWorkbenchStateStore();
        var firstEvents = new ProjectEventBus(store);
        var firstProviders = new ModelProviderRegistry();
        var firstBindings = new RoleModelBindingService(firstProviders, store);
        var firstService = gateway(firstProviders, firstBindings, firstEvents, store);
        var response = firstService.request(new ModelRequestCommand("demo", ModelRole.PRODUCT, "第一次请求", List.of()));

        var secondEvents = new ProjectEventBus(store);
        var secondProviders = new ModelProviderRegistry();
        var secondBindings = new RoleModelBindingService(secondProviders, store);
        var secondService = gateway(secondProviders, secondBindings, secondEvents, store);

        assertThat(secondService.recentRequests("demo")).singleElement().satisfies(record -> {
            assertThat(record.requestId()).isEqualTo(response.requestId());
            assertThat(record.role()).isEqualTo(ModelRole.PRODUCT);
        });
        assertThat(secondService.metrics("demo").requestCount()).isEqualTo(1);
    }

    @Test
    void 模型请求使用角色智能体配置中的供应商模型和提示词模板() {
        providers.upsert(new ModelProvider(
                "deepseek",
                "DeepSeek",
                ModelProtocol.OPENAI_COMPATIBLE,
                "https://api.deepseek.com",
                "MATRIXCODE_DEEPSEEK_API_KEY",
                true
        ));
        var calls = new ArrayList<String>();
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
                calls.add(provider.id() + "|" + binding.model() + "|" + contract.systemPrefix() + "|" + instruction);
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
        var current = roleAgentConfigs.require("demo", ModelRole.DEVELOPER);
        roleAgentConfigs.update("demo", ModelRole.DEVELOPER, new RoleAgentConfigCommand(
                current.displayName(),
                current.agentKind(),
                "deepseek",
                "deepseek-chat",
                current.toolContractVersion(),
                "你是开发编码智能体，必须先读代码再修改。",
                "任务：{{instruction}}",
                current.themeColor(),
                current.fontFamily(),
                current.fontSize(),
                current.sortOrder(),
                current.enabled(),
                current.cachePolicyId(),
                current.volatileSuffixStrategy()
        ));

        var response = gateway.request(new ModelRequestCommand(
                "demo",
                ModelRole.DEVELOPER,
                "修复文档交接布局",
                List.of()
        ));

        assertThat(response.answer()).isEqualTo("真实兼容模型返回");
        assertThat(response.binding().providerId()).isEqualTo("deepseek");
        assertThat(response.binding().model()).isEqualTo("deepseek-chat");
        assertThat(calls).hasSize(1);
        assertThat(calls.getFirst())
                .contains("deepseek|deepseek-chat")
                .contains("必须先读代码")
                .contains("任务：修复文档交接布局");
    }

    @Test
    void 供应商真实缓存用量优先于本地估算() {
        providers.upsert(new ModelProvider(
                "deepseek",
                "DeepSeek",
                ModelProtocol.OPENAI_COMPATIBLE,
                "https://api.deepseek.com",
                "MATRIXCODE_DEEPSEEK_API_KEY",
                true
        ));
        var cacheScopes = new ArrayList<String>();
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
                cacheScopes.add(cacheScopeId);
                return ModelCompletionResult.withProviderUsage(
                        "真实兼容模型返回",
                        new ProviderTokenUsage(200, 50, 25)
                );
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
        var current = roleAgentConfigs.require("demo", ModelRole.DEVELOPER);
        roleAgentConfigs.update("demo", ModelRole.DEVELOPER, new RoleAgentConfigCommand(
                current.displayName(),
                current.agentKind(),
                "deepseek",
                "deepseek-chat",
                current.toolContractVersion(),
                current.systemPrompt(),
                current.userPromptTemplate(),
                current.themeColor(),
                current.fontFamily(),
                current.fontSize(),
                current.sortOrder(),
                current.enabled(),
                current.cachePolicyId(),
                current.volatileSuffixStrategy()
        ));

        var response = gateway.request(new ModelRequestCommand(
                "demo",
                ModelRole.DEVELOPER,
                "生成受控 patch",
                List.of()
        ));

        assertThat(cacheScopes).containsExactly("matrixcode_demo_DEVELOPER_deepseek_deepseek-chat");
        assertThat(response.usage().roleSessionId()).isEqualTo("demo:DEVELOPER");
        assertThat(response.usage().cacheHitTokens()).isEqualTo(200);
        assertThat(response.usage().cacheMissInputTokens()).isEqualTo(50);
        assertThat(response.usage().outputTokens()).isEqualTo(25);
        assertThat(response.usage().cacheHitRate()).isEqualTo(0.8);
        assertThat(response.usage().cacheSource()).isEqualTo("PROVIDER");
        assertThat(response.usage().providerUsageAvailable()).isTrue();
        assertThat(response.usage().cacheScopeId()).isEqualTo("matrixcode_demo_DEVELOPER_deepseek_deepseek-chat");
        assertThat(response.usage().stablePrefixHash()).isEqualTo(response.promptContract().stablePrefixHash());
        assertThat(response.usage().cachePolicyId()).isEqualTo("stable-platform-prefix-v1");
        assertThat(response.usage().volatileSuffixStrategy()).isEqualTo("role-prompt-and-dynamic-context");
        assertThat(gateway.metrics("demo").cacheHitTokens()).isEqualTo(200);
    }

    @Test
    void 缓存作用域符合DeepSeekUserId字符约束() {
        providers.upsert(new ModelProvider(
                "kimi",
                "Kimi",
                ModelProtocol.OPENAI_COMPATIBLE,
                "https://api.moonshot.cn/v1",
                "MATRIXCODE_KIMI_API_KEY",
                true
        ));
        var cacheScopes = new ArrayList<String>();
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
                cacheScopes.add(cacheScopeId);
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
        updateDeveloperBinding("demo.project", "kimi", "kimi-k2.5");

        gateway.request(new ModelRequestCommand(
                "demo.project",
                ModelRole.DEVELOPER,
                "验证缓存作用域",
                List.of()
        ));

        assertThat(cacheScopes).containsExactly("matrixcode_demo_project_DEVELOPER_kimi_kimi-k2_5");
        assertThat(cacheScopes.getFirst()).matches("[A-Za-z0-9_-]+");
    }

    @Test
    void 不同供应商同模型名称不共享本地缓存作用域() {
        providers.upsert(new ModelProvider(
                "deepseek",
                "DeepSeek",
                ModelProtocol.OPENAI_COMPATIBLE,
                "https://api.deepseek.com",
                "MATRIXCODE_DEEPSEEK_API_KEY",
                true
        ));
        providers.upsert(new ModelProvider(
                "qwen",
                "千问",
                ModelProtocol.OPENAI_COMPATIBLE,
                "https://dashscope.aliyuncs.com/compatible-mode/v1",
                "MATRIXCODE_QWEN_API_KEY",
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
        updateDeveloperBinding("deepseek", "shared-chat");
        var first = gateway.request(new ModelRequestCommand(
                "demo",
                ModelRole.DEVELOPER,
                "第一次请求",
                List.of()
        ));
        updateDeveloperBinding("qwen", "shared-chat");

        var second = gateway.request(new ModelRequestCommand(
                "demo",
                ModelRole.DEVELOPER,
                "第二次请求",
                List.of()
        ));

        assertThat(first.usage().cacheHitTokens()).isZero();
        assertThat(first.usage().cacheSource()).isEqualTo("ESTIMATED");
        assertThat(first.usage().providerUsageAvailable()).isFalse();
        assertThat(first.usage().cacheScopeId()).isEqualTo("matrixcode_demo_DEVELOPER_deepseek_shared-chat");
        assertThat(first.usage().stablePrefixHash()).isEqualTo(first.promptContract().stablePrefixHash());
        assertThat(second.usage().cacheHitTokens()).isZero();
        assertThat(second.usage().cacheSource()).isEqualTo("ESTIMATED");
        assertThat(second.usage().providerUsageAvailable()).isFalse();
        assertThat(second.usage().cacheScopeId()).isEqualTo("matrixcode_demo_DEVELOPER_qwen_shared-chat");
        assertThat(second.usage().stablePrefixHash()).isEqualTo(second.promptContract().stablePrefixHash());
    }

    @Test
    void 角色缓存作用域策略允许同供应商不同模型复用会话作用域() {
        providers.upsert(new ModelProvider(
                "deepseek",
                "DeepSeek",
                ModelProtocol.OPENAI_COMPATIBLE,
                "https://api.deepseek.com",
                "MATRIXCODE_DEEPSEEK_API_KEY",
                true
        ));
        var cacheScopes = new ArrayList<String>();
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
                cacheScopes.add(cacheScopeId);
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
        updateDeveloperConfig(
                "demo",
                "deepseek",
                "deepseek-chat",
                roleAgentConfigs.require("demo", ModelRole.DEVELOPER).systemPrompt(),
                "provider-role"
        );
        var first = gateway.request(new ModelRequestCommand(
                "demo",
                ModelRole.DEVELOPER,
                "第一次请求",
                List.of()
        ));
        updateDeveloperConfig(
                "demo",
                "deepseek",
                "deepseek-reasoner",
                roleAgentConfigs.require("demo", ModelRole.DEVELOPER).systemPrompt(),
                "provider-role"
        );

        var second = gateway.request(new ModelRequestCommand(
                "demo",
                ModelRole.DEVELOPER,
                "第二次请求",
                List.of()
        ));

        assertThat(cacheScopes)
                .containsExactly("matrixcode_demo_DEVELOPER_deepseek", "matrixcode_demo_DEVELOPER_deepseek");
        assertThat(first.usage().cacheScopeId()).isEqualTo(second.usage().cacheScopeId());
        assertThat(second.usage().cacheHitTokens()).isGreaterThan(0);
    }

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
        assertThat(second.usage().cachePolicyId()).isEqualTo("stable-platform-prefix-v1");
        assertThat(second.usage().volatileSuffixStrategy()).isEqualTo("role-prompt-and-dynamic-context");
    }

    @Test
    void 模型请求使用角色智能体缓存策略配置并写入AgentTrace() {
        var runtimeRepository = new RecordingAgentRuntimeRepository();
        var runtimeService = new AgentRuntimeService(Optional.of(runtimeRepository), new ObjectMapper(), java.time.Clock.systemUTC());
        var gateway = new ModelGatewayService(
                providers,
                bindings,
                new PromptContractBuilder(),
                new PromptCacheEstimator(),
                new UsageCalculator(),
                new ContextEngine(),
                List.of(new DeterministicModelAdapter(new LocalProductDraftAgent())),
                events,
                roleAgentConfigs,
                store,
                Optional.of(runtimeService)
        );
        var current = roleAgentConfigs.require("demo", ModelRole.DEVELOPER);
        roleAgentConfigs.update("demo", ModelRole.DEVELOPER, new RoleAgentConfigCommand(
                current.displayName(),
                current.agentKind(),
                current.providerId(),
                current.model(),
                current.toolContractVersion(),
                current.systemPrompt(),
                current.userPromptTemplate(),
                current.themeColor(),
                current.fontFamily(),
                current.fontSize(),
                current.sortOrder(),
                current.enabled(),
                "deepseek-prefix-v2",
                "stable-prefix-dynamic-tail"
        ));

        var response = gateway.request(new ModelRequestCommand(
                "demo",
                ModelRole.DEVELOPER,
                "user-dev",
                "run-cache",
                "生成可缓存的开发计划",
                List.of()
        ));

        assertThat(response.usage().cachePolicyId()).isEqualTo("deepseek-prefix-v2");
        assertThat(response.usage().volatileSuffixStrategy()).isEqualTo("stable-prefix-dynamic-tail");
        assertThat(response.usage().promptPartitionPolicyId()).isEqualTo("deepseek-reasonix-partitions-v1");
        assertThat(response.usage().promptPartitionFingerprint()).isNotBlank();
        assertThat(response.usage().stablePartitionCount()).isEqualTo(2);
        assertThat(response.usage().volatilePartitionCount()).isEqualTo(3);
        assertThat(gateway.recentRequests("demo")).last().satisfies(record -> {
            assertThat(record.agentRunId()).isEqualTo("run-cache");
            assertThat(record.usage().cachePolicyId()).isEqualTo("deepseek-prefix-v2");
            assertThat(record.usage().volatileSuffixStrategy()).isEqualTo("stable-prefix-dynamic-tail");
            assertThat(record.usage().promptPartitionPolicyId()).isEqualTo("deepseek-reasonix-partitions-v1");
            assertThat(record.usage().promptPartitionFingerprint()).isEqualTo(response.usage().promptPartitionFingerprint());
        });
        assertThat(runtimeRepository.events).singleElement().satisfies(event -> {
            assertThat(event.eventPayload()).contains("\"cachePolicyId\":\"deepseek-prefix-v2\"");
            assertThat(event.eventPayload()).contains("\"volatileSuffixStrategy\":\"stable-prefix-dynamic-tail\"");
            assertThat(event.eventPayload()).contains("\"promptPartitionPolicyId\":\"deepseek-reasonix-partitions-v1\"");
            assertThat(event.eventPayload()).contains("\"promptPartitionFingerprint\":\"%s\"".formatted(response.usage().promptPartitionFingerprint()));
            assertThat(event.eventPayload()).contains("\"stablePartitionCount\":2");
            assertThat(event.eventPayload()).contains("\"volatilePartitionCount\":3");
        });
    }

    @Test
    void 模型请求会合并向量召回上下文() {
        var vectorContext = new VectorContextRetriever() {
            @Override
            public List<ContextBlock> recall(String projectId, ModelRole role, String instruction) {
                assertThat(projectId).isEqualTo("demo");
                assertThat(role).isEqualTo(ModelRole.DEVELOPER);
                assertThat(instruction).contains("交接文档");
                return List.of(new ContextBlock("VECTOR_CONTEXT", "历史交接文档：部署步骤必须包含回滚说明", true));
            }
        };
        var gateway = new ModelGatewayService(
                providers,
                bindings,
                new PromptContractBuilder(),
                new PromptCacheEstimator(),
                new UsageCalculator(),
                new ContextEngine(),
                List.of(new DeterministicModelAdapter(new LocalProductDraftAgent())),
                events,
                roleAgentConfigs,
                vectorContext,
                store
        );

        var response = gateway.request(new ModelRequestCommand(
                "demo",
                ModelRole.DEVELOPER,
                "生成交接文档",
                List.of(new ContextBlock("PROJECT_RULE", "保持中文输出", true))
        ));

        assertThat(response.contextManifest().blocks()).extracting(ContextBlock::type)
                .containsExactly("PROJECT_RULE", "VECTOR_CONTEXT");
        assertThat(response.contextManifest().blocks()).extracting(ContextBlock::summary)
                .anySatisfy(summary -> assertThat(summary).contains("部署步骤必须包含回滚说明"));
    }

    private ModelGatewayService gateway(
            ModelProviderRegistry providerRegistry,
            RoleModelBindingService bindingService,
            ProjectEventBus eventBus,
            InMemoryWorkbenchStateStore store
    ) {
        return new ModelGatewayService(
                providerRegistry,
                bindingService,
                new PromptContractBuilder(),
                new PromptCacheEstimator(),
                new UsageCalculator(),
                new ContextEngine(),
                List.of(new DeterministicModelAdapter(new LocalProductDraftAgent())),
                eventBus,
                new RoleAgentConfigService(store),
                store
        );
    }

    private void updateDeveloperBinding(String providerId, String model) {
        updateDeveloperBinding("demo", providerId, model);
    }

    private void updateDeveloperBinding(String projectId, String providerId, String model) {
        updateDeveloperConfig(projectId, providerId, model, roleAgentConfigs.require(projectId, ModelRole.DEVELOPER).systemPrompt());
    }

    private void updateDeveloperConfig(String projectId, String providerId, String model, String systemPrompt) {
        updateDeveloperConfig(projectId, providerId, model, systemPrompt, roleAgentConfigs.require(projectId, ModelRole.DEVELOPER).cacheScopeStrategy());
    }

    private void updateDeveloperConfig(
            String projectId,
            String providerId,
            String model,
            String systemPrompt,
            String cacheScopeStrategy
    ) {
        var current = roleAgentConfigs.require(projectId, ModelRole.DEVELOPER);
        roleAgentConfigs.update(projectId, ModelRole.DEVELOPER, new RoleAgentConfigCommand(
                current.displayName(),
                current.agentKind(),
                providerId,
                model,
                current.toolContractVersion(),
                systemPrompt,
                current.userPromptTemplate(),
                current.themeColor(),
                current.fontFamily(),
                current.fontSize(),
                current.sortOrder(),
                current.enabled(),
                current.cachePolicyId(),
                current.volatileSuffixStrategy(),
                cacheScopeStrategy
        ));
    }

    private static final class RecordingAgentRuntimeRepository implements AgentRuntimeRepository {

        private final List<AgentRunRecord> savedRuns = new ArrayList<>();
        private final List<AgentRunEventRecord> events = new ArrayList<>();

        @Override
        public void saveRun(AgentRunRecord run) {
            savedRuns.add(run);
        }

        @Override
        public void appendEvent(AgentRunEventRecord event) {
            events.add(event);
        }

        @Override
        public Optional<AgentRunRecord> findRun(String runId) {
            for (var index = savedRuns.size() - 1; index >= 0; index--) {
                var run = savedRuns.get(index);
                if (run.id().equals(runId)) {
                    return Optional.of(run);
                }
            }
            return Optional.empty();
        }

        @Override
        public List<AgentRunRecord> recentRuns(String projectId, int limit) {
            return savedRuns.stream()
                    .filter(run -> run.projectId().equals(projectId))
                    .limit(limit)
                    .toList();
        }

        @Override
        public List<AgentRunEventRecord> eventsForRun(String runId) {
            return events.stream()
                    .filter(event -> event.runId().equals(runId))
                    .toList();
        }
    }
}
