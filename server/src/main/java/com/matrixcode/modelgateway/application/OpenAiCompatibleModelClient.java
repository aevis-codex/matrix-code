package com.matrixcode.modelgateway.application;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.matrixcode.modelgateway.domain.ModelCompletionResult;
import com.matrixcode.modelgateway.domain.ModelProtocol;
import com.matrixcode.modelgateway.domain.ModelProvider;
import com.matrixcode.modelgateway.domain.ModelRequestRuntimeOptions;
import com.matrixcode.modelgateway.domain.ProviderTokenUsage;
import com.matrixcode.modelgateway.domain.PromptContract;
import com.matrixcode.modelgateway.domain.RoleModelBinding;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Component
public class OpenAiCompatibleModelClient implements ModelCompletionClient {

    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(60);

    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;

    @Autowired
    public OpenAiCompatibleModelClient(ObjectMapper objectMapper) {
        this(HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build(), objectMapper);
    }

    OpenAiCompatibleModelClient(HttpClient httpClient, ObjectMapper objectMapper) {
        this.httpClient = httpClient;
        this.objectMapper = objectMapper;
    }

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
        return complete(provider, binding, contract, instruction, cacheScopeId, ModelRequestRuntimeOptions.defaults());
    }

    @Override
    public ModelCompletionResult complete(
            ModelProvider provider,
            RoleModelBinding binding,
            PromptContract contract,
            String instruction,
            String cacheScopeId,
            ModelRequestRuntimeOptions runtimeOptions
    ) {
        var apiKey = apiKey(provider);
        var endpoint = chatCompletionsEndpoint(provider.baseUrl());
        var requestBody = requestBody(provider, binding, contract, instruction, cacheScopeId, runtimeOptions);
        try {
            var request = HttpRequest.newBuilder(URI.create(endpoint))
                    .timeout(REQUEST_TIMEOUT)
                    .header("Authorization", "Bearer " + apiKey)
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(requestBody)))
                    .build();
            var response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw new IllegalStateException("模型供应商请求失败：" + provider.id() + " HTTP " + response.statusCode());
            }
            return completionFrom(response.body(), provider.id());
        } catch (IOException exception) {
            throw new IllegalStateException("模型供应商请求失败：" + provider.id(), exception);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("模型供应商请求被中断：" + provider.id(), exception);
        }
    }

    /**
     * 构建 OpenAI 兼容请求体。
     *
     * <p>DeepSeek 支持按 `user_id` 隔离上下文缓存；这里只对 DeepSeek 写入该字段，避免其他
     * 兼容供应商因未知字段拒绝请求。</p>
     */
    private Map<String, Object> requestBody(
            ModelProvider provider,
            RoleModelBinding binding,
            PromptContract contract,
            String instruction,
            String cacheScopeId,
            ModelRequestRuntimeOptions runtimeOptions
    ) {
        var effectiveRuntimeOptions = runtimeOptions == null
                ? ModelRequestRuntimeOptions.defaults()
                : runtimeOptions;
        var requestBody = new LinkedHashMap<String, Object>();
        requestBody.put("model", binding.model());
        requestBody.put("messages", List.of(
                Map.of("role", "system", "content", contract.systemPrefix()),
                Map.of("role", "user", "content", instruction)
        ));
        requestBody.put("temperature", temperature(provider, binding));
        if ("deepseek".equalsIgnoreCase(provider.id()) && cacheScopeId != null && !cacheScopeId.isBlank()) {
            requestBody.put("user_id", cacheScopeId.trim());
        }
        if (supportsReasoningEffort(provider, binding)) {
            effectiveRuntimeOptions.explicitReasoningEffort().ifPresent(effort -> requestBody.put("reasoning_effort", effort));
        }
        return requestBody;
    }

    /**
     * 判断当前供应商或模型是否支持显式推理力度参数。
     * 作用域：OpenAI 兼容模型客户端；场景：只对 DeepSeek 或 reasoning 模型写入 `reasoning_effort`，避免普通模型拒绝未知字段。
     */
    private boolean supportsReasoningEffort(ModelProvider provider, RoleModelBinding binding) {
        var providerId = provider.id() == null ? "" : provider.id().toLowerCase(java.util.Locale.ROOT);
        var model = binding.model().toLowerCase(java.util.Locale.ROOT);
        return "deepseek".equals(providerId) || model.contains("reason");
    }

    private String apiKey(ModelProvider provider) {
        var source = provider.apiKeySource();
        if (source == null || source.isBlank() || "NONE".equalsIgnoreCase(source)) {
            throw new IllegalStateException("模型供应商未配置 API Key 环境变量：" + provider.id());
        }
        var apiKey = System.getenv(source);
        if (apiKey == null || apiKey.isBlank()) {
            throw new IllegalStateException("模型供应商 API Key 环境变量未设置：" + source);
        }
        return apiKey.strip();
    }

    private String chatCompletionsEndpoint(String baseUrl) {
        var normalized = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        return normalized + "/chat/completions";
    }

    private double temperature(ModelProvider provider, RoleModelBinding binding) {
        if ("kimi".equalsIgnoreCase(provider.id()) && binding.model().startsWith("kimi-k")) {
            return 1.0;
        }
        return 0.2;
    }

    /**
     * 从供应商响应中解析回答和真实缓存 token 字段。
     *
     * <p>如果响应没有 `prompt_cache_hit_tokens` 或 `prompt_cache_miss_tokens`，返回结果中不携带
     * 供应商 usage，服务层会走本地估算，保持对通用 OpenAI 兼容供应商的兼容。</p>
     */
    private ModelCompletionResult completionFrom(String responseBody, String providerId) throws IOException {
        JsonNode root = objectMapper.readTree(responseBody);
        var content = root.path("choices").path(0).path("message").path("content").asText("");
        if (content.isBlank()) {
            throw new IllegalStateException("模型供应商返回内容为空：" + providerId);
        }
        var usage = root.path("usage");
        if (usage.has("prompt_cache_hit_tokens") || usage.has("prompt_cache_miss_tokens")) {
            return ModelCompletionResult.withProviderUsage(
                    content,
                    new ProviderTokenUsage(
                            usage.path("prompt_cache_hit_tokens").asLong(0L),
                            usage.path("prompt_cache_miss_tokens").asLong(0L),
                            usage.path("completion_tokens").asLong(0L)
                    )
            );
        }
        return ModelCompletionResult.withoutProviderUsage(content);
    }
}
