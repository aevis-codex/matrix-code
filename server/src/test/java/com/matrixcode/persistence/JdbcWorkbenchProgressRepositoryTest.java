package com.matrixcode.persistence;

import com.matrixcode.persistence.application.JdbcWorkbenchProgressRepository;
import com.matrixcode.persistence.application.PersistenceModeProperties;
import com.matrixcode.workbench.application.WorkbenchStateSnapshot;
import com.matrixcode.workflow.domain.WorkflowEvent;
import com.matrixcode.workflow.domain.WorkflowEventType;
import com.matrixcode.workflow.domain.WorkflowItem;
import com.matrixcode.workflow.domain.WorkflowState;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;

import java.sql.DriverManager;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class JdbcWorkbenchProgressRepositoryTest {

    @Test
    void 保存后从正式进度表恢复工作流和验收投影() throws Exception {
        var jdbcUrl = "jdbc:h2:mem:workbench_progress_" + System.nanoTime()
                + ";MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1";
        migrate(jdbcUrl);
        var repository = new JdbcWorkbenchProgressRepository(properties(jdbcUrl));
        var item = workflowItem("workflow-1", WorkflowState.REVIEW_PENDING);
        var event = workflowEvent("workflow-event-1", "workflow-1");
        var acceptance = new WorkbenchStateSnapshot.AcceptanceState("doc-1", false, "测试");

        repository.saveWorkflowItems(List.of(item));
        repository.saveWorkflowEvents(Map.of("workflow-1", List.of(event)));
        repository.saveAcceptances(Map.of("demo", acceptance));

        assertThat(repository.loadWorkflowItems()).containsExactly(item);
        assertThat(repository.loadWorkflowEvents().get("workflow-1")).containsExactly(event);
        assertThat(repository.loadAcceptances().get("demo")).isEqualTo(acceptance);
        assertThat(projectCount(jdbcUrl)).isEqualTo(1);
    }

    private void migrate(String jdbcUrl) {
        Flyway.configure()
                .dataSource(jdbcUrl, "sa", "")
                .locations("classpath:db/migration")
                .load()
                .migrate();
    }

    private PersistenceModeProperties properties(String jdbcUrl) {
        var properties = new PersistenceModeProperties();
        properties.setMode("jdbc");
        properties.getJdbc().setUrl(jdbcUrl);
        properties.getJdbc().setUsername("sa");
        properties.getJdbc().setPassword("");
        return properties;
    }

    private int projectCount(String jdbcUrl) throws Exception {
        try (var connection = DriverManager.getConnection(jdbcUrl, "sa", "");
             var statement = connection.prepareStatement("select count(*) from matrixcode_projects");
             var resultSet = statement.executeQuery()) {
            resultSet.next();
            return resultSet.getInt(1);
        }
    }

    private WorkflowItem workflowItem(String id, WorkflowState state) {
        return new WorkflowItem(id, "demo", "PRD-001", state);
    }

    private WorkflowEvent workflowEvent(String id, String itemId) {
        return new WorkflowEvent(
                id,
                itemId,
                WorkflowEventType.SUBMIT_REVIEW,
                WorkflowState.DRAFT,
                WorkflowState.REVIEW_PENDING,
                "user-product",
                Instant.parse("2026-06-25T12:00:00Z")
        );
    }
}
