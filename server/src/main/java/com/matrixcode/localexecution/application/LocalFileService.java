package com.matrixcode.localexecution.application;

import com.matrixcode.localexecution.domain.DirectoryEntry;
import com.matrixcode.localexecution.domain.FileOperationRecord;
import com.matrixcode.localexecution.domain.FileOperationType;
import com.matrixcode.localexecution.domain.FileReadResult;
import com.matrixcode.localexecution.domain.FileWriteResult;
import com.matrixcode.workbench.application.InMemoryWorkbenchStateStore;
import com.matrixcode.workbench.application.WorkbenchStateStore;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class LocalFileService {

    private static final long READ_LIMIT_BYTES = 64 * 1024;
    private static final long WRITE_LIMIT_BYTES = 128 * 1024;
    private static final int BINARY_SAMPLE_BYTES = 1024;
    private static final int HISTORY_LIMIT = 20;

    private final WorkspaceRegistry workspaces;
    private final PathGuard pathGuard;
    private final Map<String, ArrayDeque<FileOperationRecord>> operations = new ConcurrentHashMap<>();
    private final WorkbenchStateStore stateStore;
    private final Optional<LocalWorkspaceActivityRepository> activityRepository;

    public LocalFileService(WorkspaceRegistry workspaces, PathGuard pathGuard) {
        this(workspaces, pathGuard, new InMemoryWorkbenchStateStore(), Optional.empty());
    }

    public LocalFileService(WorkspaceRegistry workspaces, PathGuard pathGuard, WorkbenchStateStore stateStore) {
        this(workspaces, pathGuard, stateStore, Optional.empty());
    }

    @Autowired
    public LocalFileService(
            WorkspaceRegistry workspaces,
            PathGuard pathGuard,
            WorkbenchStateStore stateStore,
            Optional<LocalWorkspaceActivityRepository> activityRepository
    ) {
        this.workspaces = workspaces;
        this.pathGuard = pathGuard;
        this.stateStore = stateStore;
        this.activityRepository = activityRepository == null ? Optional.empty() : activityRepository;
        restoreOperations()
                .forEach((projectId, records) -> operations.put(projectId, new ArrayDeque<>(records)));
    }

    public List<DirectoryEntry> list(String projectId, String workspaceId, String relativePath) {
        var workspace = workspaces.requireAuthorized(projectId, workspaceId);
        var directory = pathGuard.resolveExisting(workspace.rootPath(), relativePath);
        if (!Files.isDirectory(directory)) {
            throw new IllegalArgumentException("目标路径不是目录");
        }
        try (var stream = Files.list(directory)) {
            var root = Path.of(workspace.rootPath()).toRealPath();
            var entries = stream
                    .sorted(Comparator.comparing(path -> path.getFileName().toString()))
                    .map(path -> toEntry(root, path))
                    .toList();
            record(projectId, workspaceId, FileOperationType.LIST, relativePath, "SUCCESS",
                    "列出目录 %s，共 %d 项".formatted(relativePath, entries.size()));
            return entries;
        } catch (IOException exception) {
            throw new IllegalArgumentException("目录无法读取");
        }
    }

    public FileReadResult read(String projectId, String workspaceId, String relativePath) {
        var workspace = workspaces.requireAuthorized(projectId, workspaceId);
        var file = pathGuard.resolveExisting(workspace.rootPath(), relativePath);
        if (!Files.isRegularFile(file)) {
            throw new IllegalArgumentException("目标路径不是文件");
        }
        try {
            var size = Files.size(file);
            if (size > READ_LIMIT_BYTES) {
                throw new IllegalArgumentException("文件超过读取上限");
            }
            var bytes = Files.readAllBytes(file);
            if (looksBinary(bytes)) {
                throw new IllegalArgumentException("不支持读取二进制文件");
            }
            var content = new String(bytes, StandardCharsets.UTF_8);
            record(projectId, workspaceId, FileOperationType.READ, relativePath, "SUCCESS",
                    "读取文件 %s，%d 字节".formatted(relativePath, size));
            return new FileReadResult(workspaceId, relativePath, content, size);
        } catch (IOException exception) {
            throw new IllegalArgumentException("文件无法读取");
        }
    }

    public FileWriteResult write(String projectId, String workspaceId, String relativePath, String content) {
        var workspace = workspaces.requireAuthorized(projectId, workspaceId);
        if (content == null) {
            throw new IllegalArgumentException("写入内容不能为空");
        }
        var bytes = content.getBytes(StandardCharsets.UTF_8);
        if (bytes.length > WRITE_LIMIT_BYTES) {
            throw new IllegalArgumentException("文件超过写入上限");
        }
        var file = pathGuard.resolveWritable(workspace.rootPath(), relativePath);
        try {
            Files.writeString(file, content, StandardCharsets.UTF_8);
            record(projectId, workspaceId, FileOperationType.WRITE, relativePath, "SUCCESS",
                    "写入文件 %s，%d 字节".formatted(relativePath, bytes.length));
            return new FileWriteResult(workspaceId, relativePath, bytes.length);
        } catch (IOException exception) {
            throw new IllegalArgumentException("文件无法写入");
        }
    }

    public List<FileOperationRecord> recentOperations(String projectId) {
        var records = operations.getOrDefault(projectId, new ArrayDeque<>());
        return List.copyOf(records);
    }

    private DirectoryEntry toEntry(Path root, Path path) {
        try {
            var relative = root.relativize(path.toAbsolutePath().normalize()).toString();
            return new DirectoryEntry(path.getFileName().toString(), relative, Files.isDirectory(path),
                    Files.isDirectory(path) ? 0 : Files.size(path));
        } catch (IOException exception) {
            throw new IllegalArgumentException("目录项无法读取");
        }
    }

    private boolean looksBinary(byte[] bytes) {
        var sampleSize = Math.min(bytes.length, BINARY_SAMPLE_BYTES);
        for (var index = 0; index < sampleSize; index++) {
            if (bytes[index] == 0) {
                return true;
            }
        }
        return false;
    }

    private void record(
            String projectId,
            String workspaceId,
            FileOperationType type,
            String relativePath,
            String status,
            String summary
    ) {
        var records = operations.computeIfAbsent(projectId, ignored -> new ArrayDeque<>());
        records.addFirst(new FileOperationRecord(
                UUID.randomUUID().toString(),
                projectId,
                workspaceId,
                type,
                relativePath,
                status,
                summary,
                Instant.now()
        ));
        while (records.size() > HISTORY_LIMIT) {
            records.removeLast();
        }
        saveOperations();
    }

    private Map<String, List<FileOperationRecord>> snapshot() {
        var snapshot = new java.util.HashMap<String, List<FileOperationRecord>>();
        operations.forEach((projectId, records) -> snapshot.put(projectId, List.copyOf(records)));
        return snapshot;
    }

    /**
     * 加载最近文件操作记录。
     *
     * <p>JDBC 模式优先读取正式表；正式表为空时读取旧 `workbench-state.fileOperations`
     * 并回填正式表。文件模式不提供正式仓储，继续使用原快照存储。</p>
     */
    private Map<String, List<FileOperationRecord>> restoreOperations() {
        if (activityRepository.isEmpty()) {
            return stateStore.load().fileOperations();
        }
        var formal = activityRepository.get().loadFileOperations();
        if (!formal.isEmpty()) {
            return formal;
        }
        var legacy = stateStore.load().fileOperations();
        if (!legacy.isEmpty()) {
            activityRepository.get().saveFileOperations(legacy);
        }
        return legacy;
    }

    /**
     * 保存最近文件操作记录。
     *
     * <p>JDBC 模式只写正式表，避免继续扩大 `workbench-state` 聚合快照；文件模式保留原行为。</p>
     */
    private void saveOperations() {
        var snapshot = snapshot();
        if (activityRepository.isPresent()) {
            activityRepository.get().saveFileOperations(snapshot);
            return;
        }
        stateStore.saveFileOperations(snapshot);
    }
}
