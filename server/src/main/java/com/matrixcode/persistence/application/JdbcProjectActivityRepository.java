package com.matrixcode.persistence.application;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.matrixcode.modelgateway.domain.ModelRequestRecord;
import com.matrixcode.modelgateway.domain.ModelRole;
import com.matrixcode.realtime.domain.ProjectEvent;
import com.matrixcode.usage.domain.UsageRecord;
import com.matrixcode.workbench.application.ProjectActivityRepository;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.sql.Types;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class JdbcProjectActivityRepository implements ProjectActivityRepository {

    private static final TypeReference<List<String>> STRING_LIST = new TypeReference<>() {
    };

    private final PersistenceModeProperties properties;
    private final DatabaseMigrationService migrationService;
    private final ObjectMapper objectMapper;
    private boolean migrated;

    public JdbcProjectActivityRepository(
            PersistenceModeProperties properties,
            DatabaseMigrationService migrationService,
            ObjectMapper objectMapper
    ) {
        this.properties = properties;
        this.migrationService = migrationService;
        this.objectMapper = objectMapper;
    }

    public JdbcProjectActivityRepository(PersistenceModeProperties properties) {
        this.properties = properties;
        this.migrationService = null;
        this.objectMapper = new ObjectMapper();
    }

    @Override
    public Map<String, List<ModelRequestRecord>> loadModelRequests() {
        ensureSchema();
        try (var connection = connection();
             var statement = connection.prepareStatement("""
                     select id, project_id, role_key, provider_id, model_name, answer_summary, actor_user_id,
                            agent_run_id,
                            usage_role_session_id, usage_model, cache_hit_tokens, cache_miss_input_tokens,
                            output_tokens, cache_hit_rate, estimated_cost, currency, cache_source, cache_scope_id,
                            stable_prefix_hash, provider_usage_available, cache_policy_id, volatile_suffix_strategy,
                            prompt_partition_policy_id, prompt_partition_fingerprint,
                            stable_partition_count, volatile_partition_count,
                            context_types, created_at
                     from matrixcode_model_requests
                     order by project_id, created_at, id
                     """);
             var resultSet = statement.executeQuery()) {
            var grouped = new LinkedHashMap<String, List<ModelRequestRecord>>();
            while (resultSet.next()) {
                var usage = new UsageRecord(
                        resultSet.getString("usage_role_session_id"),
                        resultSet.getString("usage_model"),
                        resultSet.getLong("cache_hit_tokens"),
                        resultSet.getLong("cache_miss_input_tokens"),
                        resultSet.getLong("output_tokens"),
                        resultSet.getDouble("cache_hit_rate"),
                        resultSet.getDouble("estimated_cost"),
                        resultSet.getString("currency"),
                        resultSet.getString("cache_source"),
                        resultSet.getString("cache_scope_id"),
                        resultSet.getString("stable_prefix_hash"),
                        resultSet.getBoolean("provider_usage_available"),
                        resultSet.getString("cache_policy_id"),
                        resultSet.getString("volatile_suffix_strategy"),
                        resultSet.getString("prompt_partition_policy_id"),
                        resultSet.getString("prompt_partition_fingerprint"),
                        resultSet.getInt("stable_partition_count"),
                        resultSet.getInt("volatile_partition_count")
                );
                var record = new ModelRequestRecord(
                        resultSet.getString("id"),
                        resultSet.getString("project_id"),
                        ModelRole.valueOf(resultSet.getString("role_key")),
                        resultSet.getString("provider_id"),
                        resultSet.getString("model_name"),
                        resultSet.getString("answer_summary"),
                        textOr(resultSet.getString("actor_user_id"), ""),
                        textOr(resultSet.getString("agent_run_id"), ""),
                        usage,
                        contextTypes(resultSet.getString("context_types")),
                        instant(resultSet.getTimestamp("created_at"))
                );
                grouped.computeIfAbsent(record.projectId(), ignored -> new ArrayList<>()).add(record);
            }
            return immutableGrouped(grouped);
        } catch (SQLException exception) {
            throw new IllegalStateException("模型请求表读取失败：" + exception.getMessage(), exception);
        }
    }

    @Override
    public void saveModelRequests(Map<String, List<ModelRequestRecord>> requests) {
        if (requests == null || requests.isEmpty()) {
            return;
        }
        ensureSchema();
        try (var connection = connection()) {
            connection.setAutoCommit(false);
            try {
                for (var entry : requests.entrySet()) {
                    ensureProject(connection, entry.getKey(), "模型请求");
                    deleteByProject(connection, "matrixcode_model_requests", entry.getKey());
                    for (var record : entry.getValue()) {
                        insertModelRequest(connection, record);
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
            throw new IllegalStateException("模型请求表写入失败：" + exception.getMessage(), exception);
        }
    }

    @Override
    public Map<String, List<ProjectEvent>> loadProjectEvents() {
        ensureSchema();
        try (var connection = connection();
             var statement = connection.prepareStatement("""
                     select id, project_id, event_type, source_role, source_id, title, payload, created_at
                     from matrixcode_project_events
                     order by project_id, created_at, id
                     """);
             var resultSet = statement.executeQuery()) {
            var grouped = new LinkedHashMap<String, List<ProjectEvent>>();
            while (resultSet.next()) {
                var message = resultSet.getString("title");
                if (message == null || message.isBlank()) {
                    message = resultSet.getString("payload");
                }
                var event = new ProjectEvent(
                        resultSet.getString("id"),
                        resultSet.getString("project_id"),
                        resultSet.getString("event_type"),
                        message == null ? "" : message,
                        instant(resultSet.getTimestamp("created_at")),
                        textOr(resultSet.getString("source_role"), ""),
                        textOr(resultSet.getString("source_id"), "")
                );
                grouped.computeIfAbsent(event.projectId(), ignored -> new ArrayList<>()).add(event);
            }
            return immutableGrouped(grouped);
        } catch (SQLException exception) {
            throw new IllegalStateException("项目事件表读取失败：" + exception.getMessage(), exception);
        }
    }

    @Override
    public void saveProjectEvents(Map<String, List<ProjectEvent>> events) {
        if (events == null || events.isEmpty()) {
            return;
        }
        ensureSchema();
        try (var connection = connection()) {
            connection.setAutoCommit(false);
            try {
                for (var entry : events.entrySet()) {
                    ensureProject(connection, entry.getKey(), "项目事件");
                    deleteByProject(connection, "matrixcode_project_events", entry.getKey());
                    for (var event : entry.getValue()) {
                        insertProjectEvent(connection, event);
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
            throw new IllegalStateException("项目事件表写入失败：" + exception.getMessage(), exception);
        }
    }

    private void insertModelRequest(Connection connection, ModelRequestRecord record) throws SQLException {
        ensureUser(connection, record.actorUserId());
        try (var statement = connection.prepareStatement("""
                insert into matrixcode_model_requests
                    (id, project_id, role_key, provider_id, model_name, answer_summary, actor_user_id,
                     agent_run_id,
                     usage_role_session_id, usage_model, cache_hit_tokens, cache_miss_input_tokens,
                     output_tokens, cache_hit_rate, estimated_cost, currency, cache_source, cache_scope_id,
                     stable_prefix_hash, provider_usage_available, cache_policy_id, volatile_suffix_strategy,
                     prompt_partition_policy_id, prompt_partition_fingerprint,
                     stable_partition_count, volatile_partition_count,
                     context_types, created_at)
                values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """)) {
            statement.setString(1, record.requestId());
            statement.setString(2, record.projectId());
            statement.setString(3, record.role().name());
            statement.setString(4, record.providerId());
            statement.setString(5, record.model());
            statement.setString(6, record.answerSummary());
            setOptionalString(statement, 7, record.actorUserId());
            setOptionalString(statement, 8, record.agentRunId());
            statement.setString(9, record.usage().roleSessionId());
            statement.setString(10, record.usage().model());
            statement.setLong(11, record.usage().cacheHitTokens());
            statement.setLong(12, record.usage().cacheMissInputTokens());
            statement.setLong(13, record.usage().outputTokens());
            statement.setDouble(14, record.usage().cacheHitRate());
            statement.setDouble(15, record.usage().estimatedCost());
            statement.setString(16, record.usage().currency());
            statement.setString(17, record.usage().cacheSource());
            statement.setString(18, record.usage().cacheScopeId());
            statement.setString(19, record.usage().stablePrefixHash());
            statement.setBoolean(20, record.usage().providerUsageAvailable());
            statement.setString(21, record.usage().cachePolicyId());
            statement.setString(22, record.usage().volatileSuffixStrategy());
            statement.setString(23, record.usage().promptPartitionPolicyId());
            statement.setString(24, record.usage().promptPartitionFingerprint());
            statement.setInt(25, record.usage().stablePartitionCount());
            statement.setInt(26, record.usage().volatilePartitionCount());
            statement.setString(27, contextTypesJson(record.contextTypes()));
            statement.setTimestamp(28, timestamp(record.createdAt()));
            statement.executeUpdate();
        }
    }

    private void insertProjectEvent(Connection connection, ProjectEvent event) throws SQLException {
        try (var statement = connection.prepareStatement("""
                insert into matrixcode_project_events
                    (id, project_id, event_type, source_role, source_id, title, payload, created_at, updated_at)
                values (?, ?, ?, ?, ?, ?, ?, ?, ?)
                """)) {
            statement.setString(1, event.id());
            statement.setString(2, event.projectId());
            statement.setString(3, event.type());
            setOptionalString(statement, 4, event.sourceRole());
            setOptionalString(statement, 5, event.sourceId());
            statement.setString(6, event.message());
            statement.setString(7, event.message());
            statement.setTimestamp(8, timestamp(event.occurredAt()));
            statement.setTimestamp(9, timestamp(event.occurredAt()));
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

    private List<String> contextTypes(String raw) {
        if (raw == null || raw.isBlank()) {
            return List.of();
        }
        try {
            return objectMapper.readValue(raw, STRING_LIST);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("模型请求上下文类型解析失败：" + exception.getMessage(), exception);
        }
    }

    private String contextTypesJson(List<String> contextTypes) {
        try {
            return objectMapper.writeValueAsString(contextTypes == null ? List.of() : contextTypes);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("模型请求上下文类型序列化失败：" + exception.getMessage(), exception);
        }
    }

    private Timestamp timestamp(Instant instant) {
        return Timestamp.from(instant == null ? Instant.EPOCH : instant);
    }

    private void setOptionalString(java.sql.PreparedStatement statement, int index, String value) throws SQLException {
        if (value == null || value.isBlank()) {
            statement.setNull(index, Types.VARCHAR);
            return;
        }
        statement.setString(index, value.trim());
    }

    private String textOr(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value.trim();
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
