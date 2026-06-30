package com.matrixcode.deployment.application;

import com.matrixcode.deployment.domain.ComposeEnvironment;
import com.matrixcode.deployment.domain.ComposeEnvironmentStatus;
import com.matrixcode.deployment.domain.ComposeOperationRecord;
import com.matrixcode.deployment.domain.ComposeOperationStatus;
import com.matrixcode.deployment.domain.ComposeOperationType;
import com.matrixcode.localexecution.application.PathGuard;
import com.matrixcode.localexecution.application.WorkspaceRegistry;
import com.matrixcode.workbench.application.InMemoryWorkbenchStateStore;
import com.matrixcode.workbench.application.WorkbenchStateStore;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;
import java.util.regex.Pattern;

@Service
public class ComposeEnvironmentService {

    private static final int HISTORY_LIMIT = 20;
    private static final Pattern PROJECT_NAME_PATTERN = Pattern.compile("[a-z0-9][a-z0-9_-]{1,62}");
    private static final Pattern SERVICE_NAME_PATTERN = Pattern.compile("[A-Za-z0-9][A-Za-z0-9_.-]{0,63}");

    private final Object lock = new Object();
    private final DeploymentTargetService targets;
    private final WorkspaceRegistry workspaces;
    private final PathGuard pathGuard;
    private final ComposeRuntimeClient runtimeClient;
    private final Map<String, ComposeEnvironment> environments = new ConcurrentHashMap<>();
    private final Map<String, ArrayDeque<ComposeOperationRecord>> operations = new ConcurrentHashMap<>();
    private final WorkbenchStateStore stateStore;
    private final DeploymentRuntimeRepository repository;

    public ComposeEnvironmentService(
            DeploymentTargetService targets,
            WorkspaceRegistry workspaces,
            PathGuard pathGuard,
            ComposeRuntimeClient runtimeClient
    ) {
        this(targets, workspaces, pathGuard, runtimeClient, new InMemoryWorkbenchStateStore(),
                (DeploymentRuntimeRepository) null);
    }

    @Autowired
    public ComposeEnvironmentService(
            DeploymentTargetService targets,
            WorkspaceRegistry workspaces,
            PathGuard pathGuard,
            ComposeRuntimeClient runtimeClient,
            WorkbenchStateStore stateStore,
            ObjectProvider<DeploymentRuntimeRepository> repository
    ) {
        this(targets, workspaces, pathGuard, runtimeClient, stateStore, repository.getIfAvailable());
    }

    public ComposeEnvironmentService(
            DeploymentTargetService targets,
            WorkspaceRegistry workspaces,
            PathGuard pathGuard,
            ComposeRuntimeClient runtimeClient,
            WorkbenchStateStore stateStore
    ) {
        this(targets, workspaces, pathGuard, runtimeClient, stateStore, (DeploymentRuntimeRepository) null);
    }

    public ComposeEnvironmentService(
            DeploymentTargetService targets,
            WorkspaceRegistry workspaces,
            PathGuard pathGuard,
            ComposeRuntimeClient runtimeClient,
            WorkbenchStateStore stateStore,
            DeploymentRuntimeRepository repository
    ) {
        this.targets = targets;
        this.workspaces = workspaces;
        this.pathGuard = pathGuard;
        this.runtimeClient = runtimeClient;
        this.stateStore = stateStore;
        this.repository = repository;
        loadPersistedComposeEnvironments().forEach(environment -> environments.put(environment.id(), environment));
        loadPersistedComposeOperations()
                .forEach((projectId, records) -> operations.put(projectId, new ArrayDeque<>(records)));
    }

    public ComposeEnvironment configure(
            String projectId,
            String targetId,
            String workspaceId,
            String composeFilePath,
            String projectName,
            String serviceName
    ) {
        projectId = requireText(projectId, "项目编号不能为空");
        var target = targets.requireByProject(projectId, targetId);
        var workspace = workspaces.requireAuthorized(projectId, workspaceId);
        var normalizedPath = normalizeComposePath(composeFilePath);
        var composeFile = pathGuard.resolveExisting(workspace.rootPath(), normalizedPath);
        requireYaml(normalizedPath);
        projectName = requirePattern(projectName, PROJECT_NAME_PATTERN, "Compose 项目名不合法");
        serviceName = requirePattern(serviceName, SERVICE_NAME_PATTERN, "Compose 服务名不合法");
        var now = Instant.now();
        var environment = new ComposeEnvironment(
                UUID.randomUUID().toString(),
                projectId,
                target.id(),
                workspace.id(),
                normalizedPath,
                projectName,
                serviceName,
                ComposeEnvironmentStatus.CONFIGURED,
                now,
                now
        );
        synchronized (lock) {
            environments.put(environment.id(), environment);
            saveEnvironments();
            return environment;
        }
    }

    public List<ComposeEnvironment> listByProject(String projectId) {
        projectId = requireText(projectId, "项目编号不能为空");
        var targetProject = projectId;
        synchronized (lock) {
            return environments.values().stream()
                    .filter(environment -> targetProject.equals(environment.projectId()))
                    .sorted(Comparator.comparing(ComposeEnvironment::createdAt).thenComparing(ComposeEnvironment::id))
                    .toList();
        }
    }

    public ComposeEnvironment requireByProject(String projectId, String environmentId) {
        projectId = requireText(projectId, "项目编号不能为空");
        environmentId = requireText(environmentId, "Compose 环境编号不能为空");
        synchronized (lock) {
            var environment = environments.get(environmentId);
            if (environment == null || !projectId.equals(environment.projectId())) {
                throw new IllegalArgumentException("Compose 环境不存在：" + environmentId);
            }
            return environment;
        }
    }

    public ComposeOperationRecord validate(String projectId, String environmentId, String actorId) {
        return execute(projectId, environmentId, actorId, ComposeOperationType.VALIDATE,
                ComposeEnvironmentStatus.VALIDATED, runtimeClient::validate);
    }

    public ComposeOperationRecord start(String projectId, String environmentId, String actorId) {
        return execute(projectId, environmentId, actorId, ComposeOperationType.START,
                ComposeEnvironmentStatus.RUNNING, runtimeClient::start);
    }

    public ComposeOperationRecord stop(String projectId, String environmentId, String actorId) {
        return execute(projectId, environmentId, actorId, ComposeOperationType.STOP,
                ComposeEnvironmentStatus.STOPPED, runtimeClient::stop);
    }

    public ComposeOperationRecord captureLogs(String projectId, String environmentId, String actorId) {
        return execute(projectId, environmentId, actorId, ComposeOperationType.LOGS,
                null, runtimeClient::logs);
    }

    public List<ComposeOperationRecord> latestByProject(String projectId) {
        projectId = requireText(projectId, "项目编号不能为空");
        synchronized (lock) {
            return operations.getOrDefault(projectId, new ArrayDeque<>()).stream()
                    .sorted(Comparator.comparing(ComposeOperationRecord::createdAt).reversed())
                    .toList();
        }
    }

    public ComposeOperationRecord latestOperationForEnvironment(String projectId, String environmentId) {
        projectId = requireText(projectId, "项目编号不能为空");
        environmentId = requireText(environmentId, "Compose 环境编号不能为空");
        var targetEnvironmentId = environmentId;
        synchronized (lock) {
            return operations.getOrDefault(projectId, new ArrayDeque<>()).stream()
                    .filter(record -> targetEnvironmentId.equals(record.environmentId()))
                    .max(Comparator.comparing(ComposeOperationRecord::createdAt))
                    .orElse(null);
        }
    }

    private ComposeOperationRecord execute(
            String projectId,
            String environmentId,
            String actorId,
            ComposeOperationType type,
            ComposeEnvironmentStatus successStatus,
            Function<ComposeRuntimeRequest, ComposeRuntimeResult> operation
    ) {
        actorId = requireText(actorId, "操作者不能为空");
        var environment = requireByProject(projectId, environmentId);
        var request = request(environment);
        var result = operation.apply(request);
        var nextStatus = result.status() == ComposeOperationStatus.SUCCEEDED
                ? (successStatus == null ? environment.status() : successStatus)
                : ComposeEnvironmentStatus.FAILED;
        var updated = environment.withStatus(nextStatus);
        var record = new ComposeOperationRecord(
                UUID.randomUUID().toString(),
                environment.projectId(),
                environment.id(),
                actorId,
                type,
                result.status(),
                result.summary(),
                result.logExcerpt(),
                Instant.now()
        );
        synchronized (lock) {
            environments.put(updated.id(), updated);
            remember(record);
            saveEnvironments();
            saveOperations();
            return record;
        }
    }

    private ComposeRuntimeRequest request(ComposeEnvironment environment) {
        var workspace = workspaces.requireAuthorized(environment.projectId(), environment.workspaceId());
        var composeFile = pathGuard.resolveExisting(workspace.rootPath(), environment.composeFilePath());
        return new ComposeRuntimeRequest(composeFile, environment.projectName(), environment.serviceName());
    }

    private void remember(ComposeOperationRecord record) {
        var records = operations.computeIfAbsent(record.projectId(), ignored -> new ArrayDeque<>());
        records.addFirst(record);
        while (records.size() > HISTORY_LIMIT) {
            records.removeLast();
        }
    }

    private void saveEnvironments() {
        var values = List.copyOf(environments.values());
        if (repository != null) {
            repository.saveComposeEnvironments(values);
        } else {
            stateStore.saveComposeEnvironments(values);
        }
    }

    private void saveOperations() {
        var snapshot = new java.util.HashMap<String, List<ComposeOperationRecord>>();
        operations.forEach((projectId, records) -> snapshot.put(projectId, List.copyOf(records)));
        if (repository != null) {
            repository.saveComposeOperations(snapshot);
        } else {
            stateStore.saveComposeOperations(snapshot);
        }
    }

    private List<ComposeEnvironment> loadPersistedComposeEnvironments() {
        if (repository != null) {
            var persisted = repository.loadComposeEnvironments();
            if (!persisted.isEmpty()) {
                return persisted;
            }
        }
        var restored = stateStore.load().composeEnvironments();
        if (repository != null && !restored.isEmpty()) {
            restored.stream()
                    .map(ComposeEnvironment::projectId)
                    .distinct()
                    .forEach(targets::listByProject);
            repository.saveComposeEnvironments(restored);
        }
        return restored;
    }

    private Map<String, List<ComposeOperationRecord>> loadPersistedComposeOperations() {
        if (repository != null) {
            var persisted = repository.loadComposeOperations();
            if (!persisted.isEmpty()) {
                return persisted;
            }
        }
        var restored = stateStore.load().composeOperations();
        if (repository != null && !restored.isEmpty()) {
            repository.saveComposeOperations(restored);
        }
        return restored;
    }

    private String normalizeComposePath(String composeFilePath) {
        var normalized = Path.of(requireText(composeFilePath, "Compose 文件路径不能为空")).normalize();
        if (normalized.isAbsolute()) {
            throw new IllegalArgumentException("只能使用相对路径");
        }
        return normalized.toString().replace('\\', '/');
    }

    private void requireYaml(String composeFilePath) {
        var lowerCasePath = composeFilePath.toLowerCase();
        if (!lowerCasePath.endsWith(".yml") && !lowerCasePath.endsWith(".yaml")) {
            throw new IllegalArgumentException("Compose 文件必须使用 .yml 或 .yaml 后缀");
        }
    }

    private String requirePattern(String value, Pattern pattern, String message) {
        var text = requireText(value, message);
        if (!pattern.matcher(text).matches()) {
            throw new IllegalArgumentException(message);
        }
        return text;
    }

    private String requireText(String value, String message) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(message);
        }
        return value.trim();
    }
}
