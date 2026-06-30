package com.matrixcode.persistence.application;

import com.matrixcode.deployment.application.DeploymentTargetRepository;
import com.matrixcode.deployment.domain.DeploymentTarget;
import com.matrixcode.deployment.domain.DeploymentTargetStatus;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

public class JdbcDeploymentTargetRepository implements DeploymentTargetRepository {

    private static final String PROVIDER = "manual";

    private final PersistenceModeProperties properties;
    private final DatabaseMigrationService migrationService;
    private boolean migrated;

    public JdbcDeploymentTargetRepository(
            PersistenceModeProperties properties,
            DatabaseMigrationService migrationService
    ) {
        this.properties = properties;
        this.migrationService = migrationService;
    }

    public JdbcDeploymentTargetRepository(PersistenceModeProperties properties) {
        this.properties = properties;
        this.migrationService = null;
    }

    @Override
    public List<DeploymentTarget> load() {
        ensureSchema();
        var targets = new ArrayList<DeploymentTarget>();
        try (var connection = connection();
             var statement = connection.prepareStatement("""
                     select id, project_id, name, endpoint_url, ssh_address, deploy_note,
                            health_check_url, rollback_note, status, remote_executed, updated_at
                     from matrixcode_deployment_targets
                     order by project_id, name, id
                     """);
             var resultSet = statement.executeQuery()) {
            while (resultSet.next()) {
                targets.add(readTarget(resultSet));
            }
            return targets;
        } catch (SQLException exception) {
            throw new IllegalStateException("部署目标表读取失败：" + exception.getMessage(), exception);
        }
    }

    @Override
    public void save(List<DeploymentTarget> targets) {
        if (targets == null || targets.isEmpty()) {
            return;
        }
        ensureSchema();
        try (var connection = connection()) {
            connection.setAutoCommit(false);
            try {
                for (var target : targets) {
                    ensureProject(connection, target.projectId());
                    if (updateTarget(connection, target) == 0) {
                        insertTarget(connection, target);
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
            throw new IllegalStateException("部署目标表写入失败：" + exception.getMessage(), exception);
        }
    }

    private DeploymentTarget readTarget(ResultSet resultSet) throws SQLException {
        return new DeploymentTarget(
                resultSet.getString("id"),
                resultSet.getString("project_id"),
                resultSet.getString("name"),
                resultSet.getString("endpoint_url"),
                textOr(resultSet.getString("ssh_address"), ""),
                textOr(resultSet.getString("deploy_note"), ""),
                textOr(resultSet.getString("health_check_url"), resultSet.getString("endpoint_url")),
                textOr(resultSet.getString("rollback_note"), ""),
                DeploymentTargetStatus.valueOf(resultSet.getString("status")),
                resultSet.getBoolean("remote_executed"),
                instant(resultSet.getTimestamp("updated_at"))
        );
    }

    private int updateTarget(Connection connection, DeploymentTarget target) throws SQLException {
        try (var statement = connection.prepareStatement("""
                update matrixcode_deployment_targets
                set name = ?, environment_key = ?, provider = ?, status = ?, endpoint_url = ?,
                    ssh_address = ?, deploy_note = ?, health_check_url = ?, rollback_note = ?,
                    remote_executed = ?, updated_at = ?
                where id = ?
                """)) {
            bindMutableFields(statement, target);
            statement.setString(12, target.id());
            return statement.executeUpdate();
        }
    }

    private void insertTarget(Connection connection, DeploymentTarget target) throws SQLException {
        try (var statement = connection.prepareStatement("""
                insert into matrixcode_deployment_targets
                    (id, project_id, name, environment_key, provider, status, endpoint_url,
                     created_at, updated_at, ssh_address, deploy_note, health_check_url,
                     rollback_note, remote_executed)
                values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """)) {
            statement.setString(1, target.id());
            statement.setString(2, target.projectId());
            statement.setString(3, target.environmentName());
            statement.setString(4, target.id());
            statement.setString(5, PROVIDER);
            statement.setString(6, target.status().name());
            statement.setString(7, target.environmentUrl());
            statement.setTimestamp(8, timestamp(target.updatedAt()));
            statement.setTimestamp(9, timestamp(target.updatedAt()));
            statement.setString(10, target.sshAddress());
            statement.setString(11, target.deployNote());
            statement.setString(12, target.healthCheckUrl());
            statement.setString(13, target.rollbackNote());
            statement.setBoolean(14, target.remoteExecuted());
            statement.executeUpdate();
        }
    }

    private void bindMutableFields(java.sql.PreparedStatement statement, DeploymentTarget target) throws SQLException {
        statement.setString(1, target.environmentName());
        statement.setString(2, target.id());
        statement.setString(3, PROVIDER);
        statement.setString(4, target.status().name());
        statement.setString(5, target.environmentUrl());
        statement.setString(6, target.sshAddress());
        statement.setString(7, target.deployNote());
        statement.setString(8, target.healthCheckUrl());
        statement.setString(9, target.rollbackNote());
        statement.setBoolean(10, target.remoteExecuted());
        statement.setTimestamp(11, timestamp(target.updatedAt()));
    }

    private void ensureProject(Connection connection, String projectId) throws SQLException {
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
            statement.setString(6, "部署目标");
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

    private Timestamp timestamp(Instant instant) {
        return Timestamp.from(instant == null ? Instant.EPOCH : instant);
    }

    private Instant instant(Timestamp timestamp) {
        return timestamp == null ? Instant.EPOCH : timestamp.toInstant();
    }

    private String textOr(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }
}
