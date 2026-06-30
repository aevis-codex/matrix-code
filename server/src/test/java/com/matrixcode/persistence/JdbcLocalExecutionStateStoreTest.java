package com.matrixcode.persistence;

import com.matrixcode.approval.domain.ApprovalDecision;
import com.matrixcode.approval.domain.AuditRecord;
import com.matrixcode.localexecution.domain.ExecutionTask;
import com.matrixcode.localexecution.domain.ExecutionTaskStatus;
import com.matrixcode.localexecution.domain.LocalTaskLog;
import com.matrixcode.localexecution.domain.LocalTaskLogStream;
import com.matrixcode.localexecution.domain.WorkspaceAuthorization;
import com.matrixcode.localexecution.domain.WorkspaceStatus;
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

class JdbcLocalExecutionStateStoreTest {

    @Test
    void 保存后从正式本地执行表恢复状态且不写旧快照() throws Exception {
        var jdbcUrl = jdbcUrl("local_execution_domain_");
        migrate(jdbcUrl);
        var repository = repository(jdbcUrl);
        var store = new JdbcLocalExecutionStateStore(repository, JsonTestSupport.objectMapper());

        store.saveWorkspaces(List.of(workspace()));
        store.saveTasks(
                Map.of("demo", List.of(task())),
                Map.of("demo", Map.of("task-1", List.of(log())))
        );
        store.saveAuditRecords(List.of(audit()));

        var restored = new JdbcLocalExecutionStateStore(repository, JsonTestSupport.objectMapper()).load();

        assertThat(restored.workspaces()).extracting(WorkspaceAuthorization::id).containsExactly("workspace-1");
        assertThat(restored.tasks().get("demo")).extracting(ExecutionTask::taskId).containsExactly("task-1");
        assertThat(restored.taskLogs().get("demo").get("task-1"))
                .extracting(LocalTaskLog::content)
                .containsExactly("任务完成");
        assertThat(restored.auditRecords()).extracting(AuditRecord::id).containsExactly("audit-1");
        assertThat(tableCount(jdbcUrl, "matrixcode_local_workspaces")).isEqualTo(1);
        assertThat(tableCount(jdbcUrl, "matrixcode_local_execution_tasks")).isEqualTo(1);
        assertThat(tableCount(jdbcUrl, "matrixcode_local_task_logs")).isEqualTo(1);
        assertThat(tableCount(jdbcUrl, "matrixcode_audit_records")).isEqualTo(1);
        assertThat(snapshotCount(jdbcUrl, "local-execution")).isZero();
    }

    @Test
    void 正式表为空时从旧快照恢复并回填正式表() throws Exception {
        var jdbcUrl = jdbcUrl("local_execution_backfill_");
        migrate(jdbcUrl);
        var repository = repository(jdbcUrl);
        repository.save("local-execution", 1, JsonTestSupport.objectMapper().writeValueAsString(
                new com.matrixcode.localexecution.application.LocalExecutionSnapshot(
                        1,
                        List.of(workspace()),
                        Map.of("demo", List.of(task())),
                        Map.of("demo", Map.of("task-1", List.of(log()))),
                        List.of(audit())
                )
        ));

        var restored = new JdbcLocalExecutionStateStore(repository, JsonTestSupport.objectMapper()).load();

        assertThat(restored.workspaces()).extracting(WorkspaceAuthorization::id).containsExactly("workspace-1");
        assertThat(restored.tasks().get("demo")).extracting(ExecutionTask::taskId).containsExactly("task-1");
        assertThat(restored.auditRecords()).extracting(AuditRecord::id).containsExactly("audit-1");
        assertThat(tableCount(jdbcUrl, "matrixcode_local_workspaces")).isEqualTo(1);
        assertThat(tableCount(jdbcUrl, "matrixcode_local_execution_tasks")).isEqualTo(1);
        assertThat(tableCount(jdbcUrl, "matrixcode_local_task_logs")).isEqualTo(1);
        assertThat(tableCount(jdbcUrl, "matrixcode_audit_records")).isEqualTo(1);
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

    private int tableCount(String jdbcUrl, String tableName) throws Exception {
        try (var connection = DriverManager.getConnection(jdbcUrl, "sa", "");
             var statement = connection.prepareStatement("select count(*) from " + tableName);
             var resultSet = statement.executeQuery()) {
            resultSet.next();
            return resultSet.getInt(1);
        }
    }

    private int snapshotCount(String jdbcUrl, String sliceKey) throws Exception {
        try (var connection = DriverManager.getConnection(jdbcUrl, "sa", "")) {
            try (var tables = connection.getMetaData().getTables(null, null, "matrixcode_state_snapshots", null)) {
                if (!tables.next()) {
                    return 0;
                }
            }
            try (var statement = connection.prepareStatement(
                    "select count(*) from matrixcode_state_snapshots where slice_key = ?"
            )) {
                statement.setString(1, sliceKey);
                try (var resultSet = statement.executeQuery()) {
                    resultSet.next();
                    return resultSet.getInt(1);
                }
            }
        }
    }

    private WorkspaceAuthorization workspace() {
        return new WorkspaceAuthorization(
                "workspace-1",
                "demo",
                "MatrixCode 工作区",
                "/tmp/matrixcode",
                WorkspaceStatus.AUTHORIZED,
                Instant.parse("2026-06-25T00:00:00Z"),
                Instant.parse("2026-06-25T00:00:01Z")
        );
    }

    private ExecutionTask task() {
        return new ExecutionTask(
                "task-1",
                "demo",
                "workspace-1",
                "user-dev",
                "SHELL",
                "pwd",
                ApprovalDecision.ALLOW,
                ExecutionTaskStatus.SUCCESS,
                0,
                "/tmp/matrixcode",
                "",
                12,
                Instant.parse("2026-06-25T00:00:02Z")
        );
    }

    private LocalTaskLog log() {
        return new LocalTaskLog(
                "log-1",
                "demo",
                "task-1",
                LocalTaskLogStream.SYSTEM,
                "任务完成",
                Instant.parse("2026-06-25T00:00:03Z")
        );
    }

    private AuditRecord audit() {
        return new AuditRecord(
                "audit-1",
                "task-1",
                "user-dev",
                "SHELL",
                "/tmp/matrixcode",
                "允许执行 pwd",
                ApprovalDecision.ALLOW,
                Instant.parse("2026-06-25T00:00:04Z")
        );
    }
}
