package com.matrixcode.persistence.application;

import org.junit.jupiter.api.Test;

import java.sql.SQLException;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DatabaseMigratorTest {

    @Test
    void 通信异常会短暂重试并返回后续成功迁移() {
        var attempts = new AtomicInteger();

        DatabaseMigrator.migrate(
                "jdbc:test://localhost/matrix_code",
                "matrix",
                "secret",
                false,
                (url, username, password, createDatabaseIfMissing) -> {
                    if (attempts.incrementAndGet() < 3) {
                        throw new RuntimeException("Communications link failure", new SQLException("EOF", "08S01"));
                    }
                },
                ignored -> {
                }
        );

        assertThat(attempts).hasValue(3);
    }

    @Test
    void 非通信异常不会重试避免掩盖迁移错误() {
        var attempts = new AtomicInteger();

        assertThatThrownBy(() -> DatabaseMigrator.migrate(
                "jdbc:test://localhost/matrix_code",
                "matrix",
                "secret",
                false,
                (url, username, password, createDatabaseIfMissing) -> {
                    attempts.incrementAndGet();
                    throw new RuntimeException("SQL 语法错误", new SQLException("bad sql", "42000"));
                },
                ignored -> {
                }
        ))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("数据库迁移失败");
        assertThat(attempts).hasValue(1);
    }
}
