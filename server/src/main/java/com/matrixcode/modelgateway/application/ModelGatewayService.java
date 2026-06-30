package com.matrixcode.modelgateway.application;

import com.matrixcode.agentruntime.application.AgentRuntimeService;
import com.matrixcode.context.application.ContextEngine;
import com.matrixcode.modelgateway.domain.ModelCompletionResult;
import com.matrixcode.modelgateway.domain.ModelCostBreakdown;
import com.matrixcode.modelgateway.domain.ModelCostTrendBucket;
import com.matrixcode.modelgateway.domain.ModelGatewayConfig;
import com.matrixcode.modelgateway.domain.ModelGatewayMetrics;
import com.matrixcode.modelgateway.domain.ModelGatewaySummary;
import com.matrixcode.modelgateway.domain.ModelCostTrendPoint;
import com.matrixcode.modelgateway.domain.ModelCostTrendReport;
import com.matrixcode.modelgateway.domain.ModelRequestCommand;
import com.matrixcode.modelgateway.domain.ModelRequestRecord;
import com.matrixcode.modelgateway.domain.ModelRunRequestPage;
import com.matrixcode.modelgateway.domain.ModelResponse;
import com.matrixcode.modelgateway.domain.ModelRole;
import com.matrixcode.modelgateway.domain.PromptEstimate;
import com.matrixcode.modelgateway.domain.PromptContract;
import com.matrixcode.modelgateway.domain.ProviderTokenUsage;
import com.matrixcode.modelgateway.domain.RoleModelBinding;
import com.matrixcode.realtime.application.ProjectEventBus;
import com.matrixcode.realtime.domain.ProjectEvent;
import com.matrixcode.roleagent.application.RoleAgentConfigService;
import com.matrixcode.roleagent.domain.RoleAgentConfig;
import com.matrixcode.usage.application.UsageCalculator;
import com.matrixcode.usage.domain.ModelPrice;
import com.matrixcode.usage.domain.UsageRecord;
import com.matrixcode.workbench.application.InMemoryWorkbenchStateStore;
import com.matrixcode.workbench.application.ProjectActivityRepository;
import com.matrixcode.workbench.application.WorkbenchStateStore;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
public class ModelGatewayService {

    private final ModelProviderRegistry providerRegistry;
    private final RoleModelBindingService bindingService;
    private final PromptContractBuilder promptContractBuilder;
    private final PromptCacheEstimator promptCacheEstimator;
    private final UsageCalculator usageCalculator;
    private final ContextEngine contextEngine;
    private final List<ModelCompletionClient> modelClients;
    private final ProjectEventBus eventBus;
    private final RoleAgentConfigService roleAgentConfigService;
    private final VectorContextRetriever vectorContextRetriever;
    private final Map<String, CopyOnWriteArrayList<ModelRequestRecord>> requests = new ConcurrentHashMap<>();
    private final WorkbenchStateStore stateStore;
    private final ProjectActivityRepository activityRepository;
    private final Optional<AgentRuntimeService> agentRuntimeService;

    public ModelGatewayService(
            ModelProviderRegistry providerRegistry,
            RoleModelBindingService bindingService,
            PromptContractBuilder promptContractBuilder,
            PromptCacheEstimator promptCacheEstimator,
            UsageCalculator usageCalculator,
            ContextEngine contextEngine,
            DeterministicModelAdapter modelAdapter,
            ProjectEventBus eventBus
    ) {
        this(
                providerRegistry,
                bindingService,
                promptContractBuilder,
                promptCacheEstimator,
                usageCalculator,
                contextEngine,
                List.of(modelAdapter),
                eventBus,
                new RoleAgentConfigService(new InMemoryWorkbenchStateStore()),
                VectorContextRetriever.disabled(),
                new InMemoryWorkbenchStateStore(),
                (ProjectActivityRepository) null
        );
    }

    public ModelGatewayService(
            ModelProviderRegistry providerRegistry,
            RoleModelBindingService bindingService,
            PromptContractBuilder promptContractBuilder,
            PromptCacheEstimator promptCacheEstimator,
            UsageCalculator usageCalculator,
            ContextEngine contextEngine,
            List<ModelCompletionClient> modelClients,
            ProjectEventBus eventBus,
            RoleAgentConfigService roleAgentConfigService,
            WorkbenchStateStore stateStore,
            ProjectActivityRepository activityRepository
    ) {
        this(
                providerRegistry,
                bindingService,
                promptContractBuilder,
                promptCacheEstimator,
                usageCalculator,
                contextEngine,
                modelClients,
                eventBus,
                roleAgentConfigService,
                VectorContextRetriever.disabled(),
                stateStore,
                activityRepository
        );
    }

    @Autowired
    public ModelGatewayService(
            ModelProviderRegistry providerRegistry,
            RoleModelBindingService bindingService,
            PromptContractBuilder promptContractBuilder,
            PromptCacheEstimator promptCacheEstimator,
            UsageCalculator usageCalculator,
            ContextEngine contextEngine,
            List<ModelCompletionClient> modelClients,
            ProjectEventBus eventBus,
            RoleAgentConfigService roleAgentConfigService,
            VectorContextRetriever vectorContextRetriever,
            WorkbenchStateStore stateStore,
            Optional<ProjectActivityRepository> activityRepository,
            Optional<AgentRuntimeService> agentRuntimeService
    ) {
        this(
                providerRegistry,
                bindingService,
                promptContractBuilder,
                promptCacheEstimator,
                usageCalculator,
                contextEngine,
                modelClients,
                eventBus,
                roleAgentConfigService,
                vectorContextRetriever,
                stateStore,
                activityRepository.orElse(null),
                agentRuntimeService
        );
    }

    public ModelGatewayService(
            ModelProviderRegistry providerRegistry,
            RoleModelBindingService bindingService,
            PromptContractBuilder promptContractBuilder,
            PromptCacheEstimator promptCacheEstimator,
            UsageCalculator usageCalculator,
            ContextEngine contextEngine,
            List<ModelCompletionClient> modelClients,
            ProjectEventBus eventBus,
            RoleAgentConfigService roleAgentConfigService,
            VectorContextRetriever vectorContextRetriever,
            WorkbenchStateStore stateStore
    ) {
        this(
                providerRegistry,
                bindingService,
                promptContractBuilder,
                promptCacheEstimator,
                usageCalculator,
                contextEngine,
                modelClients,
                eventBus,
                roleAgentConfigService,
                vectorContextRetriever,
                stateStore,
                (ProjectActivityRepository) null
        );
    }

    public ModelGatewayService(
            ModelProviderRegistry providerRegistry,
            RoleModelBindingService bindingService,
            PromptContractBuilder promptContractBuilder,
            PromptCacheEstimator promptCacheEstimator,
            UsageCalculator usageCalculator,
            ContextEngine contextEngine,
            List<ModelCompletionClient> modelClients,
            ProjectEventBus eventBus,
            RoleAgentConfigService roleAgentConfigService,
            VectorContextRetriever vectorContextRetriever,
            WorkbenchStateStore stateStore,
            ProjectActivityRepository activityRepository
    ) {
        this(
                providerRegistry,
                bindingService,
                promptContractBuilder,
                promptCacheEstimator,
                usageCalculator,
                contextEngine,
                modelClients,
                eventBus,
                roleAgentConfigService,
                vectorContextRetriever,
                stateStore,
                activityRepository,
                Optional.empty()
        );
    }

    public ModelGatewayService(
            ModelProviderRegistry providerRegistry,
            RoleModelBindingService bindingService,
            PromptContractBuilder promptContractBuilder,
            PromptCacheEstimator promptCacheEstimator,
            UsageCalculator usageCalculator,
            ContextEngine contextEngine,
            List<ModelCompletionClient> modelClients,
            ProjectEventBus eventBus,
            RoleAgentConfigService roleAgentConfigService,
            VectorContextRetriever vectorContextRetriever,
            WorkbenchStateStore stateStore,
            ProjectActivityRepository activityRepository,
            Optional<AgentRuntimeService> agentRuntimeService
    ) {
        this.providerRegistry = providerRegistry;
        this.bindingService = bindingService;
        this.promptContractBuilder = promptContractBuilder;
        this.promptCacheEstimator = promptCacheEstimator;
        this.usageCalculator = usageCalculator;
        this.contextEngine = contextEngine;
        this.modelClients = List.copyOf(modelClients);
        this.eventBus = eventBus;
        this.roleAgentConfigService = Objects.requireNonNull(roleAgentConfigService, "roleAgentConfigService 不能为空");
        this.vectorContextRetriever = Objects.requireNonNull(vectorContextRetriever, "vectorContextRetriever 不能为空");
        this.stateStore = stateStore;
        this.activityRepository = activityRepository;
        this.agentRuntimeService = agentRuntimeService == null ? Optional.empty() : agentRuntimeService;
        loadInitialRequests();
    }

    public ModelGatewayService(
            ModelProviderRegistry providerRegistry,
            RoleModelBindingService bindingService,
            PromptContractBuilder promptContractBuilder,
            PromptCacheEstimator promptCacheEstimator,
            UsageCalculator usageCalculator,
            ContextEngine contextEngine,
            List<ModelCompletionClient> modelClients,
            ProjectEventBus eventBus,
            RoleAgentConfigService roleAgentConfigService,
            WorkbenchStateStore stateStore
    ) {
        this(
                providerRegistry,
                bindingService,
                promptContractBuilder,
                promptCacheEstimator,
                usageCalculator,
                contextEngine,
                modelClients,
                eventBus,
                roleAgentConfigService,
                VectorContextRetriever.disabled(),
                stateStore,
                (ProjectActivityRepository) null
        );
    }

    public ModelGatewayService(
            ModelProviderRegistry providerRegistry,
            RoleModelBindingService bindingService,
            PromptContractBuilder promptContractBuilder,
            PromptCacheEstimator promptCacheEstimator,
            UsageCalculator usageCalculator,
            ContextEngine contextEngine,
            List<ModelCompletionClient> modelClients,
            ProjectEventBus eventBus,
            RoleAgentConfigService roleAgentConfigService,
            WorkbenchStateStore stateStore,
            Optional<AgentRuntimeService> agentRuntimeService
    ) {
        this(
                providerRegistry,
                bindingService,
                promptContractBuilder,
                promptCacheEstimator,
                usageCalculator,
                contextEngine,
                modelClients,
                eventBus,
                roleAgentConfigService,
                VectorContextRetriever.disabled(),
                stateStore,
                (ProjectActivityRepository) null,
                agentRuntimeService
        );
    }

    public ModelResponse request(ModelRequestCommand command) {
        var roleConfig = roleAgentConfigService.require(command.projectId(), command.role());
        if (!roleConfig.enabled()) {
            throw new IllegalArgumentException("角色智能体未启用：" + roleConfig.displayName());
        }
        var binding = effectiveBinding(command, roleConfig);
        var provider = providerRegistry.require(binding.providerId());
        if (!provider.enabled()) {
            throw new IllegalArgumentException("模型供应商未启用：" + provider.id());
        }
        var contract = promptContractBuilder.build(
                command.role(),
                binding.model(),
                binding.toolContractVersion(),
                roleConfig.systemPrompt()
        );
        var contextBlocks = new java.util.ArrayList<>(command.contextBlocks());
        contextBlocks.addAll(vectorContextRetriever.recall(command.projectId(), command.role(), command.instruction()));
        var manifest = contextEngine.build(command.role().name(), contextBlocks);
        var renderedInstruction = renderInstruction(roleConfig, command.instruction());
        var roleSessionId = command.projectId() + ":" + command.role().name();
        var cacheScopeId = cacheScopeId(
                command.projectId(),
                command.role(),
                binding.providerId(),
                binding.model(),
                roleConfig.cacheScopeStrategy()
        );
        var completion = modelClient(provider).complete(provider, binding, contract, renderedInstruction, cacheScopeId);
        var answer = completion.answer();
        var contextTypes = manifest.blocks().stream()
                .map(block -> block.type())
                .toList();
        var providerUsageAvailable = completion.usage().filter(ProviderTokenUsage::hasPromptCacheTokens).isPresent();
        var estimate = promptEstimate(completion, cacheScopeId, contract, contextTypes, command.instruction(), answer);
        var usage = usageCalculator.calculate(
                        roleSessionId,
                        new ModelPrice(
                                binding.model(),
                                binding.currency(),
                                binding.cacheHitPerMillion(),
                                binding.cacheMissInputPerMillion(),
                                binding.outputPerMillion()
                        ),
                        estimate.cacheHitTokens(),
                        estimate.cacheMissInputTokens(),
                        estimate.outputTokens()
                )
                .withCacheTrace(
                        providerUsageAvailable ? "PROVIDER" : "ESTIMATED",
                        cacheScopeId,
                        contract.stablePrefixHash(),
                        providerUsageAvailable,
                        roleConfig.cachePolicyId(),
                        roleConfig.volatileSuffixStrategy()
                )
                .withPromptPartitionTrace(
                        contract.promptPartitionPolicyId(),
                        contract.promptPartitionFingerprint(),
                        contract.stablePartitionCount(),
                        contract.volatilePartitionCount()
                );
        var createdAt = Instant.now();
        var requestId = UUID.randomUUID().toString();
        var response = new ModelResponse(requestId, answer, manifest, usage, binding, contract, createdAt);
        requests.computeIfAbsent(command.projectId(), ignored -> new CopyOnWriteArrayList<>())
                .add(new ModelRequestRecord(
                        requestId,
                        command.projectId(),
                        command.role(),
                        binding.providerId(),
                        binding.model(),
                        summarize(answer),
                        command.actorUserId(),
                        command.agentRunId(),
                        usage,
                        contextTypes,
                        createdAt
                ));
        saveModelRequests();
        appendModelRequestTrace(command, requestId, binding, usage);
        eventBus.publish(new ProjectEvent(
                command.projectId(),
                "MODEL_REQUEST_COMPLETED",
                "%s使用%s完成模型请求，缓存命中率 %.0f%%".formatted(
                        command.role().displayName(),
                        binding.model(),
                        usage.cacheHitRate() * 100
                )
        ));
        return response;
    }

    /**
     * 把模型请求完成事件写入对应 Agent 运行时间线。
     *
     * <p>只有命令显式携带 `agentRunId` 时才写 Runtime trace，避免把普通工作台模型请求误归属到某次运行。
     * trace 只保存 requestId、供应商、模型和缓存策略等低敏摘要，不保存 prompt、响应正文、向量召回正文、
     * 工具输出、API Key 或数据库密码。</p>
     */
    private void appendModelRequestTrace(
            ModelRequestCommand command,
            String requestId,
            RoleModelBinding binding,
            UsageRecord usage
    ) {
        if (command.agentRunId().isBlank()) {
            return;
        }
        agentRuntimeService.ifPresent(runtimeService -> runtimeService.appendModelRequestTrace(
                command.agentRunId(),
                command.projectId(),
                requestId,
                binding.providerId(),
                binding.model(),
                usage.cacheSource(),
                usage.stablePrefixHash(),
                usage.cachePolicyId(),
                usage.volatileSuffixStrategy(),
                usage.promptPartitionPolicyId(),
                usage.promptPartitionFingerprint(),
                usage.stablePartitionCount(),
                usage.volatilePartitionCount()
        ));
    }

    /**
     * 生成供应商侧和本地估算共用的缓存作用域。
     *
     * <p>用户可见的 roleSessionId 保持为“项目:角色”。默认策略继续包含供应商和模型，避免切换模型后
     * 误用上一供应商的本地缓存估算。角色配置可显式选择更激进的作用域策略：`provider-role`
     * 用于同供应商多模型共享稳定前缀，`project-role` 用于角色级最大复用。DeepSeek 的 `user_id`
     * 只允许字母、数字、下划线和短横线，因此这里会把其他字符规范化为下划线。</p>
     */
    private String cacheScopeId(
            String projectId,
            ModelRole role,
            String providerId,
            String model,
            String cacheScopeStrategy
    ) {
        var projectPart = cacheScopePart(projectId);
        var rolePart = role.name();
        var providerPart = cacheScopePart(providerId);
        return switch (cacheScopeStrategy == null ? "provider-model" : cacheScopeStrategy) {
            case "project-role" -> "matrixcode_%s_%s".formatted(projectPart, rolePart);
            case "provider-role" -> "matrixcode_%s_%s_%s".formatted(projectPart, rolePart, providerPart);
            default -> "matrixcode_%s_%s_%s_%s".formatted(
                    projectPart,
                    rolePart,
                    providerPart,
                    cacheScopePart(model)
            );
        };
    }

    private String cacheScopePart(String value) {
        if (value == null || value.isBlank()) {
            return "unknown";
        }
        var normalized = value.trim().replaceAll("[^A-Za-z0-9_-]", "_");
        return normalized.isBlank() ? "unknown" : normalized;
    }

    /**
     * 选择模型请求的 token 口径。
     *
     * <p>真实供应商 usage 是账单和缓存命中率的第一来源；只有供应商未返回 prompt cache 字段时，
     * 才使用 MatrixCode 的稳定前缀估算，确保普通 OpenAI 兼容供应商仍有可观测指标。</p>
     */
    private PromptEstimate promptEstimate(
            ModelCompletionResult completion,
            String cacheScopeId,
            PromptContract contract,
            List<String> contextTypes,
            String instruction,
            String answer
    ) {
        return completion.usage()
                .filter(ProviderTokenUsage::hasPromptCacheTokens)
                .map(usage -> new PromptEstimate(
                        usage.cacheHitTokens(),
                        usage.cacheMissInputTokens(),
                        usage.outputTokens()
                ))
                .orElseGet(() -> promptCacheEstimator.estimate(cacheScopeId, contract, contextTypes, instruction, answer));
    }

    public List<ModelRequestRecord> recentRequests(String projectId) {
        return List.copyOf(projectRequests(projectId));
    }

    public ModelGatewayMetrics metrics(String projectId) {
        return ModelGatewayMetrics.from(projectRequests(projectId));
    }

    /**
     * 聚合项目级长期模型成本趋势。
     *
     * <p>趋势直接复用已持久化的模型请求 usage 摘要，按 UTC 自然日、角色、供应商和模型分组；
     * 方法不读取或返回 prompt 正文、模型响应正文、向量召回正文、工具输出或密钥。</p>
     */
    public ModelCostTrendReport projectCostTrends(String projectId, int days) {
        var normalizedProjectId = requireText(projectId, "项目编号不能为空");
        var normalizedDays = Math.max(1, Math.min(180, days));
        var to = Instant.now();
        var from = to.minusSeconds(normalizedDays * 24L * 60L * 60L);
        var records = projectRequests(normalizedProjectId).stream()
                .filter(record -> !record.createdAt().isBefore(from) && !record.createdAt().isAfter(to))
                .sorted(Comparator.comparing(ModelRequestRecord::createdAt).thenComparing(ModelRequestRecord::requestId))
                .toList();
        return new ModelCostTrendReport(
                normalizedProjectId,
                normalizedDays,
                "UTC",
                from,
                to,
                ModelGatewayMetrics.from(records),
                dailyTrend(records),
                breakdown(records, record -> record.role().name()),
                breakdown(records, ModelRequestRecord::providerId),
                breakdown(records, ModelRequestRecord::model)
        );
    }

    /**
     * 按 Agent 运行查询模型请求分页与成本趋势。
     *
     * <p>该查询只消费已经脱敏的模型请求记录，按 `agentRunId` 精确过滤，按创建时间倒序分页返回明细；
     * 趋势点按创建时间升序返回最近 12 条，供运行中心展示成本走势。方法不返回 prompt、响应正文、
     * 向量召回正文、工具输出或密钥。</p>
     */
    public ModelRunRequestPage agentRunModelRequests(String projectId, String agentRunId, int page, int size) {
        var normalizedProjectId = requireText(projectId, "项目编号不能为空");
        var normalizedAgentRunId = requireText(agentRunId, "Agent 运行编号不能为空");
        var normalizedPage = Math.max(0, page);
        var normalizedSize = Math.max(1, Math.min(50, size));
        var filtered = projectRequests(normalizedProjectId).stream()
                .filter(request -> normalizedAgentRunId.equals(request.agentRunId()))
                .sorted((left, right) -> {
                    var byTime = right.createdAt().compareTo(left.createdAt());
                    return byTime == 0 ? right.requestId().compareTo(left.requestId()) : byTime;
                })
                .toList();
        var rawFromIndex = (long) normalizedPage * normalizedSize;
        var fromIndex = rawFromIndex >= filtered.size() ? filtered.size() : (int) rawFromIndex;
        var toIndex = Math.min(filtered.size(), fromIndex + normalizedSize);
        var pageItems = filtered.subList(fromIndex, toIndex);
        var trend = filtered.stream()
                .sorted((left, right) -> {
                    var byTime = left.createdAt().compareTo(right.createdAt());
                    return byTime == 0 ? left.requestId().compareTo(right.requestId()) : byTime;
                })
                .skip(Math.max(0, filtered.size() - 12))
                .map(ModelCostTrendPoint::from)
                .toList();
        return new ModelRunRequestPage(
                normalizedProjectId,
                normalizedAgentRunId,
                normalizedPage,
                normalizedSize,
                filtered.size(),
                ModelGatewayMetrics.from(filtered),
                trend,
                pageItems
        );
    }

    public ModelGatewayConfig config(String projectId) {
        return new ModelGatewayConfig(
                providerRegistry.all(),
                effectiveBindings(projectId),
                metrics(projectId),
                recentRequests(projectId)
        );
    }

    public ModelGatewaySummary summary(String projectId) {
        return new ModelGatewaySummary(
                effectiveBindings(projectId),
                metrics(projectId),
                recentRequests(projectId)
        );
    }

    /**
     * 将模型请求按 UTC 自然日聚合为趋势点。
     *
     * <p>使用 UTC 是为了避免多节点部署在不同时区运行时产生日边界不一致。</p>
     */
    private List<ModelCostTrendBucket> dailyTrend(List<ModelRequestRecord> records) {
        return records.stream()
                .collect(Collectors.groupingBy(
                        record -> LocalDate.ofInstant(record.createdAt(), ZoneOffset.UTC).toString(),
                        java.util.TreeMap::new,
                        Collectors.toList()
                ))
                .entrySet()
                .stream()
                .map(entry -> new ModelCostTrendBucket(entry.getKey(), ModelGatewayMetrics.from(entry.getValue())))
                .toList();
    }

    /**
     * 按指定维度生成模型成本分组摘要。
     *
     * <p>分组结果按估算费用降序排列，费用相同时按分组键升序排列，保证前端展示稳定。</p>
     */
    private List<ModelCostBreakdown> breakdown(
            List<ModelRequestRecord> records,
            Function<ModelRequestRecord, String> classifier
    ) {
        return records.stream()
                .collect(Collectors.groupingBy(classifier, Collectors.toList()))
                .entrySet()
                .stream()
                .map(entry -> new ModelCostBreakdown(entry.getKey(), ModelGatewayMetrics.from(entry.getValue())))
                .sorted(Comparator
                        .comparing((ModelCostBreakdown breakdown) -> breakdown.metrics().estimatedCost()).reversed()
                        .thenComparing(ModelCostBreakdown::key))
                .toList();
    }

    private List<RoleModelBinding> effectiveBindings(String projectId) {
        return roleAgentConfigService.configs(projectId).stream()
                .map(config -> effectiveBinding(projectId, config))
                .toList();
    }

    private RoleModelBinding effectiveBinding(ModelRequestCommand command, RoleAgentConfig config) {
        return effectiveBinding(command.projectId(), config);
    }

    private RoleModelBinding effectiveBinding(String projectId, RoleAgentConfig config) {
        var priceBinding = bindingService.require(projectId, config.role());
        return new RoleModelBinding(
                projectId,
                config.role(),
                config.providerId(),
                config.model(),
                priceBinding.currency(),
                priceBinding.cacheHitPerMillion(),
                priceBinding.cacheMissInputPerMillion(),
                priceBinding.outputPerMillion(),
                priceBinding.contextBudgetTokens(),
                config.toolContractVersion()
        );
    }

    private String renderInstruction(RoleAgentConfig config, String instruction) {
        var template = config.userPromptTemplate();
        if (template == null || template.isBlank()) {
            return instruction;
        }
        if (template.contains("{{instruction}}")) {
            return template.replace("{{instruction}}", instruction);
        }
        return template.strip() + "\n\n" + instruction;
    }

    private ModelCompletionClient modelClient(com.matrixcode.modelgateway.domain.ModelProvider provider) {
        return modelClients.stream()
                .filter(client -> client.supports(provider.protocol()))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("模型协议没有可用客户端：" + provider.protocol()));
    }

    private List<ModelRequestRecord> projectRequests(String projectId) {
        if (projectId == null || projectId.isBlank()) {
            throw new IllegalArgumentException("项目编号不能为空");
        }
        return requests.getOrDefault(projectId.trim(), new CopyOnWriteArrayList<>());
    }

    private String requireText(String value, String message) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(message);
        }
        return value.trim();
    }

    private String summarize(String answer) {
        var normalized = answer.replace('\n', ' ').strip();
        return normalized.length() <= 120 ? normalized : normalized.substring(0, 120);
    }

    private void loadInitialRequests() {
        var persisted = activityRepository == null ? Map.<String, List<ModelRequestRecord>>of()
                : activityRepository.loadModelRequests();
        if (!persisted.isEmpty()) {
            loadRequests(persisted);
            return;
        }

        var legacy = stateStore.load().modelRequests();
        loadRequests(legacy);
        if (activityRepository != null && !legacy.isEmpty()) {
            activityRepository.saveModelRequests(legacy);
        }
    }

    private void loadRequests(Map<String, List<ModelRequestRecord>> source) {
        source.forEach((projectId, records) -> requests.put(projectId, new CopyOnWriteArrayList<>(records)));
    }

    private void saveModelRequests() {
        var snapshot = snapshot();
        if (activityRepository != null) {
            activityRepository.saveModelRequests(snapshot);
            return;
        }
        stateStore.saveModelRequests(snapshot);
    }

    private Map<String, List<ModelRequestRecord>> snapshot() {
        var snapshot = new HashMap<String, List<ModelRequestRecord>>();
        requests.forEach((projectId, records) -> snapshot.put(projectId, List.copyOf(records)));
        return snapshot;
    }
}
