package com.matrixcode.persistence.application;

import com.baomidou.mybatisplus.core.incrementer.DefaultIdentifierGenerator;
import com.matrixcode.workbench.application.WorkbenchProgressRepository;
import com.matrixcode.workbench.application.WorkbenchStateSnapshot;
import com.matrixcode.workflow.domain.WorkflowEvent;
import com.matrixcode.workflow.domain.WorkflowEventType;
import com.matrixcode.workflow.domain.WorkflowItem;
import com.matrixcode.workflow.domain.WorkflowState;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

public class JdbcWorkbenchProgressRepository implements WorkbenchProgressRepository {

    private static final DefaultIdentifierGenerator ID_GENERATOR = DefaultIdentifierGenerator.getInstance();

    private final PersistenceModeProperties properties;
    private final DatabaseMigrationService migrationService;
    private boolean migrated;

    public JdbcWorkbenchProgressRepository(
            PersistenceModeProperties properties,
            DatabaseMigrationService migrationService
    ) {
        this.properties = properties;
        this.migrationService = migrationService;
    }

    public JdbcWorkbenchProgressRepository(PersistenceModeProperties properties) {
        this.properties = properties;
        this.migrationService = null;
    }

    @Override
    public List<WorkflowItem> loadWorkflowItems() {
        ensureSchema();
        try (var connection = connection();
             var statement = connection.prepareStatement("""
                     select id, project_id, title, state
                     from matrixcode_workflow_items
                     order by project_id, updated_at, id
                     """);
             var resultSet = statement.executeQuery()) {
            var items = new ArrayList<WorkflowItem>();
            while (resultSet.next()) {
                items.add(new WorkflowItem(
                        resultSet.getString("id"),
                        resultSet.getString("project_id"),
                        resultSet.getString("title"),
                        WorkflowState.valueOf(resultSet.getString("state"))
                ));
            }
            return List.copyOf(items);
        } catch (SQLException exception) {
            throw new IllegalStateException("工作流工作项表读取失败：" + exception.getMessage(), exception);
        }
    }

    @Override
    public void saveWorkflowItems(List<WorkflowItem> items) {
        if (items == null || items.isEmpty()) {
            return;
        }
        ensureSchema();
        try (var connection = connection()) {
            connection.setAutoCommit(false);
            try {
                for (var item : items) {
                    ensureProject(connection, item.projectId(), "工作流");
                    if (updateWorkflowItem(connection, item) == 0) {
                        insertWorkflowItem(connection, item);
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
            throw new IllegalStateException("工作流工作项表写入失败：" + exception.getMessage(), exception);
        }
    }

    @Override
    public Map<String, List<WorkflowEvent>> loadWorkflowEvents() {
        ensureSchema();
        try (var connection = connection();
             var statement = connection.prepareStatement("""
                     select id, item_id, event_type, from_state, to_state, actor_id, occurred_at
                     from matrixcode_workflow_events
                     order by item_id, occurred_at, id
                     """);
             var resultSet = statement.executeQuery()) {
            var grouped = new LinkedHashMap<String, List<WorkflowEvent>>();
            while (resultSet.next()) {
                var event = new WorkflowEvent(
                        resultSet.getString("id"),
                        resultSet.getString("item_id"),
                        WorkflowEventType.valueOf(resultSet.getString("event_type")),
                        WorkflowState.valueOf(resultSet.getString("from_state")),
                        WorkflowState.valueOf(resultSet.getString("to_state")),
                        resultSet.getString("actor_id"),
                        instant(resultSet.getTimestamp("occurred_at"))
                );
                grouped.computeIfAbsent(event.itemId(), ignored -> new ArrayList<>()).add(event);
            }
            return immutableGrouped(grouped);
        } catch (SQLException exception) {
            throw new IllegalStateException("工作流事件表读取失败：" + exception.getMessage(), exception);
        }
    }

    @Override
    public void saveWorkflowEvents(Map<String, List<WorkflowEvent>> events) {
        if (events == null || events.isEmpty()) {
            return;
        }
        ensureSchema();
        try (var connection = connection()) {
            connection.setAutoCommit(false);
            try {
                for (var entry : events.entrySet()) {
                    var projectId = projectIdForItem(connection, entry.getKey());
                    if (projectId == null) {
                        continue;
                    }
                    deleteEventsForItem(connection, entry.getKey());
                    for (var event : entry.getValue()) {
                        insertWorkflowEvent(connection, projectId, event);
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
            throw new IllegalStateException("工作流事件表写入失败：" + exception.getMessage(), exception);
        }
    }

    @Override
    public Map<String, WorkbenchStateSnapshot.AcceptanceState> loadAcceptances() {
        ensureSchema();
        try (var connection = connection();
             var statement = connection.prepareStatement("""
                     select project_id, document_id, accepted, return_to_role
                     from matrixcode_acceptance_states
                     order by project_id
                     """);
             var resultSet = statement.executeQuery()) {
            var acceptances = new LinkedHashMap<String, WorkbenchStateSnapshot.AcceptanceState>();
            while (resultSet.next()) {
                acceptances.put(resultSet.getString("project_id"), new WorkbenchStateSnapshot.AcceptanceState(
                        resultSet.getString("document_id"),
                        resultSet.getBoolean("accepted"),
                        resultSet.getString("return_to_role")
                ));
            }
            return Map.copyOf(acceptances);
        } catch (SQLException exception) {
            throw new IllegalStateException("验收投影表读取失败：" + exception.getMessage(), exception);
        }
    }

    @Override
    public void saveAcceptances(Map<String, WorkbenchStateSnapshot.AcceptanceState> acceptances) {
        if (acceptances == null || acceptances.isEmpty()) {
            return;
        }
        ensureSchema();
        try (var connection = connection()) {
            connection.setAutoCommit(false);
            try {
                for (var entry : acceptances.entrySet()) {
                    ensureProject(connection, entry.getKey(), "验收");
                    upsertAcceptance(connection, entry.getKey(), entry.getValue());
                }
                connection.commit();
            } catch (SQLException | RuntimeException exception) {
                connection.rollback();
                throw exception;
            } finally {
                connection.setAutoCommit(true);
            }
        } catch (SQLException exception) {
            throw new IllegalStateException("验收投影表写入失败：" + exception.getMessage(), exception);
        }
    }

    private int updateWorkflowItem(Connection connection, WorkflowItem item) throws SQLException {
        try (var statement = connection.prepareStatement("""
                update matrixcode_workflow_items
                set project_id = ?, title = ?, state = ?, updated_at = CURRENT_TIMESTAMP
                where id = ?
                """)) {
            statement.setString(1, item.projectId());
            statement.setString(2, item.title());
            statement.setString(3, item.state().name());
            statement.setString(4, item.id());
            return statement.executeUpdate();
        }
    }

    private void insertWorkflowItem(Connection connection, WorkflowItem item) throws SQLException {
        try (var statement = connection.prepareStatement("""
                insert into matrixcode_workflow_items
                    (id, project_id, title, state, created_at, updated_at)
                values (?, ?, ?, ?, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
                """)) {
            statement.setString(1, item.id());
            statement.setString(2, item.projectId());
            statement.setString(3, item.title());
            statement.setString(4, item.state().name());
            statement.executeUpdate();
        }
    }

    private void insertWorkflowEvent(Connection connection, String projectId, WorkflowEvent event) throws SQLException {
        try (var statement = connection.prepareStatement("""
                insert into matrixcode_workflow_events
                    (id, project_id, item_id, event_type, from_state, to_state, actor_id, occurred_at)
                values (?, ?, ?, ?, ?, ?, ?, ?)
                """)) {
            statement.setString(1, event.id());
            statement.setString(2, projectId);
            statement.setString(3, event.itemId());
            statement.setString(4, event.type().name());
            statement.setString(5, event.fromState().name());
            statement.setString(6, event.toState().name());
            statement.setString(7, event.actorId());
            statement.setTimestamp(8, timestamp(event.occurredAt()));
            statement.executeUpdate();
        }
    }

    private void upsertAcceptance(
            Connection connection,
            String projectId,
            WorkbenchStateSnapshot.AcceptanceState acceptance
    ) throws SQLException {
        Objects.requireNonNull(acceptance, "acceptance 不能为空");
        try (var statement = connection.prepareStatement("""
                update matrixcode_acceptance_states
                set document_id = ?, accepted = ?, return_to_role = ?, updated_at = CURRENT_TIMESTAMP
                where project_id = ?
                """)) {
            statement.setString(1, acceptance.documentId());
            statement.setBoolean(2, acceptance.accepted());
            statement.setString(3, acceptance.returnToRole());
            statement.setString(4, projectId);
            if (statement.executeUpdate() > 0) {
                return;
            }
        }
        try (var statement = connection.prepareStatement("""
                insert into matrixcode_acceptance_states
                    (id, project_id, document_id, accepted, return_to_role, updated_at)
                values (?, ?, ?, ?, ?, CURRENT_TIMESTAMP)
                """)) {
            statement.setString(1, String.valueOf(ID_GENERATOR.nextId(null)));
            statement.setString(2, projectId);
            statement.setString(3, acceptance.documentId());
            statement.setBoolean(4, acceptance.accepted());
            statement.setString(5, acceptance.returnToRole());
            statement.executeUpdate();
        }
    }

    private String projectIdForItem(Connection connection, String itemId) throws SQLException {
        try (var statement = connection.prepareStatement("""
                select project_id
                from matrixcode_workflow_items
                where id = ?
                """)) {
            statement.setString(1, itemId);
            try (var resultSet = statement.executeQuery()) {
                if (resultSet.next()) {
                    return resultSet.getString("project_id");
                }
                return null;
            }
        }
    }

    private void deleteEventsForItem(Connection connection, String itemId) throws SQLException {
        try (var statement = connection.prepareStatement("delete from matrixcode_workflow_events where item_id = ?")) {
            statement.setString(1, itemId);
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

    private Timestamp timestamp(Instant instant) {
        return Timestamp.from(instant == null ? Instant.EPOCH : instant);
    }

    private Instant instant(Timestamp timestamp) {
        return timestamp == null ? Instant.EPOCH : timestamp.toInstant();
    }

    private <T> Map<String, List<T>> immutableGrouped(Map<String, List<T>> grouped) {
        var copy = new LinkedHashMap<String, List<T>>();
        grouped.forEach((key, records) -> copy.put(key, List.copyOf(records)));
        return Map.copyOf(copy);
    }
}
