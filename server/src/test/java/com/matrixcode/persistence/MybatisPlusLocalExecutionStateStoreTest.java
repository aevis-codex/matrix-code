package com.matrixcode.persistence;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.matrixcode.MatrixCodeServerApplication;
import com.matrixcode.approval.domain.ApprovalDecision;
import com.matrixcode.approval.domain.AuditRecord;
import com.matrixcode.localexecution.application.LocalExecutionSnapshot;
import com.matrixcode.localexecution.application.LocalExecutionStateStore;
import com.matrixcode.localexecution.domain.ExecutionTask;
import com.matrixcode.localexecution.domain.ExecutionTaskStatus;
import com.matrixcode.localexecution.domain.LocalTaskLog;
import com.matrixcode.localexecution.domain.LocalTaskLogStream;
import com.matrixcode.localexecution.domain.WorkspaceAuthorization;
import com.matrixcode.localexecution.domain.WorkspaceStatus;
import com.matrixcode.persistence.application.JdbcSnapshotRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;

import javax.sql.DataSource;
import java.nio.file.Path;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class MybatisPlusLocalExecutionStateStoreTest {

    @TempDir
    Path tempDir;

    @Test
    void Jdbc模式下本地执行状态仓储使用MybatisPlus并保存正式表状态() throws Exception {
        var jdbcUrl = jdbcUrl("local_execution_mp_");

        try (var context = startJdbcContext(jdbcUrl)) {
            var store = context.getBean(LocalExecutionStateStore.class);
            assertThat(store.getClass().getName())
                    .contains("MybatisPlusLocalExecutionStateStore");

            store.saveWorkspaces(List.of(workspace()));
            store.saveTasks(
                    Map.of("demo", List.of(task())),
                    Map.of("demo", Map.of("task-1", List.of(log())))
            );
            insertIdentityAudit(context.getBean(DataSource.class));
            store.saveAuditRecords(List.of(audit()));

            var dataSource = context.getBean(DataSource.class);
            assertThat(tableCount(dataSource, "matrixcode_local_workspaces")).isEqualTo(1);
            assertThat(tableCount(dataSource, "matrixcode_local_execution_tasks")).isEqualTo(1);
            assertThat(tableCount(dataSource, "matrixcode_local_task_logs")).isEqualTo(1);
            assertThat(tableCount(dataSource, "matrixcode_audit_records")).isEqualTo(2);
            assertThat(snapshotCount(dataSource, "local-execution")).isZero();
        }

        try (var context = startJdbcContext(jdbcUrl)) {
            var restored = context.getBean(LocalExecutionStateStore.class).load();

            assertThat(restored.workspaces()).extracting(WorkspaceAuthorization::id).containsExactly("workspace-1");
            assertThat(restored.tasks().get("demo")).extracting(ExecutionTask::taskId).containsExactly("task-1");
            assertThat(restored.taskLogs().get("demo").get("task-1"))
                    .extracting(LocalTaskLog::content)
                    .containsExactly("任务完成");
            assertThat(restored.auditRecords()).extracting(AuditRecord::id).containsExactly("audit-1");
        }
    }

    @Test
    void Jdbc模式下正式表为空时MybatisPlus仓储从旧快照回填正式表() throws Exception {
        var jdbcUrl = jdbcUrl("local_execution_mp_backfill_");

        try (var context = startJdbcContext(jdbcUrl)) {
            context.getBean(JdbcSnapshotRepository.class).save(
                    "local-execution",
                    1,
                    context.getBean(ObjectMapper.class).writeValueAsString(new LocalExecutionSnapshot(
                            1,
                            List.of(workspace()),
                            Map.of("demo", List.of(task())),
                            Map.of("demo", Map.of("task-1", List.of(log()))),
                            List.of(audit())
                    ))
            );
        }

        try (var context = startJdbcContext(jdbcUrl)) {
            var store = context.getBean(LocalExecutionStateStore.class);
            assertThat(store.getClass().getName())
                    .contains("MybatisPlusLocalExecutionStateStore");

            var restored = store.load();

            assertThat(restored.workspaces()).extracting(WorkspaceAuthorization::id).containsExactly("workspace-1");
            assertThat(restored.tasks().get("demo")).extracting(ExecutionTask::taskId).containsExactly("task-1");
            assertThat(restored.auditRecords()).extracting(AuditRecord::id).containsExactly("audit-1");
            var dataSource = context.getBean(DataSource.class);
            assertThat(tableCount(dataSource, "matrixcode_local_workspaces")).isEqualTo(1);
            assertThat(tableCount(dataSource, "matrixcode_local_execution_tasks")).isEqualTo(1);
            assertThat(tableCount(dataSource, "matrixcode_local_task_logs")).isEqualTo(1);
            assertThat(tableCount(dataSource, "matrixcode_audit_records")).isEqualTo(1);
        }
    }

    private org.springframework.context.ConfigurableApplicationContext startJdbcContext(String jdbcUrl) {
        return new SpringApplicationBuilder(MatrixCodeServerApplication.class)
                .web(WebApplicationType.NONE)
                .properties(commonProperties())
                .run(
                        "--matrixcode.persistence.mode=jdbc",
                        "--matrixcode.persistence.jdbc.url=" + jdbcUrl,
                        "--matrixcode.persistence.jdbc.username=sa",
                        "--matrixcode.persistence.jdbc.password=",
                        "--matrixcode.persistence.jdbc.migrate-on-startup=true"
                );
    }

    private Map<String, Object> commonProperties() {
        return Map.of(
                "matrixcode.workbench-state.storage-path", tempDir.resolve("workbench-state.json").toString(),
                "matrixcode.local-execution.storage-path", tempDir.resolve("local-execution.json").toString(),
                "matrixcode.runtime-notifications.storage-path", tempDir.resolve("runtime-notifications.json").toString(),
                "spring.main.banner-mode", "off"
        );
    }

    private String jdbcUrl(String prefix) {
        return "jdbc:h2:mem:" + prefix + UUID.randomUUID().toString().replace("-", "")
                + ";MODE=MySQL;DATABASE_TO_LOWER=TRUE;CASE_INSENSITIVE_IDENTIFIERS=TRUE;DB_CLOSE_DELAY=-1";
    }

    private int tableCount(DataSource dataSource, String tableName) throws SQLException {
        try (var connection = dataSource.getConnection();
             var statement = connection.prepareStatement("select count(*) from " + tableName);
             var resultSet = statement.executeQuery()) {
            resultSet.next();
            return resultSet.getInt(1);
        }
    }

    private int snapshotCount(DataSource dataSource, String sliceKey) throws SQLException {
        try (var connection = dataSource.getConnection();
             var statement = connection.prepareStatement(
                     "select count(*) from matrixcode_state_snapshots where slice_key = ?"
             )) {
            statement.setString(1, sliceKey);
            try (var resultSet = statement.executeQuery()) {
                resultSet.next();
                return resultSet.getInt(1);
            }
        }
    }

    private void insertIdentityAudit(DataSource dataSource) throws SQLException {
        try (var connection = dataSource.getConnection();
             var userStatement = connection.prepareStatement("""
                     insert into matrixcode_users
                         (id, username, display_name, email, status, created_at, updated_at)
                     values (?, ?, ?, ?, ?, ?, ?)
                     """);
             var statement = connection.prepareStatement("""
                     insert into matrixcode_audit_records
                         (id, project_id, actor_user_id, actor_role, action_key, target_type, target_id,
                          decision, summary, created_at, updated_at, task_id, tool_type, workspace_path,
                          occurred_at, sort_order)
                     values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                     """)) {
            var occurredAt = java.sql.Timestamp.from(Instant.parse("2026-06-25T00:00:05Z"));
            userStatement.setString(1, "user-dev");
            userStatement.setString(2, "user-dev");
            userStatement.setString(3, "开发用户");
            userStatement.setString(4, "");
            userStatement.setString(5, "ACTIVE");
            userStatement.setTimestamp(6, occurredAt);
            userStatement.setTimestamp(7, occurredAt);
            userStatement.executeUpdate();
            statement.setString(1, "identity-audit-1");
            statement.setString(2, "demo");
            statement.setString(3, "user-dev");
            statement.setString(4, "ADMIN");
            statement.setString(5, "IDENTITY_LOGIN");
            statement.setString(6, "IDENTITY_SESSION");
            statement.setString(7, "session-1");
            statement.setString(8, "ALLOW");
            statement.setString(9, "用户登录");
            statement.setTimestamp(10, occurredAt);
            statement.setTimestamp(11, occurredAt);
            statement.setString(12, null);
            statement.setString(13, null);
            statement.setString(14, null);
            statement.setTimestamp(15, occurredAt);
            statement.setInt(16, 0);
            statement.executeUpdate();
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
