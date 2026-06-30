package com.matrixcode.persistence.application;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
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

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.HashMap;

public class JdbcLocalExecutionStateStore implements LocalExecutionStateStore {

    static final String SLICE_KEY = "local-execution";

    private final JdbcSnapshotRepository repository;
    private final ObjectMapper objectMapper;
    private final PersistenceModeProperties properties;
    private LocalExecutionSnapshot current;
    private boolean loaded;

    public JdbcLocalExecutionStateStore(JdbcSnapshotRepository repository, ObjectMapper objectMapper) {
        this.repository = repository;
        this.objectMapper = objectMapper;
        this.properties = repository.properties();
    }

    @Override
    public synchronized LocalExecutionSnapshot load() {
        ensureLoaded();
        return current;
    }

    @Override
    public synchronized void saveWorkspaces(List<WorkspaceAuthorization> workspaces) {
        ensureLoaded();
        current = new LocalExecutionSnapshot(1, workspaces, current.tasks(), current.taskLogs(), current.auditRecords());
        replaceWorkspaces(workspaces);
    }

    @Override
    public synchronized void saveTasks(
            Map<String, List<ExecutionTask>> tasks,
            Map<String, Map<String, List<LocalTaskLog>>> taskLogs
    ) {
        ensureLoaded();
        current = new LocalExecutionSnapshot(1, current.workspaces(), tasks, taskLogs, current.auditRecords());
        replaceTasks(tasks, taskLogs);
    }

    @Override
    public synchronized void saveAuditRecords(List<AuditRecord> auditRecords) {
        ensureLoaded();
        current = new LocalExecutionSnapshot(1, current.workspaces(), current.tasks(), current.taskLogs(), auditRecords);
        replaceAuditRecords(auditRecords, current.tasks());
    }

    private void ensureLoaded() {
        if (loaded) {
            return;
        }
        current = readSnapshot();
        loaded = true;
    }

    private LocalExecutionSnapshot readSnapshot() {
        var formal = readFormalSnapshot();
        if (hasData(formal)) {
            return formal;
        }
        var legacy = readLegacySnapshot();
        if (hasData(legacy)) {
            writeAll(legacy);
            return legacy;
        }
        return LocalExecutionSnapshot.empty();
    }

    private LocalExecutionSnapshot readLegacySnapshot() {
        return repository.load(SLICE_KEY)
                .filter(snapshot -> snapshot.version() == 1)
                .map(snapshot -> read(snapshot.payload()))
                .orElseGet(LocalExecutionSnapshot::empty);
    }

    private LocalExecutionSnapshot read(String payload) {
        try {
            var snapshot = objectMapper.readValue(payload, LocalExecutionSnapshot.class);
            if (snapshot.version() != 1) {
                return LocalExecutionSnapshot.empty();
            }
            return snapshot;
        } catch (JsonProcessingException | RuntimeException ignored) {
            return LocalExecutionSnapshot.empty();
        }
    }

    private LocalExecutionSnapshot readFormalSnapshot() {
        try (var connection = connection()) {
            var workspaces = readWorkspaces(connection);
            var tasks = readTasks(connection);
            var logs = readTaskLogs(connection);
            var auditRecords = readAuditRecords(connection);
            return new LocalExecutionSnapshot(1, workspaces, tasks, logs, auditRecords);
        } catch (SQLException exception) {
            throw new IllegalStateException("本地执行正式表读取失败：" + exception.getMessage(), exception);
        }
    }

    private List<WorkspaceAuthorization> readWorkspaces(Connection connection) throws SQLException {
        var workspaces = new ArrayList<WorkspaceAuthorization>();
        try (var statement = connection.prepareStatement("""
                select id, project_id, name, root_path, status, created_at, last_accessed_at
                from matrixcode_local_workspaces
                order by created_at, id
                """);
             var resultSet = statement.executeQuery()) {
            while (resultSet.next()) {
                workspaces.add(new WorkspaceAuthorization(
                        resultSet.getString("id"),
                        resultSet.getString("project_id"),
                        resultSet.getString("name"),
                        resultSet.getString("root_path"),
                        WorkspaceStatus.valueOf(resultSet.getString("status")),
                        instant(resultSet.getTimestamp("created_at")),
                        instant(resultSet.getTimestamp("last_accessed_at"))
                ));
            }
        }
        return workspaces;
    }

    private Map<String, List<ExecutionTask>> readTasks(Connection connection) throws SQLException {
        var tasks = new LinkedHashMap<String, List<ExecutionTask>>();
        try (var statement = connection.prepareStatement("""
                select id, project_id, workspace_id, requested_by_role, tool_type, command_text,
                       approval_decision, status, exit_code, stdout_summary, stderr_summary,
                       duration_millis, created_at, approver_id, approval_note, decided_at,
                       safety_rejection_reason, canceled_by, cancel_note, canceled_at
                from matrixcode_local_execution_tasks
                order by project_id, sort_order, updated_at desc, created_at desc, id
                """);
             var resultSet = statement.executeQuery()) {
            while (resultSet.next()) {
                var projectId = resultSet.getString("project_id");
                tasks.computeIfAbsent(projectId, ignored -> new ArrayList<>()).add(new ExecutionTask(
                        resultSet.getString("id"),
                        projectId,
                        resultSet.getString("workspace_id"),
                        resultSet.getString("requested_by_role"),
                        textOr(resultSet.getString("tool_type"), "SHELL"),
                        resultSet.getString("command_text"),
                        enumOr(resultSet.getString("approval_decision"), ApprovalDecision.ASK),
                        ExecutionTaskStatus.valueOf(resultSet.getString("status")),
                        integer(resultSet, "exit_code"),
                        textOr(resultSet.getString("stdout_summary"), ""),
                        textOr(resultSet.getString("stderr_summary"), ""),
                        longOr(resultSet, "duration_millis", 0),
                        instant(resultSet.getTimestamp("created_at")),
                        textOr(resultSet.getString("approver_id"), ""),
                        textOr(resultSet.getString("approval_note"), ""),
                        instantOrNull(resultSet.getTimestamp("decided_at")),
                        textOr(resultSet.getString("safety_rejection_reason"), ""),
                        textOr(resultSet.getString("canceled_by"), ""),
                        textOr(resultSet.getString("cancel_note"), ""),
                        instantOrNull(resultSet.getTimestamp("canceled_at"))
                ));
            }
        }
        return copyListMap(tasks);
    }

    private Map<String, Map<String, List<LocalTaskLog>>> readTaskLogs(Connection connection) throws SQLException {
        var logs = new LinkedHashMap<String, Map<String, List<LocalTaskLog>>>();
        try (var statement = connection.prepareStatement("""
                select id, project_id, task_id, stream, content, created_at
                from matrixcode_local_task_logs
                order by project_id, task_id, sort_order, created_at desc, id
                """);
             var resultSet = statement.executeQuery()) {
            while (resultSet.next()) {
                var projectId = resultSet.getString("project_id");
                var taskId = resultSet.getString("task_id");
                logs.computeIfAbsent(projectId, ignored -> new LinkedHashMap<>())
                        .computeIfAbsent(taskId, ignored -> new ArrayList<>())
                        .add(new LocalTaskLog(
                                resultSet.getString("id"),
                                projectId,
                                taskId,
                                LocalTaskLogStream.valueOf(resultSet.getString("stream")),
                                textOr(resultSet.getString("content"), ""),
                                instant(resultSet.getTimestamp("created_at"))
                        ));
            }
        }
        return copyNestedListMap(logs);
    }

    private List<AuditRecord> readAuditRecords(Connection connection) throws SQLException {
        var records = new ArrayList<AuditRecord>();
        try (var statement = connection.prepareStatement("""
                select id, task_id, actor_user_id, actor_role, action_key, tool_type, target_id,
                       workspace_path, summary, decision, occurred_at, created_at
                from matrixcode_audit_records
                order by sort_order, created_at, id
                """);
             var resultSet = statement.executeQuery()) {
            while (resultSet.next()) {
                records.add(new AuditRecord(
                        resultSet.getString("id"),
                        textOr(resultSet.getString("task_id"), resultSet.getString("target_id")),
                        textOr(resultSet.getString("actor_role"), resultSet.getString("actor_user_id")),
                        textOr(resultSet.getString("tool_type"), resultSet.getString("action_key")),
                        textOr(resultSet.getString("workspace_path"), ""),
                        textOr(resultSet.getString("summary"), ""),
                        enumOr(resultSet.getString("decision"), ApprovalDecision.ASK),
                        instant(resultSet.getTimestamp("occurred_at") == null
                                ? resultSet.getTimestamp("created_at")
                                : resultSet.getTimestamp("occurred_at"))
                ));
            }
        }
        return records;
    }

    private void writeAll(LocalExecutionSnapshot snapshot) {
        try (var connection = connection()) {
            connection.setAutoCommit(false);
            try {
                replaceWorkspaces(connection, snapshot.workspaces());
                replaceTasks(connection, snapshot.tasks(), snapshot.taskLogs());
                replaceAuditRecords(connection, snapshot.auditRecords(), snapshot.tasks());
                connection.commit();
            } catch (SQLException | RuntimeException exception) {
                connection.rollback();
                throw exception;
            } finally {
                connection.setAutoCommit(true);
            }
        } catch (SQLException exception) {
            throw new IllegalStateException("本地执行正式表回填失败：" + exception.getMessage(), exception);
        }
    }

    private void replaceWorkspaces(List<WorkspaceAuthorization> workspaces) {
        try (var connection = connection()) {
            connection.setAutoCommit(false);
            try {
                replaceWorkspaces(connection, workspaces);
                connection.commit();
            } catch (SQLException | RuntimeException exception) {
                connection.rollback();
                throw exception;
            } finally {
                connection.setAutoCommit(true);
            }
        } catch (SQLException exception) {
            throw new IllegalStateException("工作区正式表写入失败：" + exception.getMessage(), exception);
        }
    }

    private void replaceWorkspaces(Connection connection, List<WorkspaceAuthorization> workspaces) throws SQLException {
        try (var statement = connection.prepareStatement("delete from matrixcode_local_workspaces")) {
            statement.executeUpdate();
        }
        for (var workspace : workspaces) {
            ensureProject(connection, workspace.projectId());
            try (var statement = connection.prepareStatement("""
                    insert into matrixcode_local_workspaces
                        (id, project_id, name, root_path, status, created_at, last_accessed_at, updated_at)
                    values (?, ?, ?, ?, ?, ?, ?, CURRENT_TIMESTAMP)
                    """)) {
                statement.setString(1, workspace.id());
                statement.setString(2, workspace.projectId());
                statement.setString(3, workspace.name());
                statement.setString(4, workspace.rootPath());
                statement.setString(5, workspace.status().name());
                statement.setTimestamp(6, timestamp(workspace.createdAt()));
                statement.setTimestamp(7, timestamp(workspace.lastAccessedAt()));
                statement.executeUpdate();
            }
        }
    }

    private void replaceTasks(
            Map<String, List<ExecutionTask>> tasks,
            Map<String, Map<String, List<LocalTaskLog>>> taskLogs
    ) {
        try (var connection = connection()) {
            connection.setAutoCommit(false);
            try {
                replaceTasks(connection, tasks, taskLogs);
                connection.commit();
            } catch (SQLException | RuntimeException exception) {
                connection.rollback();
                throw exception;
            } finally {
                connection.setAutoCommit(true);
            }
        } catch (SQLException exception) {
            throw new IllegalStateException("本地执行任务正式表写入失败：" + exception.getMessage(), exception);
        }
    }

    private void replaceTasks(
            Connection connection,
            Map<String, List<ExecutionTask>> tasks,
            Map<String, Map<String, List<LocalTaskLog>>> taskLogs
    ) throws SQLException {
        try (var statement = connection.prepareStatement("delete from matrixcode_local_task_logs")) {
            statement.executeUpdate();
        }
        try (var statement = connection.prepareStatement("delete from matrixcode_local_execution_tasks")) {
            statement.executeUpdate();
        }
        for (var entry : tasks.entrySet()) {
            var projectId = entry.getKey();
            ensureProject(connection, projectId);
            var index = 0;
            for (var task : entry.getValue()) {
                insertTask(connection, task, index++);
            }
        }
        for (var projectLogs : taskLogs.entrySet()) {
            ensureProject(connection, projectLogs.getKey());
            for (var taskEntry : projectLogs.getValue().entrySet()) {
                var index = 0;
                for (var log : taskEntry.getValue()) {
                    insertLog(connection, log, index++);
                }
            }
        }
    }

    private void insertTask(Connection connection, ExecutionTask task, int index) throws SQLException {
        try (var statement = connection.prepareStatement("""
                insert into matrixcode_local_execution_tasks
                    (id, project_id, workspace_id, requested_by_role, approval_record_id, command_text,
                     status, exit_code, started_at, finished_at, created_at, updated_at, tool_type,
                     approval_decision, stdout_summary, stderr_summary, duration_millis, approver_id,
                     approval_note, decided_at, safety_rejection_reason, canceled_by, cancel_note,
                     canceled_at, sort_order)
                values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, CURRENT_TIMESTAMP, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """)) {
            statement.setString(1, task.taskId());
            statement.setString(2, task.projectId());
            statement.setString(3, task.workspaceId());
            statement.setString(4, task.actorId());
            statement.setString(5, null);
            statement.setString(6, task.command());
            statement.setString(7, task.status().name());
            setInteger(statement, 8, task.exitCode());
            statement.setTimestamp(9, null);
            statement.setTimestamp(10, terminal(task.status()) ? timestamp(task.createdAt()) : null);
            statement.setTimestamp(11, timestamp(task.createdAt()));
            statement.setString(12, task.toolType());
            statement.setString(13, task.approvalDecision().name());
            statement.setString(14, task.stdoutSummary());
            statement.setString(15, task.stderrSummary());
            statement.setLong(16, task.durationMillis());
            statement.setString(17, task.approverId());
            statement.setString(18, task.approvalNote());
            statement.setTimestamp(19, timestampOrNull(task.decidedAt()));
            statement.setString(20, task.safetyRejectionReason());
            statement.setString(21, task.canceledBy());
            statement.setString(22, task.cancelNote());
            statement.setTimestamp(23, timestampOrNull(task.canceledAt()));
            statement.setInt(24, index);
            statement.executeUpdate();
        }
    }

    private void insertLog(Connection connection, LocalTaskLog log, int index) throws SQLException {
        try (var statement = connection.prepareStatement("""
                insert into matrixcode_local_task_logs
                    (id, project_id, task_id, stream, content, sort_order, created_at, updated_at)
                values (?, ?, ?, ?, ?, ?, ?, CURRENT_TIMESTAMP)
                """)) {
            statement.setString(1, log.id());
            statement.setString(2, log.projectId());
            statement.setString(3, log.taskId());
            statement.setString(4, log.stream().name());
            statement.setString(5, log.content());
            statement.setInt(6, index);
            statement.setTimestamp(7, timestamp(log.createdAt()));
            statement.executeUpdate();
        }
    }

    private void replaceAuditRecords(List<AuditRecord> auditRecords, Map<String, List<ExecutionTask>> tasks) {
        try (var connection = connection()) {
            connection.setAutoCommit(false);
            try {
                replaceAuditRecords(connection, auditRecords, tasks);
                connection.commit();
            } catch (SQLException | RuntimeException exception) {
                connection.rollback();
                throw exception;
            } finally {
                connection.setAutoCommit(true);
            }
        } catch (SQLException exception) {
            throw new IllegalStateException("审批审计正式表写入失败：" + exception.getMessage(), exception);
        }
    }

    private void replaceAuditRecords(
            Connection connection,
            List<AuditRecord> auditRecords,
            Map<String, List<ExecutionTask>> tasks
    ) throws SQLException {
        try (var statement = connection.prepareStatement("delete from matrixcode_audit_records")) {
            statement.executeUpdate();
        }
        var index = 0;
        for (var record : auditRecords) {
            var projectId = projectIdForAudit(record, tasks);
            ensureProject(connection, projectId);
            ensureUser(connection, record.actorId());
            try (var statement = connection.prepareStatement("""
                    insert into matrixcode_audit_records
                        (id, project_id, actor_user_id, actor_role, action_key, target_type, target_id,
                         decision, summary, created_at, updated_at, task_id, tool_type, workspace_path,
                         occurred_at, sort_order)
                    values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, CURRENT_TIMESTAMP, ?, ?, ?, ?, ?)
                    """)) {
                statement.setString(1, record.id());
                statement.setString(2, projectId);
                statement.setString(3, record.actorId());
                statement.setString(4, record.actorId());
                statement.setString(5, record.toolType());
                statement.setString(6, "LOCAL_EXECUTION_TASK");
                statement.setString(7, record.taskId());
                statement.setString(8, record.decision().name());
                statement.setString(9, record.summary());
                statement.setTimestamp(10, timestamp(record.occurredAt()));
                statement.setString(11, record.taskId());
                statement.setString(12, record.toolType());
                statement.setString(13, record.workspacePath());
                statement.setTimestamp(14, timestamp(record.occurredAt()));
                statement.setInt(15, index++);
                statement.executeUpdate();
            }
        }
    }

    private void ensureUser(Connection connection, String userId) throws SQLException {
        if (userId == null || userId.isBlank()) {
            return;
        }
        var normalized = userId.trim();
        try (var statement = connection.prepareStatement("""
                update matrixcode_users
                set updated_at = CURRENT_TIMESTAMP
                where id = ?
                """)) {
            statement.setString(1, normalized);
            if (statement.executeUpdate() > 0) {
                return;
            }
        }
        try (var statement = connection.prepareStatement("""
                insert into matrixcode_users
                    (id, username, display_name, email, status, created_at, updated_at)
                values (?, ?, ?, ?, ?, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
                """)) {
            statement.setString(1, normalized);
            statement.setString(2, normalized);
            statement.setString(3, normalized);
            statement.setString(4, "");
            statement.setString(5, "ACTIVE");
            statement.executeUpdate();
        }
    }

    private String projectIdForAudit(AuditRecord record, Map<String, List<ExecutionTask>> tasks) {
        return tasks.entrySet().stream()
                .filter(entry -> entry.getValue().stream().anyMatch(task -> task.taskId().equals(record.taskId())))
                .map(Map.Entry::getKey)
                .findFirst()
                .orElse("local-execution");
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
            statement.setString(6, "本地执行与审批审计");
            statement.executeUpdate();
        }
    }

    private boolean hasData(LocalExecutionSnapshot snapshot) {
        return !snapshot.workspaces().isEmpty()
                || !snapshot.tasks().isEmpty()
                || !snapshot.taskLogs().isEmpty()
                || !snapshot.auditRecords().isEmpty();
    }

    private Connection connection() throws SQLException {
        var jdbc = properties.getJdbc();
        if (jdbc.getUrl().isBlank()) {
            throw new IllegalStateException("JDBC URL 不能为空");
        }
        return JdbcConnectionFactory.open(jdbc);
    }

    private Timestamp timestamp(Instant instant) {
        return Timestamp.from(instant == null ? Instant.EPOCH : instant);
    }

    private Timestamp timestampOrNull(Instant instant) {
        return instant == null ? null : Timestamp.from(instant);
    }

    private Instant instant(Timestamp timestamp) {
        return timestamp == null ? Instant.EPOCH : timestamp.toInstant();
    }

    private Instant instantOrNull(Timestamp timestamp) {
        return timestamp == null ? null : timestamp.toInstant();
    }

    private Integer integer(ResultSet resultSet, String column) throws SQLException {
        var value = resultSet.getInt(column);
        return resultSet.wasNull() ? null : value;
    }

    private long longOr(ResultSet resultSet, String column, long fallback) throws SQLException {
        var value = resultSet.getLong(column);
        return resultSet.wasNull() ? fallback : value;
    }

    private <T extends Enum<T>> T enumOr(String value, T fallback) {
        if (value == null || value.isBlank()) {
            return fallback;
        }
        return Enum.valueOf(fallback.getDeclaringClass(), value);
    }

    private String textOr(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }

    private void setInteger(java.sql.PreparedStatement statement, int index, Integer value) throws SQLException {
        if (value == null) {
            statement.setNull(index, java.sql.Types.INTEGER);
        } else {
            statement.setInt(index, value);
        }
    }

    private boolean terminal(ExecutionTaskStatus status) {
        return status == ExecutionTaskStatus.SUCCESS
                || status == ExecutionTaskStatus.FAILED
                || status == ExecutionTaskStatus.CANCELED
                || status == ExecutionTaskStatus.DENIED;
    }

    private Map<String, List<ExecutionTask>> copyListMap(Map<String, List<ExecutionTask>> source) {
        var copy = new HashMap<String, List<ExecutionTask>>();
        source.forEach((key, value) -> copy.put(key, List.copyOf(value)));
        return Map.copyOf(copy);
    }

    private Map<String, Map<String, List<LocalTaskLog>>> copyNestedListMap(
            Map<String, Map<String, List<LocalTaskLog>>> source
    ) {
        var copy = new HashMap<String, Map<String, List<LocalTaskLog>>>();
        source.forEach((projectId, logsByTask) -> {
            var nested = new HashMap<String, List<LocalTaskLog>>();
            logsByTask.forEach((taskId, logs) -> nested.put(taskId, List.copyOf(logs)));
            copy.put(projectId, Map.copyOf(nested));
        });
        return Map.copyOf(copy);
    }
}
