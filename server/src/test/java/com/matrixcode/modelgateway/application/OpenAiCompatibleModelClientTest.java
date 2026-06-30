package com.matrixcode.modelgateway.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.matrixcode.modelgateway.domain.ModelProtocol;
import com.matrixcode.modelgateway.domain.ModelProvider;
import com.matrixcode.modelgateway.domain.ModelRole;
import com.matrixcode.modelgateway.domain.PromptContract;
import com.matrixcode.modelgateway.domain.RoleModelBinding;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.Authenticator;
import java.net.CookieHandler;
import java.net.ProxySelector;
import java.net.URI;
import java.net.http.HttpHeaders;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.Flow;
import java.util.concurrent.TimeUnit;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLParameters;
import javax.net.ssl.SSLSession;

import static org.assertj.core.api.Assertions.assertThat;

class OpenAiCompatibleModelClientTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void 普通兼容模型默认使用低温度() throws Exception {
        var httpClient = new CapturingHttpClient();
        var client = new OpenAiCompatibleModelClient(
                httpClient,
                objectMapper
        );

        client.complete(
                provider("qwen"),
                binding("qwen", "qwen-plus"),
                contract(),
                "只回答 ok",
                "matrixcode_demo_DEVELOPER_qwen_qwen-plus"
        );

        assertThat(objectMapper.readTree(httpClient.requestBody()).path("temperature").asDouble()).isEqualTo(0.2);
        assertThat(objectMapper.readTree(httpClient.requestBody()).has("user_id")).isFalse();
    }

    @Test
    void KimiK系列模型使用供应商要求的温度() throws Exception {
        var httpClient = new CapturingHttpClient();
        var client = new OpenAiCompatibleModelClient(
                httpClient,
                objectMapper
        );

        client.complete(
                provider("kimi"),
                binding("kimi", "kimi-k2.5"),
                contract(),
                "只回答 ok",
                "matrixcode_demo_DEVELOPER_kimi_kimi-k2_5"
        );

        assertThat(objectMapper.readTree(httpClient.requestBody()).path("temperature").asDouble()).isEqualTo(1.0);
    }

    @Test
    void DeepSeek请求携带缓存作用域UserId() throws Exception {
        var httpClient = new CapturingHttpClient();
        var client = new OpenAiCompatibleModelClient(
                httpClient,
                objectMapper
        );

        client.complete(
                provider("deepseek"),
                binding("deepseek", "deepseek-chat"),
                contract(),
                "只回答 ok",
                "matrixcode_demo_DEVELOPER_deepseek_deepseek-chat"
        );

        assertThat(objectMapper.readTree(httpClient.requestBody()).path("user_id").asText())
                .isEqualTo("matrixcode_demo_DEVELOPER_deepseek_deepseek-chat");
    }

    @Test
    void 解析供应商真实缓存用量() {
        var httpClient = new CapturingHttpClient("""
                {
                  "choices": [{"message": {"content": "真实模型回答"}}],
                  "usage": {
                    "prompt_cache_hit_tokens": 128,
                    "prompt_cache_miss_tokens": 32,
                    "completion_tokens": 16
                  }
                }
                """);
        var client = new OpenAiCompatibleModelClient(
                httpClient,
                objectMapper
        );

        var result = client.complete(
                provider("deepseek"),
                binding("deepseek", "deepseek-chat"),
                contract(),
                "只回答 ok",
                "matrixcode_demo_DEVELOPER_deepseek_deepseek-chat"
        );

        assertThat(result.answer()).isEqualTo("真实模型回答");
        assertThat(result.usage()).hasValueSatisfying(usage -> {
            assertThat(usage.cacheHitTokens()).isEqualTo(128);
            assertThat(usage.cacheMissInputTokens()).isEqualTo(32);
            assertThat(usage.outputTokens()).isEqualTo(16);
        });
    }

    private ModelProvider provider(String id) {
        return new ModelProvider(
                id,
                id,
                ModelProtocol.OPENAI_COMPATIBLE,
                "https://example.invalid",
                "PATH",
                true
        );
    }

    private RoleModelBinding binding(String providerId, String model) {
        return new RoleModelBinding(
                "demo",
                ModelRole.DEVELOPER,
                providerId,
                model,
                "CNY",
                0.0,
                0.0,
                0.0,
                32_000,
                "tools-v1"
        );
    }

    private PromptContract contract() {
        return new PromptContract(
                ModelRole.DEVELOPER,
                "test-model",
                "tools-v1",
                "你是测试智能体",
                "stable-hash",
                1
        );
    }

    private static class CapturingHttpClient extends HttpClient {
        private String requestBody = "";
        private final String responseBody;

        CapturingHttpClient() {
            this("""
                    {"choices":[{"message":{"content":"ok"}}]}
                    """);
        }

        CapturingHttpClient(String responseBody) {
            this.responseBody = responseBody;
        }

        String requestBody() {
            return requestBody;
        }

        @Override
        public Optional<CookieHandler> cookieHandler() {
            return Optional.empty();
        }

        @Override
        public Optional<Duration> connectTimeout() {
            return Optional.empty();
        }

        @Override
        public Redirect followRedirects() {
            return Redirect.NEVER;
        }

        @Override
        public Optional<ProxySelector> proxy() {
            return Optional.empty();
        }

        @Override
        public SSLContext sslContext() {
            try {
                return SSLContext.getDefault();
            } catch (Exception exception) {
                throw new IllegalStateException(exception);
            }
        }

        @Override
        public SSLParameters sslParameters() {
            return new SSLParameters();
        }

        @Override
        public Optional<Authenticator> authenticator() {
            return Optional.empty();
        }

        @Override
        public Version version() {
            return Version.HTTP_1_1;
        }

        @Override
        public Optional<Executor> executor() {
            return Optional.empty();
        }

        @Override
        @SuppressWarnings("unchecked")
        public <T> HttpResponse<T> send(HttpRequest request, HttpResponse.BodyHandler<T> responseBodyHandler)
                throws IOException {
            requestBody = bodyPublisherToString(request.bodyPublisher().orElseThrow());
            return (HttpResponse<T>) new StringResponse(request, responseBody);
        }

        @Override
        public <T> CompletableFuture<HttpResponse<T>> sendAsync(
                HttpRequest request,
                HttpResponse.BodyHandler<T> responseBodyHandler
        ) {
            throw new UnsupportedOperationException();
        }

        @Override
        public <T> CompletableFuture<HttpResponse<T>> sendAsync(
                HttpRequest request,
                HttpResponse.BodyHandler<T> responseBodyHandler,
                HttpResponse.PushPromiseHandler<T> pushPromiseHandler
        ) {
            throw new UnsupportedOperationException();
        }

        private String bodyPublisherToString(HttpRequest.BodyPublisher publisher) throws IOException {
            var chunks = new java.util.ArrayList<byte[]>();
            var latch = new CountDownLatch(1);
            var failure = new AtomicFailure();
            publisher.subscribe(new Flow.Subscriber<>() {
                @Override
                public void onSubscribe(Flow.Subscription subscription) {
                    subscription.request(Long.MAX_VALUE);
                }

                @Override
                public void onNext(java.nio.ByteBuffer item) {
                    var bytes = new byte[item.remaining()];
                    item.get(bytes);
                    chunks.add(bytes);
                }

                @Override
                public void onError(Throwable throwable) {
                    failure.error = throwable;
                    latch.countDown();
                }

                @Override
                public void onComplete() {
                    latch.countDown();
                }
            });
            try {
                if (!latch.await(5, TimeUnit.SECONDS)) {
                    throw new IOException("读取请求体超时");
                }
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                throw new IOException("读取请求体被中断", exception);
            }
            if (failure.error != null) {
                throw new IOException("读取请求体失败", failure.error);
            }
            var total = chunks.stream().mapToInt(bytes -> bytes.length).sum();
            var merged = new byte[total];
            var offset = 0;
            for (var chunk : chunks) {
                System.arraycopy(chunk, 0, merged, offset, chunk.length);
                offset += chunk.length;
            }
            return new String(merged, StandardCharsets.UTF_8);
        }
    }

    private static class AtomicFailure {
        private Throwable error;
    }

    private record StringResponse(HttpRequest request, String responseBody) implements HttpResponse<String> {
        @Override
        public int statusCode() {
            return 200;
        }

        @Override
        public Optional<HttpResponse<String>> previousResponse() {
            return Optional.empty();
        }

        @Override
        public HttpHeaders headers() {
            return HttpHeaders.of(java.util.Map.of(), (ignoredName, ignoredValue) -> true);
        }

        @Override
        public String body() {
            return responseBody;
        }

        @Override
        public Optional<SSLSession> sslSession() {
            return Optional.empty();
        }

        @Override
        public URI uri() {
            return request.uri();
        }

        @Override
        public HttpClient.Version version() {
            return HttpClient.Version.HTTP_1_1;
        }
    }
}
