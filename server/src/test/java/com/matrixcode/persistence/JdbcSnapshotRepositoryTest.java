package com.matrixcode.persistence;

import db.migration.V176_2__migrate_snapshot_table_to_snowflake_id;
import com.matrixcode.persistence.application.PersistenceModeProperties;
import com.matrixcode.persistence.application.JdbcSnapshotRepository;
import org.flywaydb.core.api.configuration.Configuration;
import org.flywaydb.core.api.migration.Context;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

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
    void 自动建表并按切片覆盖保存快照() throws Exception {
        var properties = properties("matrixcode_state_" + System.nanoTime());
        var repository = new JdbcSnapshotRepository(properties);

        repository.save("runtime-notifications", 1, "{\"version\":1,\"value\":\"first\"}");
        repository.save("runtime-notifications", 1, "{\"version\":1,\"value\":\"second\"}");

        var snapshot = repository.load("runtime-notifications");

        assertThat(snapshot).isPresent();
        assertThat(snapshot.get().sliceKey()).isEqualTo("runtime-notifications");
        assertThat(snapshot.get().version()).isEqualTo(1);
        assertThat(snapshot.get().payload()).contains("second");

        try (var connection = DriverManager.getConnection(
                properties.getJdbc().getUrl(),
                properties.getJdbc().getUsername(),
                properties.getJdbc().getPassword()
        )) {
            assertThat(primaryKeyColumns(connection, properties.validatedTableName()))
                    .containsExactly("id");
            assertThat(snapshotId(connection, properties.validatedTableName(), "runtime-notifications"))
                    .isNotBlank()
                    .isNotEqualTo("runtime-notifications");
        }
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
            assertThat(columnComment(connection, properties.validatedTableName(), "id"))
                    .isEqualTo("快照记录 ID，由 MyBatis-Plus 雪花算法生成，用于表级主键。");
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

    @Test
    void 迁移历史快照表为雪花Id主键并保留切片唯一语义() throws Exception {
        var properties = properties("matrixcode_state_legacy_" + System.nanoTime());
        var tableName = properties.validatedTableName();
        try (var connection = DriverManager.getConnection(
                properties.getJdbc().getUrl(),
                properties.getJdbc().getUsername(),
                properties.getJdbc().getPassword()
        )) {
            try (var statement = connection.createStatement()) {
                statement.execute("create table " + tableName + " ("
                        + "slice_key varchar(80) primary key, "
                        + "version integer not null, "
                        + "payload text not null, "
                        + "updated_at timestamp not null)");
                statement.execute("insert into " + tableName
                        + " (slice_key, version, payload, updated_at) values "
                        + "('workbench-state', 1, '{\"version\":1}', CURRENT_TIMESTAMP)");
            }

            new V176_2__migrate_snapshot_table_to_snowflake_id().migrate(context(connection));

            assertThat(primaryKeyColumns(connection, tableName)).containsExactly("id");
            assertThat(snapshotId(connection, tableName, "workbench-state"))
                    .isNotBlank()
                    .isNotEqualTo("workbench-state");
        }

        var repository = new JdbcSnapshotRepository(properties);
        assertThat(repository.load("workbench-state"))
                .hasValueSatisfying(snapshot -> assertThat(snapshot.payload()).contains("version"));
        repository.save("workbench-state", 2, "{\"version\":2}");
        assertThat(repository.load("workbench-state"))
                .hasValueSatisfying(snapshot -> assertThat(snapshot.version()).isEqualTo(2));
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

    private List<String> primaryKeyColumns(Connection connection, String tableName) throws SQLException {
        var columns = new ArrayList<String>();
        try (var statement = connection.prepareStatement("""
                select lower(kcu.column_name)
                from information_schema.table_constraints tc
                join information_schema.key_column_usage kcu
                    on tc.constraint_name = kcu.constraint_name
                    and tc.table_schema = kcu.table_schema
                    and tc.table_name = kcu.table_name
                where lower(tc.table_name) = ? and tc.constraint_type = 'PRIMARY KEY'
                order by kcu.ordinal_position
                """)) {
            statement.setString(1, tableName);
            try (var resultSet = statement.executeQuery()) {
                while (resultSet.next()) {
                    columns.add(resultSet.getString(1));
                }
            }
        }
        return columns;
    }

    private String snapshotId(Connection connection, String tableName, String sliceKey) throws SQLException {
        try (var statement = connection.prepareStatement("select id from " + tableName + " where slice_key = ?")) {
            statement.setString(1, sliceKey);
            try (var resultSet = statement.executeQuery()) {
                assertThat(resultSet.next()).isTrue();
                return resultSet.getString(1);
            }
        }
    }

    private Context context(Connection connection) {
        return new Context() {
            @Override
            public Configuration getConfiguration() {
                return null;
            }

            @Override
            public Connection getConnection() {
                return connection;
            }
        };
    }
}
