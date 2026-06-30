package com.matrixcode.persistence.application;

import org.springframework.stereotype.Service;

@Service
public class DatabaseMigrationService {

    private final PersistenceModeProperties properties;

    public DatabaseMigrationService(PersistenceModeProperties properties) {
        this.properties = properties;
    }

    public void migrate() {
        var jdbc = properties.getJdbc();
        DatabaseMigrator.migrate(
                jdbc.getUrl(),
                jdbc.getUsername(),
                jdbc.getPassword(),
                jdbc.isCreateDatabaseIfMissing()
        );
    }
}
