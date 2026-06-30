package com.matrixcode.persistence.application;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.matrixcode.runtime.application.RuntimeNotificationSnapshot;
import com.matrixcode.runtime.application.RuntimeNotificationStore;
import com.matrixcode.runtime.domain.RuntimeNotification;
import com.matrixcode.runtime.domain.RuntimeNotificationLevel;
import com.matrixcode.runtime.domain.RuntimeNotificationSourceType;

import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.sql.Types;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

public class JdbcRuntimeNotificationStore implements RuntimeNotificationStore {

    static final String SLICE_KEY = "runtime-notifications";

    private final JdbcSnapshotRepository repository;
    private final ObjectMapper objectMapper;
    private final PersistenceModeProperties properties;

    public JdbcRuntimeNotificationStore(JdbcSnapshotRepository repository, ObjectMapper objectMapper) {
        this.repository = repository;
        this.objectMapper = objectMapper;
        this.properties = repository.properties();
    }

    @Override
    public RuntimeNotificationSnapshot load() {
        var formal = readFormalSnapshot();
        if (!formal.projects().isEmpty()) {
            return formal;
        }
        var legacy = readLegacySnapshot();
        if (!legacy.projects().isEmpty()) {
            writeFormalSnapshot(legacy);
            return legacy;
        }
        return RuntimeNotificationSnapshot.empty();
    }

    @Override
    public void save(RuntimeNotificationSnapshot snapshot) {
        writeFormalSnapshot(snapshot);
    }

    private RuntimeNotificationSnapshot readFormalSnapshot() {
        try (var connection = connection();
             var statement = connection.prepareStatement("""
                     select id, project_id, user_id, level_key, title, message, source_type, source_id, created_at, read_at
                     from matrixcode_runtime_notifications
                     order by project_id, created_at desc, id
                     """);
             var resultSet = statement.executeQuery()) {
            var projects = new HashMap<String, List<RuntimeNotification>>();
            while (resultSet.next()) {
                var notification = new RuntimeNotification(
                        resultSet.getString("id"),
                        resultSet.getString("project_id"),
                        RuntimeNotificationLevel.valueOf(resultSet.getString("level_key")),
                        resultSet.getString("title"),
                        resultSet.getString("message"),
                        RuntimeNotificationSourceType.valueOf(resultSet.getString("source_type")),
                        textOr(resultSet.getString("source_id"), resultSet.getString("id")),
                        instant(resultSet.getTimestamp("created_at")),
                        optionalInstant(resultSet.getTimestamp("read_at")),
                        textOr(resultSet.getString("user_id"), "")
                );
                projects.computeIfAbsent(notification.projectId(), ignored -> new ArrayList<>()).add(notification);
            }
            return snapshot(projects, readFormalReceipts());
        } catch (SQLException exception) {
            throw new IllegalStateException("运行态提醒正式表读取失败：" + exception.getMessage(), exception);
        }
    }

    private Map<String, Map<String, Map<String, Instant>>> readFormalReceipts() {
        try (var connection = connection();
             var statement = connection.prepareStatement("""
                     select project_id, notification_id, user_id, read_at
                     from matrixcode_runtime_notification_reads
                     order by project_id, user_id, notification_id
                     """);
             var resultSet = statement.executeQuery()) {
            var receipts = new HashMap<String, Map<String, Map<String, Instant>>>();
            while (resultSet.next()) {
                receipts
                        .computeIfAbsent(resultSet.getString("project_id"), ignored -> new HashMap<>())
                        .computeIfAbsent(resultSet.getString("user_id"), ignored -> new HashMap<>())
                        .put(resultSet.getString("notification_id"), instant(resultSet.getTimestamp("read_at")));
            }
            return receipts;
        } catch (SQLException exception) {
            throw new IllegalStateException("运行态提醒已读回执正式表读取失败：" + exception.getMessage(), exception);
        }
    }

    private RuntimeNotificationSnapshot readLegacySnapshot() {
        return repository.load(SLICE_KEY)
                .filter(snapshot -> snapshot.version() == 1)
                .map(snapshot -> read(snapshot.payload()))
                .orElseGet(RuntimeNotificationSnapshot::empty);
    }

    private void writeFormalSnapshot(RuntimeNotificationSnapshot snapshot) {
        try (var connection = connection()) {
            connection.setAutoCommit(false);
            try {
                try (var statement = connection.prepareStatement("delete from matrixcode_runtime_notification_reads")) {
                    statement.executeUpdate();
                }
                try (var statement = connection.prepareStatement("delete from matrixcode_runtime_notifications")) {
                    statement.executeUpdate();
                }
                var notificationIds = new HashMap<String, Set<String>>();
                for (var notifications : snapshot.projects().values()) {
                    for (var notification : notifications) {
                        ensureProject(connection, notification.projectId());
                        insertNotification(connection, notification);
                        notificationIds
                                .computeIfAbsent(notification.projectId(), ignored -> new HashSet<>())
                                .add(notification.id());
                    }
                }
                insertReadReceipts(connection, snapshot, notificationIds);
                connection.commit();
            } catch (SQLException | RuntimeException exception) {
                connection.rollback();
                throw exception;
            } finally {
                connection.setAutoCommit(true);
            }
        } catch (SQLException exception) {
            throw new IllegalStateException("运行态提醒正式表写入失败：" + exception.getMessage(), exception);
        }
    }

    private void insertReadReceipts(
            Connection connection,
            RuntimeNotificationSnapshot snapshot,
            Map<String, Set<String>> notificationIds
    ) throws SQLException {
        for (var projectEntry : snapshot.readReceipts().entrySet()) {
            var projectId = projectEntry.getKey();
            ensureProject(connection, projectId);
            for (var userEntry : projectEntry.getValue().entrySet()) {
                var userId = userEntry.getKey();
                ensureUser(connection, userId);
                for (var readEntry : userEntry.getValue().entrySet()) {
                    var notificationId = readEntry.getKey();
                    if (readEntry.getValue() == null
                            || !notificationIds.getOrDefault(projectId, Set.of()).contains(notificationId)) {
                        continue;
                    }
                    insertReadReceipt(connection, projectId, notificationId, userId, readEntry.getValue());
                }
            }
        }
    }

    private void insertReadReceipt(
            Connection connection,
            String projectId,
            String notificationId,
            String userId,
            Instant readAt
    ) throws SQLException {
        try (var statement = connection.prepareStatement("""
                insert into matrixcode_runtime_notification_reads
                    (id, project_id, notification_id, user_id, read_at, created_at, updated_at)
                values (?, ?, ?, ?, ?, ?, ?)
            """)) {
            statement.setString(1, readReceiptId(projectId, notificationId, userId));
            statement.setString(2, projectId);
            statement.setString(3, notificationId);
            statement.setString(4, userId);
            statement.setTimestamp(5, timestamp(readAt));
            statement.setTimestamp(6, timestamp(readAt));
            statement.setTimestamp(7, timestamp(readAt));
            statement.executeUpdate();
        }
    }

    private void insertNotification(Connection connection, RuntimeNotification notification) throws SQLException {
        ensureUser(connection, notification.readByUserId());
        try (var statement = connection.prepareStatement("""
                insert into matrixcode_runtime_notifications
                    (id, project_id, user_id, level_key, source_type, source_id, title, message,
                     read_at, created_at, updated_at)
                values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """)) {
            statement.setString(1, notification.id());
            statement.setString(2, notification.projectId());
            setOptionalString(statement, 3, notification.readByUserId());
            statement.setString(4, notification.level().name());
            statement.setString(5, notification.sourceType().name());
            statement.setString(6, notification.sourceId());
            statement.setString(7, notification.title());
            statement.setString(8, notification.message());
            statement.setTimestamp(9, optionalTimestamp(notification.readAt()));
            statement.setTimestamp(10, timestamp(notification.occurredAt()));
            statement.setTimestamp(11, timestamp(updatedAt(notification)));
            statement.executeUpdate();
        }
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
            statement.setString(6, "运行态提醒");
            statement.executeUpdate();
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

    private RuntimeNotificationSnapshot snapshot(
            Map<String, List<RuntimeNotification>> projects,
            Map<String, Map<String, Map<String, Instant>>> readReceipts
    ) {
        var copy = new HashMap<String, List<RuntimeNotification>>();
        projects.forEach((projectId, notifications) -> copy.put(projectId, List.copyOf(notifications)));
        return new RuntimeNotificationSnapshot(1, Map.copyOf(copy), readReceipts);
    }

    private Connection connection() throws SQLException {
        var jdbc = properties.getJdbc();
        if (jdbc.getUrl().isBlank()) {
            throw new IllegalStateException("JDBC URL 不能为空");
        }
        return JdbcConnectionFactory.open(jdbc);
    }

    private Instant updatedAt(RuntimeNotification notification) {
        return notification.readAt() == null ? notification.occurredAt() : notification.readAt();
    }

    private Timestamp timestamp(Instant instant) {
        return Timestamp.from(instant == null ? Instant.EPOCH : instant);
    }

    private Timestamp optionalTimestamp(Instant instant) {
        return instant == null ? null : Timestamp.from(instant);
    }

    private void setOptionalString(java.sql.PreparedStatement statement, int index, String value) throws SQLException {
        if (value == null || value.isBlank()) {
            statement.setNull(index, Types.VARCHAR);
            return;
        }
        statement.setString(index, value.trim());
    }

    private Instant instant(Timestamp timestamp) {
        return timestamp == null ? Instant.EPOCH : timestamp.toInstant();
    }

    private Instant optionalInstant(Timestamp timestamp) {
        return timestamp == null ? null : timestamp.toInstant();
    }

    private String textOr(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }

    private String readReceiptId(String projectId, String notificationId, String userId) {
        return UUID.nameUUIDFromBytes((projectId + ":" + notificationId + ":" + userId)
                .getBytes(StandardCharsets.UTF_8)).toString();
    }

    private RuntimeNotificationSnapshot read(String payload) {
        try {
            var snapshot = objectMapper.readValue(payload, RuntimeNotificationSnapshot.class);
            if (snapshot.version() != 1) {
                return RuntimeNotificationSnapshot.empty();
            }
            return snapshot;
        } catch (JsonProcessingException | RuntimeException ignored) {
            return RuntimeNotificationSnapshot.empty();
        }
    }
}
