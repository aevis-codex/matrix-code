package com.matrixcode.persistence;

import com.matrixcode.persistence.application.DatabaseMigrationRunner;
import com.matrixcode.persistence.application.DatabaseMigrationService;
import com.matrixcode.persistence.application.PersistenceModeProperties;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class DatabaseMigrationRunnerTest {

    @Test
    void 文件模式不执行数据库迁移() throws Exception {
        var properties = new PersistenceModeProperties();
        var migrationService = new CountingMigrationService();
        var runner = new DatabaseMigrationRunner(properties, migrationService);

        runner.run(null);

        assertThat(migrationService.calls()).isZero();
    }

    @Test
    void Jdbc模式未开启启动迁移时不执行数据库迁移() throws Exception {
        var properties = new PersistenceModeProperties();
        properties.setMode("jdbc");
        properties.getJdbc().setMigrateOnStartup(false);
        var migrationService = new CountingMigrationService();
        var runner = new DatabaseMigrationRunner(properties, migrationService);

        runner.run(null);

        assertThat(migrationService.calls()).isZero();
    }

    @Test
    void Jdbc模式开启启动迁移时执行一次数据库迁移() throws Exception {
        var properties = new PersistenceModeProperties();
        properties.setMode("jdbc");
        properties.getJdbc().setMigrateOnStartup(true);
        var migrationService = new CountingMigrationService();
        var runner = new DatabaseMigrationRunner(properties, migrationService);

        runner.run(null);

        assertThat(migrationService.calls()).isEqualTo(1);
    }

    private static final class CountingMigrationService extends DatabaseMigrationService {

        private int calls;

        private CountingMigrationService() {
            super(new PersistenceModeProperties());
        }

        @Override
        public void migrate() {
            calls++;
        }

        private int calls() {
            return calls;
        }
    }
}
