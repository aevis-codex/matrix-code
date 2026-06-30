package com.matrixcode.deployment.application;

import com.matrixcode.deployment.domain.DeploymentHealthStatus;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

@Component
public class HttpDeploymentHealthClient implements DeploymentHealthClient {

    private final HttpClient client;

    public HttpDeploymentHealthClient() {
        this(HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(2))
                .build());
    }

    HttpDeploymentHealthClient(HttpClient client) {
        this.client = client;
    }

    @Override
    public DeploymentHealthProbe check(URI uri) {
        var start = System.nanoTime();
        try {
            var request = HttpRequest.newBuilder(uri)
                    .timeout(Duration.ofSeconds(3))
                    .GET()
                    .build();
            var response = client.send(request, HttpResponse.BodyHandlers.discarding());
            var durationMillis = elapsedMillis(start);
            var statusCode = response.statusCode();
            var status = statusCode >= 200 && statusCode < 400
                    ? DeploymentHealthStatus.HEALTHY
                    : DeploymentHealthStatus.UNHEALTHY;
            return new DeploymentHealthProbe(status, statusCode, durationMillis, "HTTP " + statusCode);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            return unreachable(start);
        } catch (Exception exception) {
            return unreachable(start);
        }
    }

    private DeploymentHealthProbe unreachable(long start) {
        return new DeploymentHealthProbe(
                DeploymentHealthStatus.UNREACHABLE,
                null,
                elapsedMillis(start),
                "健康检查地址不可达"
        );
    }

    private long elapsedMillis(long start) {
        return Math.max(0, Duration.ofNanos(System.nanoTime() - start).toMillis());
    }
}
