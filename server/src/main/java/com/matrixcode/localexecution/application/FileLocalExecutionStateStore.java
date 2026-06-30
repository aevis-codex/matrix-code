package com.matrixcode.localexecution.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.matrixcode.approval.domain.AuditRecord;
import com.matrixcode.localexecution.domain.ExecutionTask;
import com.matrixcode.localexecution.domain.LocalTaskLog;
import com.matrixcode.localexecution.domain.WorkspaceAuthorization;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;
import java.util.Map;

@Service
@ConditionalOnProperty(prefix = "matrixcode.persistence", name = "mode", havingValue = "file", matchIfMissing = true)
public class FileLocalExecutionStateStore implements LocalExecutionStateStore {

    private final ObjectMapper objectMapper;
    private final LocalExecutionStorageProperties properties;
    private LocalExecutionSnapshot current;

    public FileLocalExecutionStateStore(ObjectMapper objectMapper, LocalExecutionStorageProperties properties) {
        this.objectMapper = objectMapper;
        this.properties = properties;
        this.current = readSnapshot();
    }

    @Override
    public synchronized LocalExecutionSnapshot load() {
        return current;
    }

    @Override
    public synchronized void saveWorkspaces(List<WorkspaceAuthorization> workspaces) {
        current = new LocalExecutionSnapshot(1, workspaces, current.tasks(), current.taskLogs(), current.auditRecords());
        writeSnapshot();
    }

    @Override
    public synchronized void saveTasks(
            Map<String, List<ExecutionTask>> tasks,
            Map<String, Map<String, List<LocalTaskLog>>> taskLogs
    ) {
        current = new LocalExecutionSnapshot(1, current.workspaces(), tasks, taskLogs, current.auditRecords());
        writeSnapshot();
    }

    @Override
    public synchronized void saveAuditRecords(List<AuditRecord> auditRecords) {
        current = new LocalExecutionSnapshot(1, current.workspaces(), current.tasks(), current.taskLogs(), auditRecords);
        writeSnapshot();
    }

    private LocalExecutionSnapshot readSnapshot() {
        var path = storagePath();
        if (!Files.exists(path)) {
            return LocalExecutionSnapshot.empty();
        }
        try {
            var snapshot = objectMapper.readValue(path.toFile(), LocalExecutionSnapshot.class);
            if (snapshot.version() != 1) {
                return LocalExecutionSnapshot.empty();
            }
            return snapshot;
        } catch (IOException | RuntimeException ignored) {
            return LocalExecutionSnapshot.empty();
        }
    }

    private void writeSnapshot() {
        var path = storagePath();
        try {
            var parent = path.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            var tempFile = parent == null
                    ? Files.createTempFile("local-execution-", ".tmp")
                    : Files.createTempFile(parent, "local-execution-", ".tmp");
            objectMapper.writeValue(tempFile.toFile(), current);
            try {
                Files.move(tempFile, path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (IOException atomicMoveFailure) {
                Files.move(tempFile, path, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException exception) {
            throw new IllegalStateException("本地执行状态存储写入失败：" + exception.getMessage(), exception);
        }
    }

    private Path storagePath() {
        return properties.getStoragePath().toAbsolutePath().normalize();
    }
}
