package com.matrixcode.persistence;

import com.matrixcode.MatrixCodeServerApplication;
import com.matrixcode.modelgateway.domain.ModelRequestRecord;
import com.matrixcode.modelgateway.domain.ModelRole;
import com.matrixcode.realtime.domain.ProjectEvent;
import com.matrixcode.usage.domain.UsageRecord;
import com.matrixcode.workbench.application.ProjectActivityRepository;
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

class MybatisPlusProjectActivityRepositoryTest {

    private static final Instant CREATED_AT = Instant.parse("2026-06-25T12:00:00Z");

    @TempDir
    Path tempDir;

    @Test
    void Jdbc模式下项目活动仓储使用MybatisPlus并保持正式表读写行为() throws Exception {
        var jdbcUrl = jdbcUrl("project_activity_mp_");

        try (var context = startJdbcContext(jdbcUrl)) {
            var repository = context.getBean(ProjectActivityRepository.class);
            assertThat(repository.getClass().getName())
                    .contains("MybatisPlusProjectActivityRepository");
            var request = modelRequest("request-1", "开发智能体完成代码修改", "user-dev", CREATED_AT);
            var event = projectEvent("event-1", "开发使用 deepseek-chat 完成模型请求", CREATED_AT.plusSeconds(5));

            repository.saveModelRequests(Map.of("demo", List.of(request)));
            repository.saveProjectEvents(Map.of("demo", List.of(event)));

            assertThat(repository.loadModelRequests().get("demo")).containsExactly(request);
            assertThat(repository.loadProjectEvents().get("demo")).containsExactly(event);
            assertThat(repository.loadProjectEvents().get("demo").getFirst().sourceRole()).isEqualTo("DEVELOPER");
            assertThat(repository.loadProjectEvents().get("demo").getFirst().sourceId()).isEqualTo("user-dev");
            assertThat(tableCount(context.getBean(DataSource.class), "matrixcode_model_requests")).isEqualTo(1);
            assertThat(tableCount(context.getBean(DataSource.class), "matrixcode_project_events")).isEqualTo(1);
            assertThat(tableCount(context.getBean(DataSource.class), "matrixcode_projects")).isEqualTo(1);
            assertThat(tableCount(context.getBean(DataSource.class), "matrixcode_users")).isEqualTo(1);
        }
    }

    @Test
    void 同项目二次保存时按项目替换模型请求和项目事件() throws Exception {
        var jdbcUrl = jdbcUrl("project_activity_replace_mp_");

        try (var context = startJdbcContext(jdbcUrl)) {
            var repository = context.getBean(ProjectActivityRepository.class);
            repository.saveModelRequests(Map.of("demo", List.of(
                    modelRequest("request-1", "旧模型请求", "user-dev", CREATED_AT)
            )));
            repository.saveProjectEvents(Map.of("demo", List.of(
                    projectEvent("event-1", "旧事件", CREATED_AT)
            )));

            var replacementRequest = modelRequest(
                    "request-2",
                    "新模型请求",
                    "user-product",
                    CREATED_AT.plusSeconds(60)
            );
            var replacementEvent = projectEvent(
                    "event-2",
                    "新事件",
                    CREATED_AT.plusSeconds(65)
            );
            repository.saveModelRequests(Map.of("demo", List.of(replacementRequest)));
            repository.saveProjectEvents(Map.of("demo", List.of(replacementEvent)));

            assertThat(repository.loadModelRequests().get("demo")).containsExactly(replacementRequest);
            assertThat(repository.loadProjectEvents().get("demo")).containsExactly(replacementEvent);
            assertThat(tableCount(context.getBean(DataSource.class), "matrixcode_model_requests")).isEqualTo(1);
            assertThat(tableCount(context.getBean(DataSource.class), "matrixcode_project_events")).isEqualTo(1);
            assertThat(tableCount(context.getBean(DataSource.class), "matrixcode_users")).isEqualTo(2);
        }
    }

    @Test
    void 默认文件模式不创建数据源和项目活动仓储Bean() {
        try (var context = new SpringApplicationBuilder(MatrixCodeServerApplication.class)
                .web(WebApplicationType.NONE)
                .properties(commonProperties())
                .run("--matrixcode.persistence.mode=file")) {
            assertThat(context.getBeanNamesForType(DataSource.class)).isEmpty();
            assertThat(context.getBeanNamesForType(ProjectActivityRepository.class)).isEmpty();
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

    private ModelRequestRecord modelRequest(String id, String summary, String actorUserId, Instant createdAt) {
        return new ModelRequestRecord(
                id,
                "demo",
                ModelRole.DEVELOPER,
                "deepseek",
                "deepseek-chat",
                summary,
                actorUserId,
                "run-1",
                new UsageRecord(
                        "demo:DEVELOPER",
                        "deepseek-chat",
                        11,
                        22,
                        33,
                        0.25,
                        0.42,
                        "CNY",
                        "PROVIDER",
                        "matrixcode_demo_DEVELOPER_deepseek_deepseek-chat",
                        "fp-cache-001",
                        true,
                        "stable-platform-prefix-v1",
                        "role-prompt-and-dynamic-context",
                        "deepseek-reasonix-partitions-v1",
                        "partition-fp-001",
                        2,
                        3
                ),
                List.of("PROJECT_RULE", "VECTOR_CONTEXT"),
                createdAt
        );
    }

    private ProjectEvent projectEvent(String id, String message, Instant occurredAt) {
        return new ProjectEvent(
                id,
                "demo",
                "MODEL_REQUEST_COMPLETED",
                message,
                occurredAt,
                "DEVELOPER",
                "user-dev"
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
}
