package com.matrixcode.runtime.application;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.nio.file.Path;

@Component
@ConfigurationProperties(prefix = "matrixcode.runtime-notifications")
public class RuntimeNotificationStorageProperties {

    private Path storagePath = Path.of(".matrixcode/runtime-notifications.json");

    public Path getStoragePath() {
        return storagePath;
    }

    public void setStoragePath(Path storagePath) {
        if (storagePath != null) {
            this.storagePath = storagePath;
        }
    }
}
