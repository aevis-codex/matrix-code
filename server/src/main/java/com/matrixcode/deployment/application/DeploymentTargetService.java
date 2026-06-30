package com.matrixcode.deployment.application;

import com.matrixcode.deployment.domain.DeploymentTarget;
import com.matrixcode.deployment.domain.DeploymentTargetStatus;
import com.matrixcode.workbench.application.InMemoryWorkbenchStateStore;
import com.matrixcode.workbench.application.WorkbenchStateStore;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class DeploymentTargetService {

    private final Object lock = new Object();
    private final Map<String, DeploymentTarget> targets = new ConcurrentHashMap<>();
    private final WorkbenchStateStore stateStore;
    private final DeploymentTargetRepository repository;
    private volatile boolean loaded;

    public DeploymentTargetService() {
        this(new InMemoryWorkbenchStateStore(), (DeploymentTargetRepository) null);
    }

    @Autowired
    public DeploymentTargetService(
            WorkbenchStateStore stateStore,
            ObjectProvider<DeploymentTargetRepository> repository
    ) {
        this(stateStore, repository.getIfAvailable());
    }

    public DeploymentTargetService(WorkbenchStateStore stateStore) {
        this(stateStore, (DeploymentTargetRepository) null);
    }

    public DeploymentTargetService(WorkbenchStateStore stateStore, DeploymentTargetRepository repository) {
        this.stateStore = stateStore;
        this.repository = repository;
    }

    public DeploymentTarget configure(
            String projectId,
            String environmentName,
            String environmentUrl,
            String sshAddress,
            String deployNote,
            String healthCheckUrl,
            String rollbackNote
    ) {
        requireText(projectId, "项目编号不能为空");
        requireText(environmentName, "环境名称不能为空");
        requireText(environmentUrl, "环境地址不能为空");
        requireText(sshAddress, "SSH 地址不能为空");
        requireText(deployNote, "部署说明不能为空");
        requireText(healthCheckUrl, "健康检查地址不能为空");
        requireText(rollbackNote, "回滚说明不能为空");
        synchronized (lock) {
            ensureLoaded();
            var target = new DeploymentTarget(
                    UUID.randomUUID().toString(),
                    projectId,
                    environmentName,
                    environmentUrl,
                    sshAddress,
                    deployNote,
                    healthCheckUrl,
                    rollbackNote,
                    DeploymentTargetStatus.RECORDED,
                    false,
                    Instant.now()
            );
            targets.put(target.id(), target);
            save();
            return target;
        }
    }

    public List<DeploymentTarget> listByProject(String projectId) {
        requireText(projectId, "项目编号不能为空");
        synchronized (lock) {
            ensureLoaded();
            var projectTargets = targets.values().stream()
                    .filter(target -> projectId.equals(target.projectId()))
                    .sorted(Comparator.comparing(DeploymentTarget::environmentName).thenComparing(DeploymentTarget::id))
                    .toList();
            return List.copyOf(projectTargets);
        }
    }

    public DeploymentTarget requireByProject(String projectId, String targetId) {
        requireText(projectId, "项目编号不能为空");
        requireText(targetId, "部署目标编号不能为空");
        synchronized (lock) {
            ensureLoaded();
            var target = targets.get(targetId);
            if (target == null || !projectId.equals(target.projectId())) {
                throw new IllegalArgumentException("部署目标不存在：" + targetId);
            }
            return target;
        }
    }

    private void requireText(String value, String message) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(message);
        }
    }

    private void save() {
        var values = List.copyOf(targets.values());
        if (repository != null) {
            repository.save(values);
        } else {
            stateStore.saveDeploymentTargets(values);
        }
    }

    private void ensureLoaded() {
        if (loaded) {
            return;
        }
        synchronized (lock) {
            if (loaded) {
                return;
            }
            loadPersistedTargets().forEach(target -> targets.put(target.id(), target));
            loaded = true;
        }
    }

    private List<DeploymentTarget> loadPersistedTargets() {
        if (repository != null) {
            var persisted = repository.load();
            if (!persisted.isEmpty()) {
                return persisted;
            }
        }
        var restored = stateStore.load().deploymentTargets();
        if (repository != null && !restored.isEmpty()) {
            repository.save(restored);
        }
        return restored;
    }
}
