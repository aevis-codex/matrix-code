package com.matrixcode.deployment;

import com.matrixcode.deployment.application.HttpDeploymentHealthClient;
import com.matrixcode.deployment.domain.DeploymentHealthStatus;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

class HttpDeploymentHealthClientTest {

    private HttpServer server;

    @AfterEach
    void tearDown() {
        if (server != null) {
            server.stop(0);
        }
    }

    @Test
    void 二三开头状态码视为健康() throws Exception {
        var uri = startServer(204);
        var client = new HttpDeploymentHealthClient();

        var probe = client.check(uri);

        assertThat(probe.status()).isEqualTo(DeploymentHealthStatus.HEALTHY);
        assertThat(probe.httpStatus()).isEqualTo(204);
        assertThat(probe.summary()).isEqualTo("HTTP 204");
        assertThat(probe.durationMillis()).isGreaterThanOrEqualTo(0);
    }

    @Test
    void 四五开头状态码视为非健康() throws Exception {
        var uri = startServer(503);
        var client = new HttpDeploymentHealthClient();

        var probe = client.check(uri);

        assertThat(probe.status()).isEqualTo(DeploymentHealthStatus.UNHEALTHY);
        assertThat(probe.httpStatus()).isEqualTo(503);
        assertThat(probe.summary()).isEqualTo("HTTP 503");
        assertThat(probe.durationMillis()).isGreaterThanOrEqualTo(0);
    }

    @Test
    void 连接失败视为不可达() {
        var client = new HttpDeploymentHealthClient();

        var probe = client.check(URI.create("http://127.0.0.1:1/health"));

        assertThat(probe.status()).isEqualTo(DeploymentHealthStatus.UNREACHABLE);
        assertThat(probe.httpStatus()).isNull();
        assertThat(probe.summary()).isEqualTo("健康检查地址不可达");
        assertThat(probe.durationMillis()).isGreaterThanOrEqualTo(0);
    }

    @Test
    void 请求被中断时会恢复线程中断标记并返回不可达() {
        var client = new HttpDeploymentHealthClient();
        Thread.currentThread().interrupt();

        try {
            var probe = client.check(URI.create("http://127.0.0.1:1/health"));

            assertThat(probe.status()).isEqualTo(DeploymentHealthStatus.UNREACHABLE);
            assertThat(probe.httpStatus()).isNull();
            assertThat(probe.summary()).isEqualTo("健康检查地址不可达");
            assertThat(probe.durationMillis()).isGreaterThanOrEqualTo(0);
            assertThat(Thread.currentThread().isInterrupted()).isTrue();
        } finally {
            Thread.interrupted();
        }
    }

    private URI startServer(int statusCode) throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/health", exchange -> {
            var body = "ok".getBytes(StandardCharsets.UTF_8);
            var responseLength = statusCode == 204 ? -1 : body.length;
            exchange.sendResponseHeaders(statusCode, responseLength);
            if (responseLength >= 0) {
                exchange.getResponseBody().write(body);
            }
            exchange.close();
        });
        server.start();
        return URI.create("http://127.0.0.1:%d/health".formatted(server.getAddress().getPort()));
    }
}
