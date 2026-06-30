package com.matrixcode.workbench.application;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.nio.file.Path;

@Component
@ConfigurationProperties(prefix = "matrixcode.workbench-state")
public class WorkbenchStateStorageProperties {

    private Path storagePath = Path.of(".matrixcode/workbench-state.json");

    public Path getStoragePath() {
        return storagePath;
    }

    public void setStoragePath(Path storagePath) {
        if (storagePath != null) {
            this.storagePath = storagePath;
        }
    }
}
