package com.matrixcode.modelgateway.application;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.matrixcode.modelgateway.domain.ModelProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Component
public class OpenAiCompatibleEmbeddingClient implements EmbeddingClient {

    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(60);

    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;
    private final ModelProviderRegistry providerRegistry;
    private final ModelGatewayProperties properties;

    @Autowired
    public OpenAiCompatibleEmbeddingClient(
            ObjectMapper objectMapper,
            ModelProviderRegistry providerRegistry,
            ModelGatewayProperties properties
    ) {
        this(
                HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build(),
                objectMapper,
                providerRegistry,
                properties
        );
    }

    OpenAiCompatibleEmbeddingClient(
            HttpClient httpClient,
            ObjectMapper objectMapper,
            ModelProviderRegistry providerRegistry,
            ModelGatewayProperties properties
    ) {
        this.httpClient = httpClient;
        this.objectMapper = objectMapper;
        this.providerRegistry = providerRegistry;
        this.properties = properties;
    }

    @Override
    public List<Float> embed(String input) {
        var embedding = properties.getEmbedding();
        var provider = providerRegistry.require(embedding.getProviderId());
        if (!provider.enabled()) {
            throw new IllegalStateException("Embedding 供应商未启用：" + provider.id());
        }
        var requestBody = new LinkedHashMap<String, Object>();
        requestBody.put("model", embedding.getModel());
        requestBody.put("input", input);
        requestBody.put("dimensions", properties.getVectorContext().getDimension());

        try {
            var request = HttpRequest.newBuilder(URI.create(embeddingsEndpoint(provider.baseUrl())))
                    .timeout(REQUEST_TIMEOUT)
                    .header("Authorization", "Bearer " + apiKey(provider))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(requestBody)))
                    .build();
            var response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw new IllegalStateException("Embedding 供应商请求失败：" + provider.id() + " HTTP " + response.statusCode());
            }
            return embeddingFrom(response.body(), provider.id());
        } catch (IOException exception) {
            throw new IllegalStateException("Embedding 供应商请求失败：" + provider.id(), exception);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Embedding 供应商请求被中断：" + provider.id(), exception);
        }
    }

    private String apiKey(ModelProvider provider) {
        var source = provider.apiKeySource();
        if (source == null || source.isBlank() || "NONE".equalsIgnoreCase(source)) {
            throw new IllegalStateException("Embedding 供应商未配置 API Key 环境变量：" + provider.id());
        }
        var apiKey = System.getenv(source);
        if (apiKey == null || apiKey.isBlank()) {
            throw new IllegalStateException("Embedding 供应商 API Key 环境变量未设置：" + source);
        }
        return apiKey.strip();
    }

    private String embeddingsEndpoint(String baseUrl) {
        var normalized = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        return normalized + "/embeddings";
    }

    private List<Float> embeddingFrom(String responseBody, String providerId) throws IOException {
        JsonNode root = objectMapper.readTree(responseBody);
        var values = root.path("data").path(0).path("embedding");
        if (!values.isArray() || values.isEmpty()) {
            throw new IllegalStateException("Embedding 供应商返回向量为空：" + providerId);
        }
        var embedding = new ArrayList<Float>();
        values.forEach(value -> embedding.add((float) value.asDouble()));
        return List.copyOf(embedding);
    }
}
