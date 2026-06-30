package com.matrixcode.localexecution.application;

import com.matrixcode.localexecution.domain.WorkspaceAuthorization;
import com.matrixcode.localexecution.domain.WorkspaceStatus;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class WorkspaceRegistry {

    private final Map<String, WorkspaceAuthorization> workspaces = new ConcurrentHashMap<>();
    private final LocalExecutionStateStore store;

    public WorkspaceRegistry() {
        this(new InMemoryLocalExecutionStateStore());
    }

    @Autowired
    public WorkspaceRegistry(LocalExecutionStateStore store) {
        this.store = store;
        restore(store.load().workspaces());
    }

    public WorkspaceAuthorization authorize(String projectId, String name, String rootPath) {
        projectId = requireText(projectId, "项目编号不能为空");
        name = requireText(name, "工作区名称不能为空");
        rootPath = requireText(rootPath, "工作区路径不能为空");
        var root = Path.of(rootPath).toAbsolutePath().normalize();
        if (!Files.isDirectory(root)) {
            throw new IllegalArgumentException("工作区路径必须是已存在目录");
        }
        var now = Instant.now();
        var workspace = new WorkspaceAuthorization(
                UUID.randomUUID().toString(),
                projectId,
                name,
                root.toString(),
                WorkspaceStatus.AUTHORIZED,
                now,
                now
        );
        workspaces.put(workspace.id(), workspace);
        persist();
        return workspace;
    }

    public List<WorkspaceAuthorization> list(String projectId) {
        projectId = requireText(projectId, "项目编号不能为空");
        var targetProject = projectId;
        return workspaces.values().stream()
                .filter(workspace -> workspace.projectId().equals(targetProject))
                .sorted(Comparator.comparing(WorkspaceAuthorization::createdAt))
                .toList();
    }

    public WorkspaceAuthorization requireAuthorized(String projectId, String workspaceId) {
        projectId = requireText(projectId, "项目编号不能为空");
        workspaceId = requireText(workspaceId, "工作区编号不能为空");
        var workspace = workspaces.get(workspaceId);
        if (workspace == null || !workspace.projectId().equals(projectId)
                || workspace.status() != WorkspaceStatus.AUTHORIZED) {
            throw new IllegalArgumentException("工作区未授权");
        }
        var accessed = workspace.accessedNow();
        workspaces.put(accessed.id(), accessed);
        persist();
        return accessed;
    }

    public WorkspaceAuthorization revoke(String projectId, String workspaceId) {
        var workspace = requireAuthorized(projectId, workspaceId);
        var revoked = workspace.withStatus(WorkspaceStatus.REVOKED);
        workspaces.put(revoked.id(), revoked);
        persist();
        return revoked;
    }

    private void restore(List<WorkspaceAuthorization> restoredWorkspaces) {
        restoredWorkspaces.forEach(workspace -> workspaces.put(workspace.id(), workspace));
    }

    private void persist() {
        store.saveWorkspaces(List.copyOf(workspaces.values()));
    }

    private static String requireText(String value, String message) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(message);
        }
        return value.trim();
    }
}
