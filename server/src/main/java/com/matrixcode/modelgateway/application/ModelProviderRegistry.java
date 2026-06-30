package com.matrixcode.modelgateway.application;

import com.matrixcode.modelgateway.domain.ModelProtocol;
import com.matrixcode.modelgateway.domain.ModelProvider;
import org.springframework.beans.factory.annotation.Autowired;
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

    @Autowired
    public ModelProviderRegistry(ModelGatewayProperties properties) {
        this();
        properties.getProviders().forEach((id, provider) -> upsert(new ModelProvider(
                id,
                provider.getName().isBlank() ? id : provider.getName(),
                ModelProtocol.OPENAI_COMPATIBLE,
                provider.getBaseUrl(),
                provider.getApiKeySource(),
                provider.isEnabled()
        )));
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
