package com.matrixcode.persistence.application;

import org.flywaydb.core.Flyway;

import java.sql.SQLException;

final class DatabaseMigrator {

    static final String MIGRATION_LOCATION = "classpath:db/migration";
    private static final int MAX_ATTEMPTS = 3;
    private static final long RETRY_DELAY_MILLIS = 300L;

    private DatabaseMigrator() {
    }

    static void migrate(String url, String username, String password) {
        migrate(url, username, password, false);
    }

    static void migrate(String url, String username, String password, boolean createDatabaseIfMissing) {
        migrate(url, username, password, createDatabaseIfMissing, DatabaseMigrator::runMigration, DatabaseMigrator::sleep);
    }

    static void migrate(
            String url,
            String username,
            String password,
            boolean createDatabaseIfMissing,
            MigrationExecutor migrationExecutor,
            RetrySleeper sleeper
    ) {
        if (url == null || url.isBlank()) {
            throw new IllegalStateException("JDBC 迁移 URL 不能为空");
        }

        RuntimeException lastFailure = null;
        for (var attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
            try {
                migrationExecutor.migrate(url, username, password, createDatabaseIfMissing);
                return;
            } catch (RuntimeException exception) {
                lastFailure = exception;
                if (!isCommunicationFailure(exception) || attempt == MAX_ATTEMPTS) {
                    throw migrationFailure(exception);
                }
                pauseBeforeRetry(sleeper, attempt, exception);
            }
        }
        throw migrationFailure(lastFailure);
    }

    /**
     * 执行一次数据库迁移；由外层重试器负责处理远程 MySQL 偶发通信异常。
     */
    private static void runMigration(String url, String username, String password, boolean createDatabaseIfMissing) {
        if (createDatabaseIfMissing) {
            new JdbcDatabaseBootstrapper().createDatabaseIfRequired(url, username, password);
        }
        Flyway.configure()
                .dataSource(url, username, password)
                .locations(MIGRATION_LOCATION)
                .load()
                .migrate();
    }

    private static boolean isCommunicationFailure(Throwable exception) {
        var current = exception;
        while (current != null) {
            if (current instanceof SQLException sqlException) {
                var sqlState = sqlException.getSQLState();
                if (sqlState != null && sqlState.startsWith("08")) {
                    return true;
                }
            }
            var message = current.getMessage();
            if (message != null && message.contains("Communications link failure")) {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }

    private static void pauseBeforeRetry(RetrySleeper sleeper, int attempt, RuntimeException failure) {
        try {
            sleeper.sleep(RETRY_DELAY_MILLIS * attempt);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            var retryInterrupted = new IllegalStateException("数据库迁移重试被中断", interrupted);
            retryInterrupted.addSuppressed(failure);
            throw retryInterrupted;
        }
    }

    private static IllegalStateException migrationFailure(RuntimeException exception) {
        return new IllegalStateException("数据库迁移失败：" + exception.getMessage(), exception);
    }

    private static void sleep(long millis) throws InterruptedException {
        Thread.sleep(millis);
    }

    @FunctionalInterface
    interface MigrationExecutor {
        void migrate(String url, String username, String password, boolean createDatabaseIfMissing);
    }

    @FunctionalInterface
    interface RetrySleeper {
        void sleep(long millis) throws InterruptedException;
    }
}
