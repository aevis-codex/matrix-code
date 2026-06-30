package com.matrixcode.persistence;

import com.matrixcode.modelgateway.domain.ModelRequestRecord;
import com.matrixcode.modelgateway.domain.ModelRole;
import com.matrixcode.persistence.application.JdbcProjectActivityRepository;
import com.matrixcode.persistence.application.PersistenceModeProperties;
import com.matrixcode.realtime.domain.ProjectEvent;
import com.matrixcode.usage.domain.UsageRecord;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;

import java.sql.DriverManager;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class JdbcProjectActivityRepositoryTest {

    @Test
    void 保存后从正式项目活动表恢复模型请求和项目事件() throws Exception {
        var jdbcUrl = "jdbc:h2:mem:project_activity_" + System.nanoTime()
                + ";MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1";
        migrate(jdbcUrl);
        var repository = new JdbcProjectActivityRepository(properties(jdbcUrl));
        var request = modelRequest();
        var event = projectEvent();

        repository.saveModelRequests(Map.of("demo", List.of(request)));
        repository.saveProjectEvents(Map.of("demo", List.of(event)));

        assertThat(repository.loadModelRequests().get("demo")).containsExactly(request);
        assertThat(repository.loadModelRequests().get("demo").getFirst().usage())
                .extracting(
                        UsageRecord::promptPartitionPolicyId,
                        UsageRecord::promptPartitionFingerprint,
                        UsageRecord::stablePartitionCount,
                        UsageRecord::volatilePartitionCount
                )
                .containsExactly(
                        "deepseek-reasonix-partitions-v1",
                        "partition-fp-001",
                        2,
                        3
                );
        assertThat(repository.loadProjectEvents().get("demo")).containsExactly(event);
        assertThat(repository.loadProjectEvents().get("demo").getFirst().sourceRole()).isEqualTo("DEVELOPER");
        assertThat(repository.loadProjectEvents().get("demo").getFirst().sourceId()).isEqualTo("user-dev");
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

    private ModelRequestRecord modelRequest() {
        return new ModelRequestRecord(
                "request-1",
                "demo",
                ModelRole.DEVELOPER,
                "deepseek",
                "deepseek-chat",
                "开发智能体完成代码修改",
                "user-dev",
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
                Instant.parse("2026-06-25T12:00:00Z")
        );
    }

    private ProjectEvent projectEvent() {
        return new ProjectEvent(
                "event-1",
                "demo",
                "MODEL_REQUEST_COMPLETED",
                "开发使用 deepseek-chat 完成模型请求",
                Instant.parse("2026-06-25T12:00:05Z"),
                "DEVELOPER",
                "user-dev"
        );
    }
}
