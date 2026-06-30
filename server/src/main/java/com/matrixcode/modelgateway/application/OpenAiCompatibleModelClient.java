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
import java.util.function.Consumer;
import java.util.stream.Stream;

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
        var requestBody = requestBody(provider, binding, contract, instruction, cacheScopeId, runtimeOptions, false);
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
     * 执行 OpenAI-compatible 流式补全请求。
     *
     * <p>作用域：真实模型供应商调用；场景：Composer 需要边生成边展示时读取 SSE `delta.content`，
     * 并在结束后返回完整回答和供应商 usage，用于后续成本与缓存指标记录。</p>
     */
    @Override
    public ModelCompletionResult stream(
            ModelProvider provider,
            RoleModelBinding binding,
            PromptContract contract,
            String instruction,
            String cacheScopeId,
            ModelRequestRuntimeOptions runtimeOptions,
            Consumer<String> deltaConsumer
    ) {
        var apiKey = apiKey(provider);
        var endpoint = chatCompletionsEndpoint(provider.baseUrl());
        var requestBody = requestBody(provider, binding, contract, instruction, cacheScopeId, runtimeOptions, true);
        try {
            var request = HttpRequest.newBuilder(URI.create(endpoint))
                    .timeout(REQUEST_TIMEOUT)
                    .header("Authorization", "Bearer " + apiKey)
                    .header("Content-Type", "application/json")
                    .header("Accept", "text/event-stream")
                    .POST(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(requestBody)))
                    .build();
            var response = httpClient.send(request, HttpResponse.BodyHandlers.ofLines());
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw new IllegalStateException("模型供应商请求失败：" + provider.id() + " HTTP " + response.statusCode());
            }
            return completionFromStream(response.body(), provider.id(), deltaConsumer);
        } catch (IOException exception) {
            throw new IllegalStateException("模型供应商流式请求失败：" + provider.id(), exception);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("模型供应商流式请求被中断：" + provider.id(), exception);
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
            ModelRequestRuntimeOptions runtimeOptions,
            boolean stream
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
        if (stream) {
            requestBody.put("stream", true);
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

    /**
     * 解析供应商 SSE 响应。
     *
     * <p>作用域：OpenAI-compatible 客户端内部；场景：兼容 `data: {...}` 和 `[DONE]` 事件，
     * 只把模型正文片段回调给前端，不把 usage 或其他控制字段当成正文输出。</p>
     */
    private ModelCompletionResult completionFromStream(
            Stream<String> responseLines,
            String providerId,
            Consumer<String> deltaConsumer
    ) throws IOException {
        var answer = new StringBuilder();
        var usageHolder = new ProviderUsageHolder();
        try (responseLines) {
            var eventData = new StringBuilder();
            var iterator = responseLines.iterator();
            while (iterator.hasNext()) {
                var line = iterator.next();
                if (line == null) {
                    continue;
                }
                if (line.isBlank()) {
                    parseStreamEvent(eventData.toString(), answer, usageHolder, deltaConsumer);
                    eventData.setLength(0);
                    continue;
                }
                if (line.startsWith("data:")) {
                    if (!eventData.isEmpty()) {
                        eventData.append('\n');
                    }
                    eventData.append(line.substring("data:".length()).stripLeading());
                }
            }
            if (!eventData.isEmpty()) {
                parseStreamEvent(eventData.toString(), answer, usageHolder, deltaConsumer);
            }
        }
        if (answer.isEmpty()) {
            throw new IllegalStateException("模型供应商返回内容为空：" + providerId);
        }
        if (usageHolder.usage != null && usageHolder.usage.hasPromptCacheTokens()) {
            return ModelCompletionResult.withProviderUsage(answer.toString(), usageHolder.usage);
        }
        return ModelCompletionResult.withoutProviderUsage(answer.toString());
    }

    /**
     * 解析单个 SSE data 事件并抽取正文片段和 usage。
     */
    private void parseStreamEvent(
            String data,
            StringBuilder answer,
            ProviderUsageHolder usageHolder,
            Consumer<String> deltaConsumer
    ) throws IOException {
        var normalized = data == null ? "" : data.strip();
        if (normalized.isBlank() || "[DONE]".equals(normalized)) {
            return;
        }
        var root = objectMapper.readTree(normalized);
        var choice = root.path("choices").path(0);
        var delta = choice.path("delta").path("content").asText("");
        if (delta.isEmpty()) {
            delta = choice.path("message").path("content").asText("");
        }
        if (!delta.isEmpty()) {
            answer.append(delta);
            if (deltaConsumer != null) {
                deltaConsumer.accept(delta);
            }
        }
        var usage = root.path("usage");
        if (usage.has("prompt_cache_hit_tokens") || usage.has("prompt_cache_miss_tokens")) {
            usageHolder.usage = new ProviderTokenUsage(
                    usage.path("prompt_cache_hit_tokens").asLong(0L),
                    usage.path("prompt_cache_miss_tokens").asLong(0L),
                    usage.path("completion_tokens").asLong(0L)
            );
        }
    }

    private static final class ProviderUsageHolder {
        private ProviderTokenUsage usage;
    }
}
