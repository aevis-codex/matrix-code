package com.matrixcode.roleagent.application;

import com.matrixcode.modelgateway.domain.ModelRole;
import com.matrixcode.modelgateway.application.ModelGatewayProperties;
import com.matrixcode.roleagent.domain.RoleAgentConfig;
import com.matrixcode.workbench.application.WorkbenchStateStore;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class RoleAgentConfigService {

    private static final String PROVIDER_ID = "local-deterministic";
    private static final String TOOL_CONTRACT_VERSION = "tools-v1";
    private static final String FONT_FAMILY = "Inter";
    private static final int FONT_SIZE = 14;

    private final WorkbenchStateStore stateStore;
    private final ModelGatewayProperties modelGatewayProperties;
    private final RoleAgentConfigRepository repository;
    private final Clock clock;
    private final Map<String, RoleAgentConfig> configs = new ConcurrentHashMap<>();
    private volatile boolean loaded;

    @Autowired
    public RoleAgentConfigService(
            WorkbenchStateStore stateStore,
            ModelGatewayProperties modelGatewayProperties,
            ObjectProvider<RoleAgentConfigRepository> repository
    ) {
        this(stateStore, modelGatewayProperties, repository.getIfAvailable(), Clock.systemUTC());
    }

    public RoleAgentConfigService(WorkbenchStateStore stateStore) {
        this(stateStore, new ModelGatewayProperties(), null, Clock.systemUTC());
    }

    public RoleAgentConfigService(WorkbenchStateStore stateStore, Clock clock) {
        this(stateStore, new ModelGatewayProperties(), null, clock);
    }

    public RoleAgentConfigService(
            WorkbenchStateStore stateStore,
            ModelGatewayProperties modelGatewayProperties,
            Clock clock
    ) {
        this(stateStore, modelGatewayProperties, null, clock);
    }

    public RoleAgentConfigService(
            WorkbenchStateStore stateStore,
            ModelGatewayProperties modelGatewayProperties,
            RoleAgentConfigRepository repository,
            Clock clock
    ) {
        this.stateStore = stateStore;
        this.modelGatewayProperties = modelGatewayProperties;
        this.repository = repository;
        this.clock = clock;
    }

    public List<RoleAgentConfig> configs(String projectId) {
        ensureLoaded();
        return Arrays.stream(ModelRole.values())
                .map(role -> require(projectId, role))
                .sorted(Comparator.comparingInt(RoleAgentConfig::sortOrder))
                .toList();
    }

    public RoleAgentConfig require(String projectId, ModelRole role) {
        ensureLoaded();
        return configs.computeIfAbsent(key(projectId, role), ignored -> defaultConfig(projectId, role));
    }

    public RoleAgentConfig update(String projectId, ModelRole role, RoleAgentConfigCommand command) {
        if (command == null) {
            throw new IllegalArgumentException("角色智能体配置不能为空");
        }
        ensureLoaded();
        var updated = new RoleAgentConfig(
                normalizeProjectId(projectId),
                role,
                command.displayName(),
                command.agentKind(),
                command.providerId(),
                command.model(),
                command.toolContractVersion(),
                command.systemPrompt(),
                command.userPromptTemplate(),
                command.themeColor(),
                command.fontFamily(),
                command.fontSize(),
                command.sortOrder(),
                command.enabled(),
                command.cachePolicyId(),
                command.volatileSuffixStrategy(),
                command.cacheScopeStrategy(),
                clock.instant()
        );
        configs.put(key(updated.projectId(), role), updated);
        saveConfigs();
        return updated;
    }

    private void ensureLoaded() {
        if (loaded) {
            return;
        }
        synchronized (this) {
            if (loaded) {
                return;
            }
            loadPersistedConfigs().forEach(config -> configs.put(key(config.projectId(), config.role()), config));
            loaded = true;
        }
    }

    private List<RoleAgentConfig> loadPersistedConfigs() {
        var restored = new ArrayList<RoleAgentConfig>();
        if (repository != null) {
            restored.addAll(repository.load());
            if (!restored.isEmpty()) {
                return restored;
            }
        }
        restored.addAll(stateStore.load().roleAgentConfigs());
        if (repository != null && !restored.isEmpty()) {
            repository.save(restored);
        }
        return restored;
    }

    private void saveConfigs() {
        var values = List.copyOf(configs.values());
        if (repository != null) {
            repository.save(values);
        } else {
            stateStore.saveRoleAgentConfigs(values);
        }
    }

    private RoleAgentConfig defaultConfig(String projectId, ModelRole role) {
        var roleDefault = modelGatewayProperties.getRoleDefaults().get(role);
        var providerId = roleDefault == null ? PROVIDER_ID : roleDefault.getProviderId();
        var model = roleDefault == null || roleDefault.getModel().isBlank() ? localModel(role) : roleDefault.getModel();
        var toolContractVersion = roleDefault == null ? TOOL_CONTRACT_VERSION : roleDefault.getToolContractVersion();
        return switch (role) {
            case PRODUCT -> new RoleAgentConfig(
                    normalizeProjectId(projectId),
                    role,
                    "产品智能体",
                    "product",
                    providerId,
                    model,
                    toolContractVersion,
                    "你是 MatrixCode 的产品智能体，负责梳理需求、拆解验收标准、发现范围偏差，并把多人协作目标保持在可交付状态。",
                    "请基于当前项目上下文处理产品任务：{{instruction}}",
                    "#7c3aed",
                    FONT_FAMILY,
                    FONT_SIZE,
                    1,
                    true,
                    RoleAgentConfig.DEFAULT_CACHE_POLICY_ID,
                    RoleAgentConfig.DEFAULT_VOLATILE_SUFFIX_STRATEGY,
                    clock.instant()
            );
            case DEVELOPER -> new RoleAgentConfig(
                    normalizeProjectId(projectId),
                    role,
                    "开发智能体",
                    "coding",
                    providerId,
                    model,
                    toolContractVersion,
                    "你是 MatrixCode 的开发编码智能体，负责先阅读代码和计划，再执行代码修改、测试验证、回归检查和文档同步。",
                    "请基于以下开发任务输出计划并执行：{{instruction}}",
                    "#2563eb",
                    FONT_FAMILY,
                    FONT_SIZE,
                    2,
                    true,
                    RoleAgentConfig.DEFAULT_CACHE_POLICY_ID,
                    RoleAgentConfig.DEFAULT_VOLATILE_SUFFIX_STRATEGY,
                    clock.instant()
            );
            case TESTER -> new RoleAgentConfig(
                    normalizeProjectId(projectId),
                    role,
                    "测试智能体",
                    "testing",
                    providerId,
                    model,
                    toolContractVersion,
                    "你是 MatrixCode 的测试智能体，负责验证需求、复现缺陷、设计回归用例，并给出可执行的质量结论。",
                    "请基于以下测试任务执行验证：{{instruction}}",
                    "#dc2626",
                    FONT_FAMILY,
                    FONT_SIZE,
                    3,
                    true,
                    RoleAgentConfig.DEFAULT_CACHE_POLICY_ID,
                    RoleAgentConfig.DEFAULT_VOLATILE_SUFFIX_STRATEGY,
                    clock.instant()
            );
            case OPERATIONS -> new RoleAgentConfig(
                    normalizeProjectId(projectId),
                    role,
                    "运维智能体",
                    "operations",
                    providerId,
                    model,
                    toolContractVersion,
                    "你是 MatrixCode 的运维智能体，负责运行环境、部署目标、健康检查、配置核查和上线风险提示。",
                    "请基于以下运维任务执行检查：{{instruction}}",
                    "#16a34a",
                    FONT_FAMILY,
                    FONT_SIZE,
                    4,
                    true,
                    RoleAgentConfig.DEFAULT_CACHE_POLICY_ID,
                    RoleAgentConfig.DEFAULT_VOLATILE_SUFFIX_STRATEGY,
                    clock.instant()
            );
        };
    }

    private String localModel(ModelRole role) {
        return switch (role) {
            case PRODUCT -> "matrixcode-local-product";
            case DEVELOPER -> "matrixcode-local-developer";
            case TESTER -> "matrixcode-local-tester";
            case OPERATIONS -> "matrixcode-local-operations";
        };
    }

    private String key(String projectId, ModelRole role) {
        if (role == null) {
            throw new IllegalArgumentException("模型角色不能为空");
        }
        return normalizeProjectId(projectId) + "::" + role.name();
    }

    private String normalizeProjectId(String projectId) {
        if (projectId == null || projectId.isBlank()) {
            throw new IllegalArgumentException("项目编号不能为空");
        }
        return projectId.trim();
    }
}
