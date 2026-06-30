package com.matrixcode.localexecution.application;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.nio.file.Path;

@Component
@ConfigurationProperties(prefix = "matrixcode.local-execution")
public class LocalExecutionStorageProperties {

    private Path storagePath = Path.of(".matrixcode/local-execution.json");

    public Path getStoragePath() {
        return storagePath;
    }

    public void setStoragePath(Path storagePath) {
        if (storagePath != null) {
            this.storagePath = storagePath;
        }
    }
}
