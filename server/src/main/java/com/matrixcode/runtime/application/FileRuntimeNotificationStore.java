package com.matrixcode.runtime.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

@Service
@ConditionalOnProperty(prefix = "matrixcode.persistence", name = "mode", havingValue = "file", matchIfMissing = true)
public class FileRuntimeNotificationStore implements RuntimeNotificationStore {

    private final ObjectMapper objectMapper;
    private final RuntimeNotificationStorageProperties properties;

    public FileRuntimeNotificationStore(ObjectMapper objectMapper, RuntimeNotificationStorageProperties properties) {
        this.objectMapper = objectMapper;
        this.properties = properties;
    }

    @Override
    public RuntimeNotificationSnapshot load() {
        var path = storagePath();
        if (!Files.exists(path)) {
            return RuntimeNotificationSnapshot.empty();
        }
        try {
            var snapshot = objectMapper.readValue(path.toFile(), RuntimeNotificationSnapshot.class);
            if (snapshot.version() != 1) {
                return RuntimeNotificationSnapshot.empty();
            }
            return snapshot;
        } catch (IOException | RuntimeException ignored) {
            return RuntimeNotificationSnapshot.empty();
        }
    }

    @Override
    public void save(RuntimeNotificationSnapshot snapshot) {
        var path = storagePath();
        try {
            var parent = path.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            var tempFile = parent == null
                    ? Files.createTempFile("runtime-notifications-", ".tmp")
                    : Files.createTempFile(parent, "runtime-notifications-", ".tmp");
            objectMapper.writeValue(tempFile.toFile(), snapshot);
            try {
                Files.move(tempFile, path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (IOException atomicMoveFailure) {
                Files.move(tempFile, path, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException exception) {
            throw new IllegalStateException("运行态提醒存储写入失败：" + exception.getMessage(), exception);
        }
    }

    private Path storagePath() {
        return properties.getStoragePath().toAbsolutePath().normalize();
    }
}
