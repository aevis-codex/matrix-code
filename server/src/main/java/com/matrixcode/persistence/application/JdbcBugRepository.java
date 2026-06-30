package com.matrixcode.persistence.application;

import com.matrixcode.bug.application.BugRepository;
import com.matrixcode.bug.domain.BugSeverity;
import com.matrixcode.bug.domain.BugStatus;
import com.matrixcode.bug.domain.ProjectBug;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

public class JdbcBugRepository implements BugRepository {

    private final PersistenceModeProperties properties;
    private final DatabaseMigrationService migrationService;
    private boolean migrated;

    public JdbcBugRepository(
            PersistenceModeProperties properties,
            DatabaseMigrationService migrationService
    ) {
        this.properties = properties;
        this.migrationService = migrationService;
    }

    public JdbcBugRepository(PersistenceModeProperties properties) {
        this.properties = properties;
        this.migrationService = null;
    }

    @Override
    public List<ProjectBug> load() {
        ensureSchema();
        var bugs = new ArrayList<ProjectBug>();
        try (var connection = connection();
             var statement = connection.prepareStatement("""
                     select id, project_id, title, severity, status, reproduction_steps,
                            expected_result, actual_result, created_by_role, current_owner_role,
                            last_note, updated_at
                     from matrixcode_bugs
                     order by project_id, title, id
                     """);
             var resultSet = statement.executeQuery()) {
            while (resultSet.next()) {
                bugs.add(readBug(resultSet));
            }
            return bugs;
        } catch (SQLException exception) {
            throw new IllegalStateException("Bug 表读取失败：" + exception.getMessage(), exception);
        }
    }

    @Override
    public void save(List<ProjectBug> bugs) {
        if (bugs == null || bugs.isEmpty()) {
            return;
        }
        ensureSchema();
        try (var connection = connection()) {
            connection.setAutoCommit(false);
            try {
                for (var bug : bugs) {
                    ensureProject(connection, bug.projectId());
                    if (updateBug(connection, bug) == 0) {
                        insertBug(connection, bug);
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
            throw new IllegalStateException("Bug 表写入失败：" + exception.getMessage(), exception);
        }
    }

    private ProjectBug readBug(ResultSet resultSet) throws SQLException {
        return new ProjectBug(
                resultSet.getString("id"),
                resultSet.getString("project_id"),
                resultSet.getString("title"),
                BugSeverity.valueOf(resultSet.getString("severity")),
                BugStatus.valueOf(resultSet.getString("status")),
                resultSet.getString("reproduction_steps"),
                resultSet.getString("expected_result"),
                resultSet.getString("actual_result"),
                resultSet.getString("created_by_role"),
                resultSet.getString("current_owner_role"),
                resultSet.getString("last_note"),
                instant(resultSet.getTimestamp("updated_at"))
        );
    }

    private int updateBug(Connection connection, ProjectBug bug) throws SQLException {
        try (var statement = connection.prepareStatement("""
                update matrixcode_bugs
                set title = ?, description = ?, severity = ?, status = ?, created_by_role = ?,
                    current_owner_role = ?, updated_at = ?, reproduction_steps = ?,
                    expected_result = ?, actual_result = ?, last_note = ?
                where id = ?
                """)) {
            bindMutableBugFields(statement, bug);
            statement.setString(12, bug.id());
            return statement.executeUpdate();
        }
    }

    private void insertBug(Connection connection, ProjectBug bug) throws SQLException {
        try (var statement = connection.prepareStatement("""
                insert into matrixcode_bugs
                    (id, project_id, title, description, severity, status, created_by_role,
                     current_owner_role, related_document_id, created_at, updated_at,
                     reproduction_steps, expected_result, actual_result, last_note)
                values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """)) {
            statement.setString(1, bug.id());
            statement.setString(2, bug.projectId());
            statement.setString(3, bug.title());
            statement.setString(4, bug.steps());
            statement.setString(5, bug.severity().name());
            statement.setString(6, bug.status().name());
            statement.setString(7, bug.createdByRole());
            statement.setString(8, bug.currentOwnerRole());
            statement.setString(9, null);
            statement.setTimestamp(10, timestamp(bug.updatedAt()));
            statement.setTimestamp(11, timestamp(bug.updatedAt()));
            statement.setString(12, bug.steps());
            statement.setString(13, bug.expected());
            statement.setString(14, bug.actual());
            statement.setString(15, bug.lastNote());
            statement.executeUpdate();
        }
    }

    private void bindMutableBugFields(java.sql.PreparedStatement statement, ProjectBug bug) throws SQLException {
        statement.setString(1, bug.title());
        statement.setString(2, bug.steps());
        statement.setString(3, bug.severity().name());
        statement.setString(4, bug.status().name());
        statement.setString(5, bug.createdByRole());
        statement.setString(6, bug.currentOwnerRole());
        statement.setTimestamp(7, timestamp(bug.updatedAt()));
        statement.setString(8, bug.steps());
        statement.setString(9, bug.expected());
        statement.setString(10, bug.actual());
        statement.setString(11, bug.lastNote());
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
            statement.setString(6, "Bug 缺陷闭环");
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
}
