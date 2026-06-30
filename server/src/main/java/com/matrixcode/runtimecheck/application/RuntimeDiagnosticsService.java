package com.matrixcode.runtimecheck.application;

import com.matrixcode.modelgateway.application.ModelGatewayProperties;
import com.matrixcode.persistence.application.PersistenceModeProperties;
import com.matrixcode.runtimecheck.domain.RuntimeCheckItem;
import com.matrixcode.runtimecheck.domain.RuntimeCheckStatus;
import com.matrixcode.runtimecheck.domain.RuntimeDiagnosticsReport;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

@Service
public class RuntimeDiagnosticsService {

    private static final Duration TCP_TIMEOUT = Duration.ofSeconds(2);

    private final PersistenceModeProperties persistenceProperties;
    private final ModelGatewayProperties modelGatewayProperties;
    private final TcpConnectivityProbe tcpProbe;
    private final RuntimeProtocolProbe protocolProbe;
    private final Environment environment;

    @Autowired
    public RuntimeDiagnosticsService(
            PersistenceModeProperties persistenceProperties,
            ModelGatewayProperties modelGatewayProperties,
            TcpConnectivityProbe tcpProbe,
            RuntimeProtocolProbe protocolProbe,
            Environment environment
    ) {
        this.persistenceProperties = persistenceProperties;
        this.modelGatewayProperties = modelGatewayProperties;
        this.tcpProbe = tcpProbe;
        this.protocolProbe = protocolProbe;
        this.environment = environment;
    }

    public RuntimeDiagnosticsService(
            PersistenceModeProperties persistenceProperties,
            ModelGatewayProperties modelGatewayProperties,
            TcpConnectivityProbe tcpProbe
    ) {
        this(persistenceProperties, modelGatewayProperties, tcpProbe, RuntimeProtocolProbe.noop(), null);
    }

    public RuntimeDiagnosticsService(
            PersistenceModeProperties persistenceProperties,
            ModelGatewayProperties modelGatewayProperties,
            TcpConnectivityProbe tcpProbe,
            RuntimeProtocolProbe protocolProbe
    ) {
        this(persistenceProperties, modelGatewayProperties, tcpProbe, protocolProbe, null);
    }

    public RuntimeDiagnosticsReport inspect() {
        var items = new ArrayList<RuntimeCheckItem>();
        inspectJdbc(items);
        inspectProviders(items);
        inspectVectorContext(items);
        inspectReservedInfrastructure(items);
        return new RuntimeDiagnosticsReport(status(items), Instant.now(), items, nextActions(items));
    }

    private void inspectJdbc(List<RuntimeCheckItem> items) {
        if (!"jdbc".equalsIgnoreCase(persistenceProperties.getMode())) {
            items.add(pass("mysql.mode", "MySQL 持久化模式", "当前未启用 JDBC 模式", false));
            return;
        }
        var jdbc = persistenceProperties.getJdbc();
        if (jdbc.getUrl().isBlank()) {
            items.add(fail("mysql.url", "MySQL JDBC URL", "JDBC URL 不能为空", true));
            return;
        }
        var credentialsReady = !(jdbc.getUsername().isBlank()
                || jdbc.getPassword().isBlank()
                || "change-me".equals(jdbc.getPassword()));
        if (!credentialsReady) {
            items.add(fail("mysql.credentials", "MySQL 凭证", "JDBC 用户名或密码未配置真实值", true));
        } else {
            items.add(pass("mysql.credentials", "MySQL 凭证", "JDBC 用户名和密码变量已设置", true));
        }
        var endpoint = mysqlEndpoint(jdbc.getUrl());
        if (endpoint == null) {
            items.add(fail("mysql.connectivity", "MySQL 连通性", "无法从 JDBC URL 解析 MySQL 地址", true));
            return;
        }
        var connectivity = tcp("mysql.connectivity", "MySQL 连通性", endpoint.host(), endpoint.port(), true);
        items.add(connectivity);
        if (credentialsReady && connectivity.status() == RuntimeCheckStatus.PASS) {
            items.add(protocol("mysql.protocol", "MySQL 协议检查", protocolProbe.checkJdbc(jdbc), true));
        }
    }

    private void inspectProviders(List<RuntimeCheckItem> items) {
        modelGatewayProperties.getProviders().forEach((id, provider) -> {
            if (!provider.isEnabled()) {
                items.add(skipped("provider.%s".formatted(id), "%s 供应商".formatted(provider.getName()), "供应商未启用", false));
                return;
            }
            var source = provider.getApiKeySource();
            if (source == null || source.isBlank() || "NONE".equalsIgnoreCase(source)) {
                items.add(fail("provider.%s.api-key".formatted(id), "%s API Key".formatted(provider.getName()),
                        "供应商已启用但未配置 API Key 环境变量名", true));
                return;
            }
            var value = System.getenv(source);
            if (value == null || value.isBlank() || "change-me".equals(value)) {
                items.add(fail("provider.%s.api-key".formatted(id), "%s API Key".formatted(provider.getName()),
                        "环境变量 %s 未设置真实值".formatted(source), true));
            } else {
                items.add(pass("provider.%s.api-key".formatted(id), "%s API Key".formatted(provider.getName()),
                        "环境变量 %s 已设置".formatted(source), true));
            }
        });
    }

    private void inspectVectorContext(List<RuntimeCheckItem> items) {
        var vector = modelGatewayProperties.getVectorContext();
        if (!vector.isEnabled()) {
            items.add(skipped("vector-context.enabled", "向量上下文", "向量上下文未启用", false));
            return;
        }
        if (!"milvus".equalsIgnoreCase(vector.getStore())) {
            items.add(pass("vector-context.store", "向量上下文存储", "当前使用 %s store".formatted(vector.getStore()), false));
            return;
        }
        var milvus = modelGatewayProperties.getMilvus();
        if (milvus.getHost().isBlank() || milvus.getPort() <= 0 || milvus.getCollection().isBlank()) {
            items.add(fail("milvus.config", "Milvus 配置", "Milvus host、port、collection 必须配置", true));
        } else {
            items.add(pass("milvus.config", "Milvus 配置", "Milvus 地址和 collection 已配置", true));
            var connectivity = tcp("milvus.connectivity", "Milvus 连通性", milvus.getHost(), milvus.getPort(), true);
            items.add(connectivity);
            if (connectivity.status() == RuntimeCheckStatus.PASS) {
                items.add(protocol("milvus.protocol", "Milvus 协议检查", protocolProbe.checkMilvus(milvus), true));
            }
        }
        if (vector.getDimension() <= 0) {
            items.add(fail("vector-context.dimension", "向量维度", "向量维度必须大于 0", true));
        } else {
            items.add(pass("vector-context.dimension", "向量维度", "向量维度：" + vector.getDimension(), true));
        }
    }

    private void inspectReservedInfrastructure(List<RuntimeCheckItem> items) {
        items.add(tcp(
                "redis.connectivity",
                "Redis 连通性",
                property("matrixcode.redis.host", "127.0.0.1"),
                intProperty("matrixcode.redis.port", 6379),
                false
        ));
        var rocketNameServer = property("matrixcode.rocketmq.name-server", "127.0.0.1:9876");
        var rocketEndpoint = endpointFromHostPort(rocketNameServer, 9876);
        items.add(tcp("rocketmq.connectivity", "RocketMQ 连通性", rocketEndpoint.host(), rocketEndpoint.port(), false));
    }

    private RuntimeCheckItem tcp(String key, String label, String host, int port, boolean blocking) {
        if (tcpProbe.canConnect(host, port, TCP_TIMEOUT)) {
            return pass(key, label, "%s:%d 可连接".formatted(host, port), blocking);
        }
        var detail = "%s:%d 当前不可连接%s".formatted(host, port, blocking ? "" : "，已预留但不阻塞");
        return new RuntimeCheckItem(key, label, blocking ? RuntimeCheckStatus.FAIL : RuntimeCheckStatus.WARN, detail, blocking);
    }

    private RuntimeCheckItem protocol(
            String key,
            String label,
            RuntimeProtocolProbeResult result,
            boolean blocking
    ) {
        if (result.passed()) {
            return pass(key, label, result.detail(), blocking);
        }
        return fail(key, label, result.detail(), blocking);
    }

    private RuntimeCheckStatus status(List<RuntimeCheckItem> items) {
        if (items.stream().anyMatch(item -> item.blocking() && item.status() == RuntimeCheckStatus.FAIL)) {
            return RuntimeCheckStatus.FAIL;
        }
        if (items.stream().anyMatch(item -> item.status() == RuntimeCheckStatus.WARN || item.status() == RuntimeCheckStatus.FAIL)) {
            return RuntimeCheckStatus.WARN;
        }
        return RuntimeCheckStatus.PASS;
    }

    private List<String> nextActions(List<RuntimeCheckItem> items) {
        return items.stream()
                .filter(item -> item.status() == RuntimeCheckStatus.FAIL || item.status() == RuntimeCheckStatus.WARN)
                .sorted(Comparator.comparing(RuntimeCheckItem::blocking).reversed())
                .map(item -> "%s：%s".formatted(item.label(), item.detail()))
                .toList();
    }

    private RuntimeCheckItem pass(String key, String label, String detail, boolean blocking) {
        return new RuntimeCheckItem(key, label, RuntimeCheckStatus.PASS, detail, blocking);
    }

    private RuntimeCheckItem fail(String key, String label, String detail, boolean blocking) {
        return new RuntimeCheckItem(key, label, RuntimeCheckStatus.FAIL, detail, blocking);
    }

    private RuntimeCheckItem skipped(String key, String label, String detail, boolean blocking) {
        return new RuntimeCheckItem(key, label, RuntimeCheckStatus.SKIPPED, detail, blocking);
    }

    private Endpoint mysqlEndpoint(String jdbcUrl) {
        try {
            var normalized = jdbcUrl.replaceFirst("^jdbc:", "");
            var uri = URI.create(normalized);
            var host = uri.getHost();
            var port = uri.getPort() > 0 ? uri.getPort() : 3306;
            return host == null || host.isBlank() ? null : new Endpoint(host, port);
        } catch (IllegalArgumentException exception) {
            return null;
        }
    }

    private Endpoint endpointFromHostPort(String value, int defaultPort) {
        if (value == null || value.isBlank()) {
            return new Endpoint("localhost", defaultPort);
        }
        var normalized = value.trim();
        var separator = normalized.lastIndexOf(':');
        if (separator < 0) {
            return new Endpoint(normalized, defaultPort);
        }
        try {
            return new Endpoint(normalized.substring(0, separator), Integer.parseInt(normalized.substring(separator + 1)));
        } catch (NumberFormatException exception) {
            return new Endpoint(normalized.substring(0, separator), defaultPort);
        }
    }

    private String property(String key, String fallback) {
        return environment == null ? fallback : environment.getProperty(key, fallback);
    }

    private int intProperty(String key, int fallback) {
        return environment == null ? fallback : environment.getProperty(key, Integer.class, fallback);
    }

    private record Endpoint(String host, int port) {
    }
}
