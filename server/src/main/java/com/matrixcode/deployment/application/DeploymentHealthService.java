package com.matrixcode.deployment.application;

import com.matrixcode.deployment.domain.DeploymentHealthCheck;
import com.matrixcode.deployment.domain.DeploymentHealthStatus;
import com.matrixcode.workbench.application.InMemoryWorkbenchStateStore;
import com.matrixcode.workbench.application.WorkbenchStateStore;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class DeploymentHealthService {

    private static final int HISTORY_LIMIT = 20;

    private final Object lock = new Object();
    private final DeploymentTargetService targets;
    private final DeploymentHealthClient client;
    private final Map<String, ArrayDeque<DeploymentHealthCheck>> checks = new ConcurrentHashMap<>();
    private final WorkbenchStateStore stateStore;
    private final DeploymentRuntimeRepository repository;

    public DeploymentHealthService(DeploymentTargetService targets, DeploymentHealthClient client) {
        this(targets, client, new InMemoryWorkbenchStateStore(), (DeploymentRuntimeRepository) null);
    }

    @Autowired
    public DeploymentHealthService(
            DeploymentTargetService targets,
            DeploymentHealthClient client,
            WorkbenchStateStore stateStore,
            ObjectProvider<DeploymentRuntimeRepository> repository
    ) {
        this(targets, client, stateStore, repository.getIfAvailable());
    }

    public DeploymentHealthService(
            DeploymentTargetService targets,
            DeploymentHealthClient client,
            WorkbenchStateStore stateStore
    ) {
        this(targets, client, stateStore, (DeploymentRuntimeRepository) null);
    }

    public DeploymentHealthService(
            DeploymentTargetService targets,
            DeploymentHealthClient client,
            WorkbenchStateStore stateStore,
            DeploymentRuntimeRepository repository
    ) {
        this.targets = targets;
        this.client = client;
        this.stateStore = stateStore;
        this.repository = repository;
        loadPersistedChecks()
                .forEach((projectId, records) -> checks.put(projectId, new ArrayDeque<>(records)));
    }

    public DeploymentHealthCheck check(String projectId, String targetId, String actorId) {
        actorId = requireText(actorId, "操作者不能为空");
        var target = targets.requireByProject(projectId, targetId);
        var probe = probe(target.healthCheckUrl());
        var check = new DeploymentHealthCheck(
                UUID.randomUUID().toString(),
                projectId,
                target.id(),
                actorId,
                probe.status(),
                probe.httpStatus(),
                probe.durationMillis(),
                probe.summary(),
                Instant.now()
        );
        return remember(check);
    }

    public List<DeploymentHealthCheck> latestByProject(String projectId) {
        requireText(projectId, "项目编号不能为空");
        synchronized (lock) {
            return checks.getOrDefault(projectId, new ArrayDeque<>()).stream()
                    .sorted(Comparator.comparing(DeploymentHealthCheck::checkedAt).reversed())
                    .toList();
        }
    }

    public DeploymentHealthCheck latestForTarget(String projectId, String targetId) {
        requireText(projectId, "项目编号不能为空");
        requireText(targetId, "部署目标编号不能为空");
        synchronized (lock) {
            return checks.getOrDefault(projectId, new ArrayDeque<>()).stream()
                    .filter(check -> targetId.equals(check.targetId()))
                    .max(Comparator.comparing(DeploymentHealthCheck::checkedAt))
                    .orElse(null);
        }
    }

    private DeploymentHealthProbe probe(String healthCheckUrl) {
        var url = requireHealthCheckUrl(healthCheckUrl);
        if (url == null) {
            return unreachable("健康检查地址不可达");
        }
        try {
            var uri = URI.create(url);
            var scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase();
            if (!List.of("http", "https").contains(scheme)) {
                return unreachable("健康检查地址协议不支持");
            }
            return Objects.requireNonNull(client.check(uri));
        } catch (RuntimeException exception) {
            return unreachable("健康检查地址不可达");
        }
    }

    private DeploymentHealthProbe unreachable(String summary) {
        return new DeploymentHealthProbe(DeploymentHealthStatus.UNREACHABLE, null, 0, summary);
    }

    private DeploymentHealthCheck remember(DeploymentHealthCheck check) {
        synchronized (lock) {
            var records = checks.computeIfAbsent(check.projectId(), ignored -> new ArrayDeque<>());
            records.addFirst(check);
            while (records.size() > HISTORY_LIMIT) {
                records.removeLast();
            }
            var snapshot = snapshot();
            if (repository != null) {
                repository.saveDeploymentHealthChecks(snapshot);
            } else {
                stateStore.saveDeploymentHealthChecks(snapshot);
            }
            return check;
        }
    }

    private Map<String, List<DeploymentHealthCheck>> snapshot() {
        var snapshot = new java.util.HashMap<String, List<DeploymentHealthCheck>>();
        checks.forEach((projectId, records) -> snapshot.put(projectId, List.copyOf(records)));
        return snapshot;
    }

    private Map<String, List<DeploymentHealthCheck>> loadPersistedChecks() {
        if (repository != null) {
            var persisted = repository.loadDeploymentHealthChecks();
            if (!persisted.isEmpty()) {
                return persisted;
            }
        }
        var restored = stateStore.load().deploymentHealthChecks();
        if (repository != null && !restored.isEmpty()) {
            restored.keySet().forEach(targets::listByProject);
            repository.saveDeploymentHealthChecks(restored);
        }
        return restored;
    }

    private String requireHealthCheckUrl(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return value.trim();
    }

    private String requireText(String value, String message) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(message);
        }
        return value.trim();
    }
}
