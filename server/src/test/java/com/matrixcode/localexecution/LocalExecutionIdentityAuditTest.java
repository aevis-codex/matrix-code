package com.matrixcode.localexecution;

import com.matrixcode.approval.domain.ApprovalDecision;
import com.matrixcode.approval.domain.AuditRecord;
import com.matrixcode.localexecution.domain.ExecutionTask;
import com.matrixcode.localexecution.domain.ExecutionTaskStatus;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.matrixcode.persistence.application.JdbcLocalExecutionStateStore;
import com.matrixcode.persistence.application.JdbcSnapshotRepository;
import com.matrixcode.persistence.application.PersistenceModeProperties;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;

import java.sql.DriverManager;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class LocalExecutionIdentityAuditTest {

    @Test
    void jdbc审计写入时填充用户字段并确保用户存在() throws Exception {
        var jdbcUrl = jdbcUrl("local_execution_identity_audit_");
        migrate(jdbcUrl);
        var repository = repository(jdbcUrl);
        var store = new JdbcLocalExecutionStateStore(repository, new ObjectMapper());
        var fixed = Instant.parse("2026-06-25T13:20:00Z");
        var task = new ExecutionTask(
                "task-identity-1",
                "demo",
                "workspace-identity-1",
                "user-dev",
                "SHELL",
                "git status",
                ApprovalDecision.ALLOW,
                ExecutionTaskStatus.SUCCESS,
                0,
                "ok",
                "",
                5,
                fixed,
                "user-reviewer",
                "允许",
                fixed,
                ""
        );

        store.saveTasks(Map.of("demo", List.of(task)), Map.of());
        store.saveAuditRecords(List.of(new AuditRecord(
                "audit-identity-1",
                "task-identity-1",
                "user-reviewer",
                "SHELL",
                "/tmp/matrixcode",
                "git status",
                ApprovalDecision.ALLOW,
                fixed
        )));

        assertThat(actorUserId(jdbcUrl, "audit-identity-1")).isEqualTo("user-reviewer");
        assertThat(userCount(jdbcUrl, "user-reviewer")).isEqualTo(1);
    }

    private String jdbcUrl(String prefix) {
        return "jdbc:h2:mem:" + prefix + System.nanoTime()
                + ";MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1";
    }

    private void migrate(String jdbcUrl) {
        Flyway.configure()
                .dataSource(jdbcUrl, "sa", "")
                .locations("classpath:db/migration")
                .load()
                .migrate();
    }

    private JdbcSnapshotRepository repository(String jdbcUrl) {
        var properties = new PersistenceModeProperties();
        properties.setMode("jdbc");
        properties.getJdbc().setUrl(jdbcUrl);
        properties.getJdbc().setUsername("sa");
        properties.getJdbc().setPassword("");
        return new JdbcSnapshotRepository(properties);
    }

    private String actorUserId(String jdbcUrl, String auditId) throws Exception {
        try (var connection = DriverManager.getConnection(jdbcUrl, "sa", "");
             var statement = connection.prepareStatement("select actor_user_id from matrixcode_audit_records where id = ?")) {
            statement.setString(1, auditId);
            try (var resultSet = statement.executeQuery()) {
                resultSet.next();
                return resultSet.getString("actor_user_id");
            }
        }
    }

    private int userCount(String jdbcUrl, String userId) throws Exception {
        try (var connection = DriverManager.getConnection(jdbcUrl, "sa", "");
             var statement = connection.prepareStatement("select count(*) from matrixcode_users where id = ?")) {
            statement.setString(1, userId);
            try (var resultSet = statement.executeQuery()) {
                resultSet.next();
                return resultSet.getInt(1);
            }
        }
    }
}
