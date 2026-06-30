package com.matrixcode.persistence;

import com.matrixcode.persistence.application.PersistenceModeProperties;
import com.matrixcode.persistence.application.JdbcSnapshotRepository;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class JdbcSnapshotRepositoryTest {

    @Test
    void 拒绝不安全的快照表名() {
        var properties = new PersistenceModeProperties();
        properties.getJdbc().setTableName("matrixcode_state_snapshots;drop table x");

        assertThatThrownBy(properties::validatedTableName)
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("快照表名只能包含字母、数字和下划线");
    }

    @Test
    void 自动建表并按切片覆盖保存快照() {
        var repository = new JdbcSnapshotRepository(properties("matrixcode_state_" + System.nanoTime()));

        repository.save("runtime-notifications", 1, "{\"version\":1,\"value\":\"first\"}");
        repository.save("runtime-notifications", 1, "{\"version\":1,\"value\":\"second\"}");

        var snapshot = repository.load("runtime-notifications");

        assertThat(snapshot).isPresent();
        assertThat(snapshot.get().sliceKey()).isEqualTo("runtime-notifications");
        assertThat(snapshot.get().version()).isEqualTo(1);
        assertThat(snapshot.get().payload()).contains("second");
    }

    @Test
    void 自动建表时写入表和字段注释() throws Exception {
        var properties = properties("matrixcode_state_comments_" + System.nanoTime());
        var repository = new JdbcSnapshotRepository(properties);

        repository.save("workbench-state", 1, "{\"version\":1,\"value\":\"demo\"}");

        try (var connection = DriverManager.getConnection(
                properties.getJdbc().getUrl(),
                properties.getJdbc().getUsername(),
                properties.getJdbc().getPassword()
        )) {
            assertThat(tableComment(connection, properties.validatedTableName()))
                    .isEqualTo("保存工作台 JSON 快照过渡数据，用于在领域表迁移期间保留尚未迁出的状态切片。");
            assertThat(columnComment(connection, properties.validatedTableName(), "slice_key"))
                    .isEqualTo("快照切片键，例如 workbench-state，用于区分不同状态投影。");
            assertThat(columnComment(connection, properties.validatedTableName(), "version"))
                    .isEqualTo("快照结构版本号，用于读取时做兼容判断和迁移。");
            assertThat(columnComment(connection, properties.validatedTableName(), "payload"))
                    .isEqualTo("快照 JSON 正文，保存该切片的完整序列化状态。");
            assertThat(columnComment(connection, properties.validatedTableName(), "updated_at"))
                    .isEqualTo("快照最后写入时间，用于排查状态恢复和同步问题。");
        }
    }

    private PersistenceModeProperties properties(String databaseName) {
        var properties = new PersistenceModeProperties();
        properties.setMode("jdbc");
        properties.getJdbc().setUrl("jdbc:h2:mem:" + databaseName
                + ";MODE=MySQL;DATABASE_TO_LOWER=TRUE;CASE_INSENSITIVE_IDENTIFIERS=TRUE;DB_CLOSE_DELAY=-1");
        properties.getJdbc().setUsername("sa");
        properties.getJdbc().setPassword("");
        return properties;
    }

    private String tableComment(Connection connection, String tableName) throws SQLException {
        try (var statement = connection.prepareStatement("""
                select remarks
                from information_schema.tables
                where lower(table_name) = ?
                """)) {
            statement.setString(1, tableName);
            try (var resultSet = statement.executeQuery()) {
                assertThat(resultSet.next()).as(tableName).isTrue();
                return resultSet.getString(1);
            }
        }
    }

    private String columnComment(Connection connection, String tableName, String columnName) throws SQLException {
        try (var statement = connection.prepareStatement("""
                select remarks
                from information_schema.columns
                where lower(table_name) = ? and lower(column_name) = ?
                """)) {
            statement.setString(1, tableName);
            statement.setString(2, columnName);
            try (var resultSet = statement.executeQuery()) {
                assertThat(resultSet.next()).as(tableName + "." + columnName).isTrue();
                return resultSet.getString(1);
            }
        }
    }
}
