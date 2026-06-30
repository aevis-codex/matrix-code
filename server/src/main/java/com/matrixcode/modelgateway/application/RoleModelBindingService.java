package com.matrixcode.modelgateway.application;

import com.matrixcode.modelgateway.domain.ModelRole;
import com.matrixcode.modelgateway.domain.RoleModelBinding;
import com.matrixcode.workbench.application.InMemoryWorkbenchStateStore;
import com.matrixcode.workbench.application.WorkbenchStateStore;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class RoleModelBindingService {

    private static final int DEFAULT_CONTEXT_BUDGET = 32_000;
    private final ModelProviderRegistry providerRegistry;
    private final ModelGatewayProperties properties;
    private final Map<String, RoleModelBinding> bindings = new ConcurrentHashMap<>();
    private final WorkbenchStateStore stateStore;
    private final RoleModelBindingRepository repository;

    public RoleModelBindingService(ModelProviderRegistry providerRegistry) {
        this(providerRegistry, new InMemoryWorkbenchStateStore(), new ModelGatewayProperties(),
                (RoleModelBindingRepository) null);
    }

    public RoleModelBindingService(ModelProviderRegistry providerRegistry, WorkbenchStateStore stateStore) {
        this(providerRegistry, stateStore, new ModelGatewayProperties(), (RoleModelBindingRepository) null);
    }

    public RoleModelBindingService(
            ModelProviderRegistry providerRegistry,
            WorkbenchStateStore stateStore,
            ModelGatewayProperties properties
    ) {
        this(providerRegistry, stateStore, properties, (RoleModelBindingRepository) null);
    }

    @Autowired
    public RoleModelBindingService(
            ModelProviderRegistry providerRegistry,
            WorkbenchStateStore stateStore,
            ModelGatewayProperties properties,
            ObjectProvider<RoleModelBindingRepository> repository
    ) {
        this(providerRegistry, stateStore, properties, repository.getIfAvailable());
    }

    public RoleModelBindingService(
            ModelProviderRegistry providerRegistry,
            WorkbenchStateStore stateStore,
            ModelGatewayProperties properties,
            RoleModelBindingRepository repository
    ) {
        this.providerRegistry = providerRegistry;
        this.stateStore = stateStore;
        this.properties = properties;
        this.repository = repository;
        loadInitialBindings();
    }

    public List<RoleModelBinding> bindings(String projectId) {
        return Arrays.stream(ModelRole.values())
                .map(role -> require(projectId, role))
                .toList();
    }

    public RoleModelBinding require(String projectId, ModelRole role) {
        return bindings.computeIfAbsent(key(projectId, role), ignored -> defaultBinding(projectId, role));
    }

    public RoleModelBinding bind(
            String projectId,
            ModelRole role,
            String providerId,
            String model,
            String currency,
            double cacheHitPerMillion,
            double cacheMissInputPerMillion,
            double outputPerMillion,
            int contextBudgetTokens,
            String toolContractVersion
    ) {
        var provider = providerRegistry.require(providerId);
        if (!provider.enabled()) {
            throw new IllegalArgumentException("模型供应商未启用：" + providerId);
        }
        var binding = new RoleModelBinding(
                projectId,
                role,
                providerId,
                model,
                currency,
                cacheHitPerMillion,
                cacheMissInputPerMillion,
                outputPerMillion,
                contextBudgetTokens,
                toolContractVersion
        );
        bindings.put(key(projectId, role), binding);
        saveBindings();
        return binding;
    }

    /**
     * 加载角色模型绑定。
     *
     * <p>JDBC 模式优先读取正式表；正式表为空时读取旧 `workbench-state.modelBindings` 并回填，
     * 用于兼容早期只保存在工作台快照中的模型选择。</p>
     */
    private void loadInitialBindings() {
        if (repository != null) {
            var persisted = repository.load();
            if (!persisted.isEmpty()) {
                persisted.forEach(binding -> bindings.put(key(binding.projectId(), binding.role()), binding));
                return;
            }
        }
        var legacy = stateStore.load().modelBindings();
        legacy.forEach(binding -> bindings.put(key(binding.projectId(), binding.role()), binding));
        if (repository != null && !legacy.isEmpty()) {
            repository.save(legacy);
        }
    }

    private void saveBindings() {
        var values = List.copyOf(bindings.values());
        if (repository != null) {
            repository.save(values);
            return;
        }
        stateStore.saveModelBindings(values);
    }

    private RoleModelBinding defaultBinding(String projectId, ModelRole role) {
        var roleDefault = properties.getRoleDefaults().get(role);
        var providerId = roleDefault == null ? "local-deterministic" : roleDefault.getProviderId();
        var model = roleDefault == null || roleDefault.getModel().isBlank() ? switch (role) {
            case PRODUCT -> "matrixcode-local-product";
            case DEVELOPER -> "matrixcode-local-developer";
            case TESTER -> "matrixcode-local-tester";
            case OPERATIONS -> "matrixcode-local-operations";
        } : roleDefault.getModel();
        return new RoleModelBinding(
                projectId,
                role,
                providerId,
                model,
                roleDefault == null ? "CNY" : roleDefault.getCurrency(),
                roleDefault == null ? 0.0 : roleDefault.getCacheHitPerMillion(),
                roleDefault == null ? 0.0 : roleDefault.getCacheMissInputPerMillion(),
                roleDefault == null ? 0.0 : roleDefault.getOutputPerMillion(),
                roleDefault == null ? DEFAULT_CONTEXT_BUDGET : roleDefault.getContextBudgetTokens(),
                roleDefault == null ? "tools-v1" : roleDefault.getToolContractVersion()
        );
    }

    private String key(String projectId, ModelRole role) {
        if (projectId == null || projectId.isBlank()) {
            throw new IllegalArgumentException("项目编号不能为空");
        }
        if (role == null) {
            throw new IllegalArgumentException("模型角色不能为空");
        }
        return projectId.trim() + ":" + role.name();
    }
}
