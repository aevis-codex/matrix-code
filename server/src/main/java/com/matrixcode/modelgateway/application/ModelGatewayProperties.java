package com.matrixcode.modelgateway.application;

import com.matrixcode.modelgateway.domain.ModelRole;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.Map;

@Component
@ConfigurationProperties(prefix = "matrixcode.model-gateway")
public class ModelGatewayProperties {

    private Map<String, Provider> providers = new LinkedHashMap<>();
    private Map<ModelRole, RoleDefault> roleDefaults = new EnumMap<>(ModelRole.class);
    private Milvus milvus = new Milvus();
    private Embedding embedding = new Embedding();
    private VectorContext vectorContext = new VectorContext();

    public Map<String, Provider> getProviders() {
        return providers;
    }

    public void setProviders(Map<String, Provider> providers) {
        this.providers = providers == null ? new LinkedHashMap<>() : new LinkedHashMap<>(providers);
    }

    public Map<ModelRole, RoleDefault> getRoleDefaults() {
        return roleDefaults;
    }

    public void setRoleDefaults(Map<ModelRole, RoleDefault> roleDefaults) {
        this.roleDefaults = roleDefaults == null ? new EnumMap<>(ModelRole.class) : new EnumMap<>(roleDefaults);
    }

    public Provider provider(String id) {
        return providers.computeIfAbsent(id, ignored -> new Provider());
    }

    public RoleDefault roleDefault(ModelRole role) {
        return roleDefaults.computeIfAbsent(role, ignored -> new RoleDefault());
    }

    public Milvus getMilvus() {
        return milvus;
    }

    public void setMilvus(Milvus milvus) {
        this.milvus = milvus == null ? new Milvus() : milvus;
    }

    public Embedding getEmbedding() {
        return embedding;
    }

    public void setEmbedding(Embedding embedding) {
        this.embedding = embedding == null ? new Embedding() : embedding;
    }

    public VectorContext getVectorContext() {
        return vectorContext;
    }

    public void setVectorContext(VectorContext vectorContext) {
        this.vectorContext = vectorContext == null ? new VectorContext() : vectorContext;
    }

    public static class Provider {
        private String name = "";
        private String baseUrl = "";
        private String apiKeySource = "NONE";
        private boolean enabled;

        public String getName() {
            return name;
        }

        public void setName(String name) {
            this.name = name == null ? "" : name;
        }

        public String getBaseUrl() {
            return baseUrl;
        }

        public void setBaseUrl(String baseUrl) {
            this.baseUrl = baseUrl == null ? "" : baseUrl;
        }

        public String getApiKeySource() {
            return apiKeySource;
        }

        public void setApiKeySource(String apiKeySource) {
            this.apiKeySource = apiKeySource == null || apiKeySource.isBlank() ? "NONE" : apiKeySource;
        }

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }
    }

    public static class RoleDefault {
        private String providerId = "local-deterministic";
        private String model = "";
        private String currency = "CNY";
        private double cacheHitPerMillion;
        private double cacheMissInputPerMillion;
        private double outputPerMillion;
        private int contextBudgetTokens = 32_000;
        private String toolContractVersion = "tools-v1";

        public String getProviderId() {
            return providerId;
        }

        public void setProviderId(String providerId) {
            this.providerId = providerId == null || providerId.isBlank() ? "local-deterministic" : providerId;
        }

        public String getModel() {
            return model;
        }

        public void setModel(String model) {
            this.model = model == null ? "" : model;
        }

        public String getCurrency() {
            return currency;
        }

        public void setCurrency(String currency) {
            this.currency = currency == null || currency.isBlank() ? "CNY" : currency;
        }

        public double getCacheHitPerMillion() {
            return cacheHitPerMillion;
        }

        public void setCacheHitPerMillion(double cacheHitPerMillion) {
            this.cacheHitPerMillion = cacheHitPerMillion;
        }

        public double getCacheMissInputPerMillion() {
            return cacheMissInputPerMillion;
        }

        public void setCacheMissInputPerMillion(double cacheMissInputPerMillion) {
            this.cacheMissInputPerMillion = cacheMissInputPerMillion;
        }

        public double getOutputPerMillion() {
            return outputPerMillion;
        }

        public void setOutputPerMillion(double outputPerMillion) {
            this.outputPerMillion = outputPerMillion;
        }

        public int getContextBudgetTokens() {
            return contextBudgetTokens;
        }

        public void setContextBudgetTokens(int contextBudgetTokens) {
            this.contextBudgetTokens = contextBudgetTokens <= 0 ? 32_000 : contextBudgetTokens;
        }

        public String getToolContractVersion() {
            return toolContractVersion;
        }

        public void setToolContractVersion(String toolContractVersion) {
            this.toolContractVersion = toolContractVersion == null || toolContractVersion.isBlank()
                    ? "tools-v1"
                    : toolContractVersion;
        }
    }

    public static class Milvus {
        private String host = "127.0.0.1";
        private int port = 19530;
        private String database = "matrix_code";
        private String collection = "matrixcode_context_chunks_v2";
        private boolean secure;
        private String username = "";
        private String password = "";
        private String token = "";
        private long loadTimeoutMs = 120_000L;

        public String getHost() {
            return host;
        }

        public void setHost(String host) {
            this.host = host == null || host.isBlank() ? "127.0.0.1" : host;
        }

        public int getPort() {
            return port;
        }

        public void setPort(int port) {
            this.port = port <= 0 ? 19530 : port;
        }

        public String getDatabase() {
            return database;
        }

        public void setDatabase(String database) {
            this.database = database == null || database.isBlank() ? "matrix_code" : database.trim();
        }

        public String getCollection() {
            return collection;
        }

        public void setCollection(String collection) {
            this.collection = collection == null || collection.isBlank()
                    ? "matrixcode_context_chunks_v2"
                    : collection;
        }

        public boolean isSecure() {
            return secure;
        }

        public void setSecure(boolean secure) {
            this.secure = secure;
        }

        public String getUsername() {
            return username;
        }

        public void setUsername(String username) {
            this.username = username == null ? "" : username.trim();
        }

        public String getPassword() {
            return password;
        }

        public void setPassword(String password) {
            this.password = password == null ? "" : password;
        }

        public String getToken() {
            return token;
        }

        public void setToken(String token) {
            this.token = token == null ? "" : token.trim();
        }

        public long getLoadTimeoutMs() {
            return loadTimeoutMs;
        }

        public void setLoadTimeoutMs(long loadTimeoutMs) {
            this.loadTimeoutMs = loadTimeoutMs <= 0 ? 120_000L : loadTimeoutMs;
        }
    }

    public static class Embedding {
        private String providerId = "qwen";
        private String model = "text-embedding-v4";

        public String getProviderId() {
            return providerId;
        }

        public void setProviderId(String providerId) {
            this.providerId = providerId == null || providerId.isBlank() ? "qwen" : providerId;
        }

        public String getModel() {
            return model;
        }

        public void setModel(String model) {
            this.model = model == null || model.isBlank() ? "text-embedding-v4" : model;
        }
    }

    public static class VectorContext {
        private boolean enabled;
        private String store = "memory";
        private int topK = 3;
        private int dimension = 1024;

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public String getStore() {
            return store;
        }

        public void setStore(String store) {
            this.store = store == null || store.isBlank() ? "memory" : store.trim();
        }

        public int getTopK() {
            return topK;
        }

        public void setTopK(int topK) {
            this.topK = topK <= 0 ? 3 : topK;
        }

        public int getDimension() {
            return dimension;
        }

        public void setDimension(int dimension) {
            this.dimension = dimension <= 0 ? 1024 : dimension;
        }
    }
}
