package com.matrixcode.persistence;

import com.matrixcode.persistence.application.DatabaseMigrationService;
import com.matrixcode.persistence.application.PersistenceModeProperties;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DatabaseMigrationServiceTest {

    @Test
    void Jdbc迁移开启时要求JdbcUrl不能为空() {
        var properties = new PersistenceModeProperties();
        properties.setMode("jdbc");
        properties.getJdbc().setMigrateOnStartup(true);

        var service = new DatabaseMigrationService(properties);

        assertThatThrownBy(service::migrate)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("JDBC 迁移 URL 不能为空");
    }

    @Test
    void 执行MySql兼容迁移时创建核心领域表() throws Exception {
        var databaseName = "migration_" + UUID.randomUUID().toString().replace("-", "");
        var url = "jdbc:h2:mem:" + databaseName + ";MODE=MySQL;DATABASE_TO_LOWER=TRUE;CASE_INSENSITIVE_IDENTIFIERS=TRUE;DB_CLOSE_DELAY=-1";
        var properties = new PersistenceModeProperties();
        properties.setMode("jdbc");
        properties.getJdbc().setUrl(url);
        properties.getJdbc().setUsername("sa");
        properties.getJdbc().setPassword("");
        var service = new DatabaseMigrationService(properties);

        service.migrate();
        service.migrate();

        try (var connection = DriverManager.getConnection(url, "sa", "")) {
            assertThat(List.of(
                    "flyway_schema_history",
                    "matrixcode_users",
                    "matrixcode_projects",
                    "matrixcode_project_members",
                    "matrixcode_role_agent_configs",
                    "matrixcode_role_model_bindings",
                    "matrixcode_collaboration_sessions",
                    "matrixcode_collaboration_participants",
                    "matrixcode_documents",
                    "matrixcode_bugs",
                    "matrixcode_deployment_targets",
                    "matrixcode_runtime_notifications",
                    "matrixcode_local_execution_tasks",
                    "matrixcode_local_file_operations",
                    "matrixcode_local_git_diff_summaries",
                    "matrixcode_audit_records",
                    "matrixcode_project_events",
                    "matrixcode_workflow_items",
                    "matrixcode_workflow_events",
                    "matrixcode_acceptance_states",
                    "matrixcode_agent_runs",
                    "matrixcode_agent_run_events"
            )).allSatisfy(tableName -> assertThat(tableExists(connection, tableName))
                    .as(tableName)
                    .isTrue());

            assertThat(tableComment(connection, "matrixcode_agent_runs"))
                    .isEqualTo("记录每一次角色智能体运行的主状态、操作者、模型和目标。");
            assertThat(columnComment(connection, "matrixcode_agent_runs", "actor_user_id"))
                    .isEqualTo("触发本次智能体运行的用户 ID，用于责任归属和审计查询。");
            assertThat(datetimePrecision(connection, "matrixcode_agent_runs", "created_at"))
                    .isEqualTo(6);
            assertThat(datetimePrecision(connection, "matrixcode_agent_runs", "started_at"))
                    .isEqualTo(6);
            assertThat(datetimePrecision(connection, "matrixcode_agent_runs", "finished_at"))
                    .isEqualTo(6);
            assertThat(datetimePrecision(connection, "matrixcode_agent_runs", "updated_at"))
                    .isEqualTo(6);
            assertThat(tableComment(connection, "matrixcode_agent_run_events"))
                    .isEqualTo("记录智能体运行过程中的步骤事件、工具调用摘要和错误摘要。");
            assertThat(columnComment(connection, "matrixcode_agent_run_events", "event_payload"))
                    .isEqualTo("事件结构化内容的 JSON 文本，保存工具输入输出摘要、错误体摘要或阶段结果。");
            assertThat(datetimePrecision(connection, "matrixcode_agent_run_events", "occurred_at"))
                    .isEqualTo(6);
            assertAllMatrixCodeTablesAndColumnsHaveComments(connection);
        }
    }

    private boolean tableExists(Connection connection, String tableName) throws SQLException {
        try (var statement = connection.prepareStatement("""
                select count(*)
                from information_schema.tables
                where lower(table_name) = ?
                """)) {
            statement.setString(1, tableName);
            try (var resultSet = statement.executeQuery()) {
                resultSet.next();
                return resultSet.getInt(1) == 1;
            }
        }
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

    private int datetimePrecision(Connection connection, String tableName, String columnName) throws SQLException {
        try (var statement = connection.prepareStatement("""
                select datetime_precision
                from information_schema.columns
                where lower(table_name) = ? and lower(column_name) = ?
                """)) {
            statement.setString(1, tableName);
            statement.setString(2, columnName);
            try (var resultSet = statement.executeQuery()) {
                assertThat(resultSet.next()).as(tableName + "." + columnName).isTrue();
                return resultSet.getInt(1);
            }
        }
    }

    private void assertAllMatrixCodeTablesAndColumnsHaveComments(Connection connection) throws SQLException {
        try (var statement = connection.prepareStatement("""
                select lower(table_name), remarks
                from information_schema.tables
                where lower(table_name) like 'matrixcode_%'
                """);
             var resultSet = statement.executeQuery()) {
            while (resultSet.next()) {
                assertThat(resultSet.getString(2))
                        .as(resultSet.getString(1) + " 表必须有注释")
                        .isNotBlank();
            }
        }

        try (var statement = connection.prepareStatement("""
                select lower(table_name), lower(column_name), remarks
                from information_schema.columns
                where lower(table_name) like 'matrixcode_%'
                """);
             var resultSet = statement.executeQuery()) {
            while (resultSet.next()) {
                assertThat(resultSet.getString(3))
                        .as(resultSet.getString(1) + "." + resultSet.getString(2) + " 字段必须有注释")
                        .isNotBlank();
            }
        }
    }
}
