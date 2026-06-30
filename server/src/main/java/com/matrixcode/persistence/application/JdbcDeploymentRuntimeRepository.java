package com.matrixcode.persistence.application;

import com.matrixcode.deployment.application.DeploymentRuntimeRepository;
import com.matrixcode.deployment.domain.ComposeEnvironment;
import com.matrixcode.deployment.domain.ComposeEnvironmentStatus;
import com.matrixcode.deployment.domain.ComposeOperationRecord;
import com.matrixcode.deployment.domain.ComposeOperationStatus;
import com.matrixcode.deployment.domain.ComposeOperationType;
import com.matrixcode.deployment.domain.DeploymentHealthCheck;
import com.matrixcode.deployment.domain.DeploymentHealthStatus;
import com.matrixcode.deployment.domain.DeploymentOperationRecord;
import com.matrixcode.deployment.domain.DeploymentOperationStatus;
import com.matrixcode.deployment.domain.DeploymentOperationType;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class JdbcDeploymentRuntimeRepository implements DeploymentRuntimeRepository {

    private final PersistenceModeProperties properties;
    private final DatabaseMigrationService migrationService;
    private boolean migrated;

    public JdbcDeploymentRuntimeRepository(
            PersistenceModeProperties properties,
            DatabaseMigrationService migrationService
    ) {
        this.properties = properties;
        this.migrationService = migrationService;
    }

    public JdbcDeploymentRuntimeRepository(PersistenceModeProperties properties) {
        this.properties = properties;
        this.migrationService = null;
    }

    @Override
    public Map<String, List<DeploymentOperationRecord>> loadDeploymentOperations() {
        ensureSchema();
        try (var connection = connection();
             var statement = connection.prepareStatement("""
                     select id, project_id, target_id, actor_id, operation_type, status, note, created_at
                     from matrixcode_deployment_operations
                     order by project_id, created_at desc, id
                     """);
             var resultSet = statement.executeQuery()) {
            var grouped = new LinkedHashMap<String, List<DeploymentOperationRecord>>();
            while (resultSet.next()) {
                var record = new DeploymentOperationRecord(
                        resultSet.getString("id"),
                        resultSet.getString("project_id"),
                        resultSet.getString("target_id"),
                        resultSet.getString("actor_id"),
                        DeploymentOperationType.valueOf(resultSet.getString("operation_type")),
                        DeploymentOperationStatus.valueOf(resultSet.getString("status")),
                        resultSet.getString("note"),
                        instant(resultSet.getTimestamp("created_at"))
                );
                grouped.computeIfAbsent(record.projectId(), ignored -> new ArrayList<>()).add(record);
            }
            return immutableGrouped(grouped);
        } catch (SQLException exception) {
            throw new IllegalStateException("部署操作表读取失败：" + exception.getMessage(), exception);
        }
    }

    @Override
    public void saveDeploymentOperations(Map<String, List<DeploymentOperationRecord>> operations) {
        if (operations == null || operations.isEmpty()) {
            return;
        }
        ensureSchema();
        try (var connection = connection()) {
            connection.setAutoCommit(false);
            try {
                for (var entry : operations.entrySet()) {
                    ensureProject(connection, entry.getKey(), "部署操作");
                    deleteByProject(connection, "matrixcode_deployment_operations", entry.getKey());
                    for (var record : entry.getValue()) {
                        insertDeploymentOperation(connection, record);
                    }
                }
                connection.commit();
            } catch (SQLException | RuntimeException exception) {
                connection.rollback();
                throw exception;
            } finally {
                connection.setAutoCommit(true);
            }
        } catch (SQLException exception) {
            throw new IllegalStateException("部署操作表写入失败：" + exception.getMessage(), exception);
        }
    }

    @Override
    public Map<String, List<DeploymentHealthCheck>> loadDeploymentHealthChecks() {
        ensureSchema();
        try (var connection = connection();
             var statement = connection.prepareStatement("""
                     select id, project_id, target_id, actor_id, status, http_status,
                            duration_millis, summary, checked_at
                     from matrixcode_deployment_health_checks
                     order by project_id, checked_at desc, id
                     """);
             var resultSet = statement.executeQuery()) {
            var grouped = new LinkedHashMap<String, List<DeploymentHealthCheck>>();
            while (resultSet.next()) {
                var check = new DeploymentHealthCheck(
                        resultSet.getString("id"),
                        resultSet.getString("project_id"),
                        resultSet.getString("target_id"),
                        resultSet.getString("actor_id"),
                        DeploymentHealthStatus.valueOf(resultSet.getString("status")),
                        nullableInt(resultSet, "http_status"),
                        resultSet.getLong("duration_millis"),
                        resultSet.getString("summary"),
                        instant(resultSet.getTimestamp("checked_at"))
                );
                grouped.computeIfAbsent(check.projectId(), ignored -> new ArrayList<>()).add(check);
            }
            return immutableGrouped(grouped);
        } catch (SQLException exception) {
            throw new IllegalStateException("部署健康检查表读取失败：" + exception.getMessage(), exception);
        }
    }

    @Override
    public void saveDeploymentHealthChecks(Map<String, List<DeploymentHealthCheck>> checks) {
        if (checks == null || checks.isEmpty()) {
            return;
        }
        ensureSchema();
        try (var connection = connection()) {
            connection.setAutoCommit(false);
            try {
                for (var entry : checks.entrySet()) {
                    ensureProject(connection, entry.getKey(), "部署健康检查");
                    deleteByProject(connection, "matrixcode_deployment_health_checks", entry.getKey());
                    for (var check : entry.getValue()) {
                        insertDeploymentHealthCheck(connection, check);
                    }
                }
                connection.commit();
            } catch (SQLException | RuntimeException exception) {
                connection.rollback();
                throw exception;
            } finally {
                connection.setAutoCommit(true);
            }
        } catch (SQLException exception) {
            throw new IllegalStateException("部署健康检查表写入失败：" + exception.getMessage(), exception);
        }
    }

    @Override
    public List<ComposeEnvironment> loadComposeEnvironments() {
        ensureSchema();
        try (var connection = connection();
             var statement = connection.prepareStatement("""
                     select id, project_id, target_id, workspace_id, compose_file_path, project_name,
                            service_name, status, created_at, updated_at
                     from matrixcode_compose_environments
                     order by project_id, created_at, id
                     """);
             var resultSet = statement.executeQuery()) {
            var environments = new ArrayList<ComposeEnvironment>();
            while (resultSet.next()) {
                environments.add(new ComposeEnvironment(
                        resultSet.getString("id"),
                        resultSet.getString("project_id"),
                        resultSet.getString("target_id"),
                        resultSet.getString("workspace_id"),
                        resultSet.getString("compose_file_path"),
                        resultSet.getString("project_name"),
                        resultSet.getString("service_name"),
                        ComposeEnvironmentStatus.valueOf(resultSet.getString("status")),
                        instant(resultSet.getTimestamp("created_at")),
                        instant(resultSet.getTimestamp("updated_at"))
                ));
            }
            return List.copyOf(environments);
        } catch (SQLException exception) {
            throw new IllegalStateException("Compose 环境表读取失败：" + exception.getMessage(), exception);
        }
    }

    @Override
    public void saveComposeEnvironments(List<ComposeEnvironment> environments) {
        if (environments == null || environments.isEmpty()) {
            return;
        }
        ensureSchema();
        try (var connection = connection()) {
            connection.setAutoCommit(false);
            try {
                for (var environment : environments) {
                    ensureProject(connection, environment.projectId(), "Compose 环境");
                    if (updateComposeEnvironment(connection, environment) == 0) {
                        insertComposeEnvironment(connection, environment);
                    }
                }
                connection.commit();
            } catch (SQLException | RuntimeException exception) {
                connection.rollback();
                throw exception;
            } finally {
                connection.setAutoCommit(true);
            }
        } catch (SQLException exception) {
            throw new IllegalStateException("Compose 环境表写入失败：" + exception.getMessage(), exception);
        }
    }

    @Override
    public Map<String, List<ComposeOperationRecord>> loadComposeOperations() {
        ensureSchema();
        try (var connection = connection();
             var statement = connection.prepareStatement("""
                     select id, project_id, environment_id, actor_id, operation_type, status,
                            summary, log_excerpt, created_at
                     from matrixcode_compose_operations
                     order by project_id, created_at desc, id
                     """);
             var resultSet = statement.executeQuery()) {
            var grouped = new LinkedHashMap<String, List<ComposeOperationRecord>>();
            while (resultSet.next()) {
                var record = new ComposeOperationRecord(
                        resultSet.getString("id"),
                        resultSet.getString("project_id"),
                        resultSet.getString("environment_id"),
                        resultSet.getString("actor_id"),
                        ComposeOperationType.valueOf(resultSet.getString("operation_type")),
                        ComposeOperationStatus.valueOf(resultSet.getString("status")),
                        resultSet.getString("summary"),
                        resultSet.getString("log_excerpt"),
                        instant(resultSet.getTimestamp("created_at"))
                );
                grouped.computeIfAbsent(record.projectId(), ignored -> new ArrayList<>()).add(record);
            }
            return immutableGrouped(grouped);
        } catch (SQLException exception) {
            throw new IllegalStateException("Compose 操作表读取失败：" + exception.getMessage(), exception);
        }
    }

    @Override
    public void saveComposeOperations(Map<String, List<ComposeOperationRecord>> operations) {
        if (operations == null || operations.isEmpty()) {
            return;
        }
        ensureSchema();
        try (var connection = connection()) {
            connection.setAutoCommit(false);
            try {
                for (var entry : operations.entrySet()) {
                    ensureProject(connection, entry.getKey(), "Compose 操作");
                    deleteByProject(connection, "matrixcode_compose_operations", entry.getKey());
                    for (var record : entry.getValue()) {
                        insertComposeOperation(connection, record);
                    }
                }
                connection.commit();
            } catch (SQLException | RuntimeException exception) {
                connection.rollback();
                throw exception;
            } finally {
                connection.setAutoCommit(true);
            }
        } catch (SQLException exception) {
            throw new IllegalStateException("Compose 操作表写入失败：" + exception.getMessage(), exception);
        }
    }

    private void insertDeploymentOperation(Connection connection, DeploymentOperationRecord record) throws SQLException {
        try (var statement = connection.prepareStatement("""
                insert into matrixcode_deployment_operations
                    (id, project_id, target_id, actor_id, operation_type, status, note, created_at)
                values (?, ?, ?, ?, ?, ?, ?, ?)
                """)) {
            statement.setString(1, record.id());
            statement.setString(2, record.projectId());
            statement.setString(3, record.targetId());
            statement.setString(4, record.actorId());
            statement.setString(5, record.type().name());
            statement.setString(6, record.status().name());
            statement.setString(7, record.note());
            statement.setTimestamp(8, timestamp(record.createdAt()));
            statement.executeUpdate();
        }
    }

    private void insertDeploymentHealthCheck(Connection connection, DeploymentHealthCheck check) throws SQLException {
        try (var statement = connection.prepareStatement("""
                insert into matrixcode_deployment_health_checks
                    (id, project_id, target_id, actor_id, status, http_status,
                     duration_millis, summary, checked_at)
                values (?, ?, ?, ?, ?, ?, ?, ?, ?)
                """)) {
            statement.setString(1, check.id());
            statement.setString(2, check.projectId());
            statement.setString(3, check.targetId());
            statement.setString(4, check.actorId());
            statement.setString(5, check.status().name());
            if (check.httpStatus() == null) {
                statement.setNull(6, java.sql.Types.INTEGER);
            } else {
                statement.setInt(6, check.httpStatus());
            }
            statement.setLong(7, check.durationMillis());
            statement.setString(8, check.summary());
            statement.setTimestamp(9, timestamp(check.checkedAt()));
            statement.executeUpdate();
        }
    }

    private int updateComposeEnvironment(Connection connection, ComposeEnvironment environment) throws SQLException {
        try (var statement = connection.prepareStatement("""
                update matrixcode_compose_environments
                set target_id = ?, workspace_id = ?, compose_file_path = ?, project_name = ?,
                    service_name = ?, status = ?, created_at = ?, updated_at = ?
                where id = ?
                """)) {
            bindComposeEnvironmentMutableFields(statement, environment);
            statement.setString(9, environment.id());
            return statement.executeUpdate();
        }
    }

    private void insertComposeEnvironment(Connection connection, ComposeEnvironment environment) throws SQLException {
        try (var statement = connection.prepareStatement("""
                insert into matrixcode_compose_environments
                    (id, project_id, target_id, workspace_id, compose_file_path,
                     project_name, service_name, status, created_at, updated_at)
                values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """)) {
            statement.setString(1, environment.id());
            statement.setString(2, environment.projectId());
            statement.setString(3, environment.targetId());
            statement.setString(4, environment.workspaceId());
            statement.setString(5, environment.composeFilePath());
            statement.setString(6, environment.projectName());
            statement.setString(7, environment.serviceName());
            statement.setString(8, environment.status().name());
            statement.setTimestamp(9, timestamp(environment.createdAt()));
            statement.setTimestamp(10, timestamp(environment.updatedAt()));
            statement.executeUpdate();
        }
    }

    private void bindComposeEnvironmentMutableFields(
            java.sql.PreparedStatement statement,
            ComposeEnvironment environment
    ) throws SQLException {
        statement.setString(1, environment.targetId());
        statement.setString(2, environment.workspaceId());
        statement.setString(3, environment.composeFilePath());
        statement.setString(4, environment.projectName());
        statement.setString(5, environment.serviceName());
        statement.setString(6, environment.status().name());
        statement.setTimestamp(7, timestamp(environment.createdAt()));
        statement.setTimestamp(8, timestamp(environment.updatedAt()));
    }

    private void insertComposeOperation(Connection connection, ComposeOperationRecord record) throws SQLException {
        try (var statement = connection.prepareStatement("""
                insert into matrixcode_compose_operations
                    (id, project_id, environment_id, actor_id, operation_type, status,
                     summary, log_excerpt, created_at)
                values (?, ?, ?, ?, ?, ?, ?, ?, ?)
                """)) {
            statement.setString(1, record.id());
            statement.setString(2, record.projectId());
            statement.setString(3, record.environmentId());
            statement.setString(4, record.actorId());
            statement.setString(5, record.type().name());
            statement.setString(6, record.status().name());
            statement.setString(7, record.summary());
            statement.setString(8, record.logExcerpt());
            statement.setTimestamp(9, timestamp(record.createdAt()));
            statement.executeUpdate();
        }
    }

    private void deleteByProject(Connection connection, String table, String projectId) throws SQLException {
        try (var statement = connection.prepareStatement("delete from " + table + " where project_id = ?")) {
            statement.setString(1, projectId);
            statement.executeUpdate();
        }
    }

    private void ensureProject(Connection connection, String projectId, String stage) throws SQLException {
        try (var statement = connection.prepareStatement("""
                update matrixcode_projects
                set updated_at = CURRENT_TIMESTAMP
                where id = ?
                """)) {
            statement.setString(1, projectId);
            if (statement.executeUpdate() > 0) {
                return;
            }
        }
        try (var statement = connection.prepareStatement("""
                insert into matrixcode_projects
                    (id, name, description, owner_user_id, status, current_stage, created_at, updated_at)
                values (?, ?, ?, ?, ?, ?, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
                """)) {
            statement.setString(1, projectId);
            statement.setString(2, projectId);
            statement.setString(3, "");
            statement.setString(4, null);
            statement.setString(5, "ACTIVE");
            statement.setString(6, stage);
            statement.executeUpdate();
        }
    }

    private Connection connection() throws SQLException {
        var jdbc = properties.getJdbc();
        if (jdbc.getUrl().isBlank()) {
            throw new IllegalStateException("JDBC URL 不能为空");
        }
        return JdbcConnectionFactory.open(jdbc);
    }

    private synchronized void ensureSchema() {
        if (migrated || migrationService == null || !properties.getJdbc().isMigrateOnStartup()) {
            return;
        }
        migrationService.migrate();
        migrated = true;
    }

    private Integer nullableInt(ResultSet resultSet, String column) throws SQLException {
        var value = resultSet.getInt(column);
        return resultSet.wasNull() ? null : value;
    }

    private Timestamp timestamp(Instant instant) {
        return Timestamp.from(instant == null ? Instant.EPOCH : instant);
    }

    private Instant instant(Timestamp timestamp) {
        return timestamp == null ? Instant.EPOCH : timestamp.toInstant();
    }

    private <T> Map<String, List<T>> immutableGrouped(Map<String, List<T>> grouped) {
        var copy = new LinkedHashMap<String, List<T>>();
        grouped.forEach((projectId, records) -> copy.put(projectId, List.copyOf(records)));
        return Map.copyOf(copy);
    }
}
