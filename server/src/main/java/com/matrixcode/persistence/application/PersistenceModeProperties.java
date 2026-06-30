package com.matrixcode.persistence.application;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Component
@ConfigurationProperties(prefix = "matrixcode.persistence")
public class PersistenceModeProperties {

    private String mode = "file";
    private Jdbc jdbc = new Jdbc();

    public String getMode() {
        return mode;
    }

    public void setMode(String mode) {
        this.mode = mode == null || mode.isBlank() ? "file" : mode.trim();
    }

    public Jdbc getJdbc() {
        return jdbc;
    }

    public void setJdbc(Jdbc jdbc) {
        this.jdbc = jdbc == null ? new Jdbc() : jdbc;
    }

    public String validatedTableName() {
        var tableName = jdbc.getTableName();
        if (!tableName.matches("[A-Za-z0-9_]+")) {
            throw new IllegalArgumentException("快照表名只能包含字母、数字和下划线");
        }
        return tableName;
    }

    public static class Jdbc {

        private String url = "";
        private String username = "";
        private String password = "";
        private String tableName = "matrixcode_state_snapshots";
        private boolean migrateOnStartup = false;
        private boolean createDatabaseIfMissing = false;
        /**
         * Controls the historical workbench snapshot writer. The production
         * JDBC path now uses typed MyBatis-Plus tables, so this remains false
         * unless an operator explicitly needs a short-lived legacy rollback.
         */
        private boolean legacySnapshotWritesEnabled = false;

        public String getUrl() {
            return url;
        }

        public void setUrl(String url) {
            this.url = url == null ? "" : url.trim();
        }

        public String getUsername() {
            return username;
        }

        public void setUsername(String username) {
            this.username = username == null ? "" : username.trim();
        }

        public String getPassword() {
            return password;
        }

        public void setPassword(String password) {
            this.password = password == null ? "" : password;
        }

        public String getTableName() {
            return tableName;
        }

        public void setTableName(String tableName) {
            this.tableName = tableName == null || tableName.isBlank()
                    ? "matrixcode_state_snapshots"
                    : tableName.trim();
        }

        public boolean isMigrateOnStartup() {
            return migrateOnStartup;
        }

        public void setMigrateOnStartup(boolean migrateOnStartup) {
            this.migrateOnStartup = migrateOnStartup;
        }

        public boolean isCreateDatabaseIfMissing() {
            return createDatabaseIfMissing;
        }

        public void setCreateDatabaseIfMissing(boolean createDatabaseIfMissing) {
            this.createDatabaseIfMissing = createDatabaseIfMissing;
        }

        public boolean isLegacySnapshotWritesEnabled() {
            return legacySnapshotWritesEnabled;
        }

        public void setLegacySnapshotWritesEnabled(boolean legacySnapshotWritesEnabled) {
            this.legacySnapshotWritesEnabled = legacySnapshotWritesEnabled;
        }
    }
}
