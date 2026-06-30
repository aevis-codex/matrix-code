package com.matrixcode.persistence;

import com.matrixcode.MatrixCodeServerApplication;
import com.matrixcode.workbench.application.WorkbenchProgressRepository;
import com.matrixcode.workbench.application.WorkbenchStateSnapshot;
import com.matrixcode.workflow.domain.WorkflowEvent;
import com.matrixcode.workflow.domain.WorkflowEventType;
import com.matrixcode.workflow.domain.WorkflowItem;
import com.matrixcode.workflow.domain.WorkflowState;
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

class MybatisPlusWorkbenchProgressRepositoryTest {

    @TempDir
    Path tempDir;

    @Test
    void Jdbc模式下工作流进度仓储使用MybatisPlus并保持工作流和验收行为() throws Exception {
        var jdbcUrl = jdbcUrl("workbench_progress_mp_");

        try (var context = startJdbcContext(jdbcUrl)) {
            var repository = context.getBean(WorkbenchProgressRepository.class);
            assertThat(repository.getClass().getName())
                    .contains("MybatisPlusWorkbenchProgressRepository");

            var draft = workflowItem("workflow-draft", WorkflowState.DRAFT);
            var review = workflowItem("workflow-review", WorkflowState.REVIEW_PENDING);
            var updatedReview = new WorkflowItem("workflow-review", "demo", "PRD-001 更新", WorkflowState.DONE);
            var reviewEvent = workflowEvent("workflow-event-1", "workflow-review");
            var ignoredEvent = workflowEvent("workflow-event-ignored", "missing-workflow");
            var acceptance = new WorkbenchStateSnapshot.AcceptanceState("doc-1", false, "测试");
            var updatedAcceptance = new WorkbenchStateSnapshot.AcceptanceState("doc-2", true, "产品");

            repository.saveWorkflowItems(List.of(draft, review));
            repository.saveWorkflowItems(List.of(updatedReview));
            repository.saveWorkflowEvents(Map.of(
                    "workflow-review", List.of(reviewEvent),
                    "missing-workflow", List.of(ignoredEvent)
            ));
            repository.saveAcceptances(Map.of("demo", acceptance));
            repository.saveAcceptances(Map.of("demo", updatedAcceptance));

            assertThat(repository.loadWorkflowItems())
                    .extracting(WorkflowItem::id)
                    .containsExactly("workflow-draft", "workflow-review");
            assertThat(repository.loadWorkflowItems().stream()
                    .filter(item -> item.id().equals("workflow-review"))
                    .findFirst())
                    .isPresent()
                    .get()
                    .satisfies(item -> {
                        assertThat(item.title()).isEqualTo("PRD-001 更新");
                        assertThat(item.state()).isEqualTo(WorkflowState.DONE);
                    });
            assertThat(repository.loadWorkflowEvents())
                    .containsOnlyKeys("workflow-review");
            assertThat(repository.loadWorkflowEvents().get("workflow-review"))
                    .containsExactly(reviewEvent);
            assertThat(repository.loadAcceptances())
                    .containsEntry("demo", updatedAcceptance);

            var dataSource = context.getBean(DataSource.class);
            assertThat(tableCount(dataSource, "matrixcode_projects")).isEqualTo(1);
            assertThat(tableCount(dataSource, "matrixcode_workflow_items")).isEqualTo(2);
            assertThat(tableCount(dataSource, "matrixcode_workflow_events")).isEqualTo(1);
            assertThat(tableCount(dataSource, "matrixcode_acceptance_states")).isEqualTo(1);
            assertThat(idForProject(dataSource, "matrixcode_acceptance_states", "demo"))
                    .isNotBlank()
                    .isNotEqualTo("demo");
        }
    }

    @Test
    void 默认文件模式不创建数据源和工作流进度仓储Bean() {
        try (var context = new SpringApplicationBuilder(MatrixCodeServerApplication.class)
                .web(WebApplicationType.NONE)
                .properties(commonProperties())
                .run("--matrixcode.persistence.mode=file")) {
            assertThat(context.getBeanNamesForType(DataSource.class)).isEmpty();
            assertThat(context.getBeanNamesForType(WorkbenchProgressRepository.class)).isEmpty();
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
                Instant.parse("2026-06-29T19:00:00Z")
        );
    }

    private int tableCount(DataSource dataSource, String tableName) throws SQLException {
        try (var connection = dataSource.getConnection();
             var statement = connection.prepareStatement("select count(*) from " + tableName);
             var resultSet = statement.executeQuery()) {
            resultSet.next();
            return resultSet.getInt(1);
        }
    }

    private String idForProject(DataSource dataSource, String tableName, String projectId) throws SQLException {
        try (var connection = dataSource.getConnection();
             var statement = connection.prepareStatement("select id from " + tableName + " where project_id = ?")) {
            statement.setString(1, projectId);
            try (var resultSet = statement.executeQuery()) {
                assertThat(resultSet.next()).as(tableName + " project row").isTrue();
                return resultSet.getString(1);
            }
        }
    }
}
