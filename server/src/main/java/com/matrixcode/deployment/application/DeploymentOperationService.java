package com.matrixcode.deployment.application;

import com.matrixcode.deployment.domain.DeploymentOperationRecord;
import com.matrixcode.deployment.domain.DeploymentOperationStatus;
import com.matrixcode.deployment.domain.DeploymentOperationType;
import com.matrixcode.workbench.application.InMemoryWorkbenchStateStore;
import com.matrixcode.workbench.application.WorkbenchStateStore;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class DeploymentOperationService {

    private static final int HISTORY_LIMIT = 20;

    private final Object lock = new Object();
    private final DeploymentTargetService targets;
    private final Map<String, ArrayDeque<DeploymentOperationRecord>> operations = new ConcurrentHashMap<>();
    private final WorkbenchStateStore stateStore;
    private final DeploymentRuntimeRepository repository;

    public DeploymentOperationService(DeploymentTargetService targets) {
        this(targets, new InMemoryWorkbenchStateStore(), (DeploymentRuntimeRepository) null);
    }

    @Autowired
    public DeploymentOperationService(
            DeploymentTargetService targets,
            WorkbenchStateStore stateStore,
            ObjectProvider<DeploymentRuntimeRepository> repository
    ) {
        this(targets, stateStore, repository.getIfAvailable());
    }

    public DeploymentOperationService(DeploymentTargetService targets, WorkbenchStateStore stateStore) {
        this(targets, stateStore, (DeploymentRuntimeRepository) null);
    }

    public DeploymentOperationService(
            DeploymentTargetService targets,
            WorkbenchStateStore stateStore,
            DeploymentRuntimeRepository repository
    ) {
        this.targets = targets;
        this.stateStore = stateStore;
        this.repository = repository;
        loadPersistedOperations()
                .forEach((projectId, records) -> operations.put(projectId, new ArrayDeque<>(records)));
    }

    public DeploymentOperationRecord record(
            String projectId,
            String targetId,
            String actorId,
            DeploymentOperationType type,
            DeploymentOperationStatus status,
            String note
    ) {
        actorId = requireText(actorId, "操作者不能为空");
        note = requireText(note, "操作说明不能为空");
        if (type == null) {
            throw new IllegalArgumentException("运维操作类型不支持");
        }
        if (status == null) {
            throw new IllegalArgumentException("运维操作状态不支持");
        }
        var target = targets.requireByProject(projectId, targetId);
        var record = new DeploymentOperationRecord(
                UUID.randomUUID().toString(),
                projectId,
                target.id(),
                actorId,
                type,
                status,
                note,
                Instant.now()
        );
        return remember(record);
    }

    /**
     * 写入外部发布脚本导入的部署操作记录。
     *
     * <p>外部审计记录已经带有发生时间和确定性 ID，本方法负责复用部署目标边界校验、
     * 幂等去重和统一持久化逻辑，不重新生成时间或 ID。</p>
     */
    public DeploymentOperationRecord recordImported(DeploymentOperationRecord record) {
        if (record == null) {
            throw new IllegalArgumentException("部署操作记录不能为空");
        }
        requireText(record.id(), "部署操作记录编号不能为空");
        requireText(record.projectId(), "项目编号不能为空");
        requireText(record.targetId(), "部署目标编号不能为空");
        requireText(record.actorId(), "操作者不能为空");
        requireText(record.note(), "操作说明不能为空");
        if (record.type() == null) {
            throw new IllegalArgumentException("运维操作类型不支持");
        }
        if (record.status() == null) {
            throw new IllegalArgumentException("运维操作状态不支持");
        }
        if (record.createdAt() == null) {
            throw new IllegalArgumentException("操作发生时间不能为空");
        }
        targets.requireByProject(record.projectId(), record.targetId());
        synchronized (lock) {
            var existing = operations.getOrDefault(record.projectId(), new ArrayDeque<>()).stream()
                    .filter(item -> item.id().equals(record.id()))
                    .findFirst();
            if (existing.isPresent()) {
                return existing.get();
            }
            return remember(record);
        }
    }

    public boolean hasRecord(String projectId, String recordId) {
        requireText(projectId, "项目编号不能为空");
        requireText(recordId, "部署操作记录编号不能为空");
        synchronized (lock) {
            return operations.getOrDefault(projectId, new ArrayDeque<>()).stream()
                    .anyMatch(record -> record.id().equals(recordId));
        }
    }

    public List<DeploymentOperationRecord> latestByProject(String projectId) {
        requireText(projectId, "项目编号不能为空");
        synchronized (lock) {
            return operations.getOrDefault(projectId, new ArrayDeque<>()).stream()
                    .sorted(Comparator.comparing(DeploymentOperationRecord::createdAt).reversed())
                    .toList();
        }
    }

    public DeploymentOperationRecord latestDeploymentForTarget(String projectId, String targetId) {
        return latestForTarget(projectId, targetId, DeploymentOperationType.DEPLOYMENT);
    }

    public DeploymentOperationRecord latestRollbackForTarget(String projectId, String targetId) {
        return latestForTarget(projectId, targetId, DeploymentOperationType.ROLLBACK);
    }

    private DeploymentOperationRecord latestForTarget(String projectId, String targetId, DeploymentOperationType type) {
        requireText(projectId, "项目编号不能为空");
        requireText(targetId, "部署目标编号不能为空");
        synchronized (lock) {
            return operations.getOrDefault(projectId, new ArrayDeque<>()).stream()
                    .filter(record -> targetId.equals(record.targetId()))
                    .filter(record -> type == record.type())
                    .max(Comparator.comparing(DeploymentOperationRecord::createdAt))
                    .orElse(null);
        }
    }

    private DeploymentOperationRecord remember(DeploymentOperationRecord record) {
        synchronized (lock) {
            var records = operations.computeIfAbsent(record.projectId(), ignored -> new ArrayDeque<>());
            records.addFirst(record);
            while (records.size() > HISTORY_LIMIT) {
                records.removeLast();
            }
            var snapshot = snapshot();
            if (repository != null) {
                repository.saveDeploymentOperations(snapshot);
            } else {
                stateStore.saveDeploymentOperations(snapshot);
            }
            return record;
        }
    }

    private Map<String, List<DeploymentOperationRecord>> snapshot() {
        var snapshot = new java.util.HashMap<String, List<DeploymentOperationRecord>>();
        operations.forEach((projectId, records) -> snapshot.put(projectId, List.copyOf(records)));
        return snapshot;
    }

    private Map<String, List<DeploymentOperationRecord>> loadPersistedOperations() {
        if (repository != null) {
            var persisted = repository.loadDeploymentOperations();
            if (!persisted.isEmpty()) {
                return persisted;
            }
        }
        var restored = stateStore.load().deploymentOperations();
        if (repository != null && !restored.isEmpty()) {
            restored.keySet().forEach(targets::listByProject);
            repository.saveDeploymentOperations(restored);
        }
        return restored;
    }

    private String requireText(String value, String message) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(message);
        }
        return value.trim();
    }
}
