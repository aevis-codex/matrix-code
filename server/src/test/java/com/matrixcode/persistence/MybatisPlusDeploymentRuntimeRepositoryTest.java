package com.matrixcode.persistence;

import com.matrixcode.MatrixCodeServerApplication;
import com.matrixcode.deployment.application.DeploymentRuntimeRepository;
import com.matrixcode.deployment.domain.ComposeEnvironment;
import com.matrixcode.deployment.domain.ComposeEnvironmentStatus;
import com.matrixcode.deployment.domain.ComposeOperationRecord;
import com.matrixcode.deployment.domain.ComposeOperationStatus;
import com.matrixcode.deployment.domain.ComposeOperationType;
import com.matrixcode.deployment.domain.DeploymentHealthCheck;
import com.matrixcode.deployment.domain.DeploymentHealthStatus;
import com.matrixcode.deployment.domain.DeploymentOperationRecord;
import com.matrixcode.deployment.domain.DeploymentOperationStatus;
import com.matrixcode.deployment.domain.DeploymentOperationType;
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

class MybatisPlusDeploymentRuntimeRepositoryTest {

    private static final Instant OPERATION_TIME = Instant.parse("2026-06-29T10:00:00Z");
    private static final Instant CHECK_TIME = Instant.parse("2026-06-29T10:01:00Z");
    private static final Instant ENV_CREATED_AT = Instant.parse("2026-06-29T10:02:00Z");
    private static final Instant ENV_UPDATED_AT = Instant.parse("2026-06-29T10:03:00Z");
    private static final Instant COMPOSE_OPERATION_TIME = Instant.parse("2026-06-29T10:04:00Z");

    @TempDir
    Path tempDir;

    @Test
    void Jdbc模式下部署运行态仓储使用MybatisPlus并保持正式表读写行为() throws Exception {
        var jdbcUrl = jdbcUrl("deployment_runtime_mp_");

        try (var context = startJdbcContext(jdbcUrl)) {
            var repository = context.getBean(DeploymentRuntimeRepository.class);
            assertThat(repository.getClass().getName())
                    .contains("MybatisPlusDeploymentRuntimeRepository");

            var operation = new DeploymentOperationRecord(
                    "deploy-op-1",
                    "demo",
                    "target-1",
                    "user-ops",
                    DeploymentOperationType.DEPLOYMENT,
                    DeploymentOperationStatus.SUCCEEDED,
                    "生产部署完成",
                    OPERATION_TIME
            );
            var check = new DeploymentHealthCheck(
                    "health-1",
                    "demo",
                    "target-1",
                    "user-ops",
                    DeploymentHealthStatus.HEALTHY,
                    200,
                    38,
                    "HTTP 200",
                    CHECK_TIME
            );
            var environment = new ComposeEnvironment(
                    "compose-env-1",
                    "demo",
                    "target-1",
                    "workspace-1",
                    "compose.yml",
                    "matrixcode-demo",
                    "web",
                    ComposeEnvironmentStatus.RUNNING,
                    ENV_CREATED_AT,
                    ENV_UPDATED_AT
            );
            var composeOperation = new ComposeOperationRecord(
                    "compose-op-1",
                    "demo",
                    "compose-env-1",
                    "user-ops",
                    ComposeOperationType.START,
                    ComposeOperationStatus.SUCCEEDED,
                    "Compose 已启动",
                    "container web started",
                    COMPOSE_OPERATION_TIME
            );

            repository.saveDeploymentOperations(Map.of("demo", List.of(operation)));
            repository.saveDeploymentHealthChecks(Map.of("demo", List.of(check)));
            repository.saveComposeEnvironments(List.of(environment));
            repository.saveComposeOperations(Map.of("demo", List.of(composeOperation)));

            assertThat(repository.loadDeploymentOperations().get("demo")).containsExactly(operation);
            assertThat(repository.loadDeploymentHealthChecks().get("demo")).containsExactly(check);
            assertThat(repository.loadComposeEnvironments()).containsExactly(environment);
            assertThat(repository.loadComposeOperations().get("demo")).containsExactly(composeOperation);
            assertThat(tableCount(context.getBean(DataSource.class), "matrixcode_projects")).isEqualTo(1);
            assertThat(tableCount(context.getBean(DataSource.class), "matrixcode_deployment_operations")).isEqualTo(1);
            assertThat(tableCount(context.getBean(DataSource.class), "matrixcode_deployment_health_checks")).isEqualTo(1);
            assertThat(tableCount(context.getBean(DataSource.class), "matrixcode_compose_environments")).isEqualTo(1);
            assertThat(tableCount(context.getBean(DataSource.class), "matrixcode_compose_operations")).isEqualTo(1);
        }
    }

    @Test
    void 保存同一Compose环境时执行Upsert不重复插入() throws Exception {
        var jdbcUrl = jdbcUrl("deployment_runtime_compose_upsert_mp_");

        try (var context = startJdbcContext(jdbcUrl)) {
            var repository = context.getBean(DeploymentRuntimeRepository.class);
            repository.saveComposeEnvironments(List.of(new ComposeEnvironment(
                    "compose-env-1",
                    "demo",
                    "target-1",
                    "workspace-1",
                    "compose.yml",
                    "matrixcode-demo",
                    "web",
                    ComposeEnvironmentStatus.CONFIGURED,
                    ENV_CREATED_AT,
                    ENV_UPDATED_AT
            )));
            repository.saveComposeEnvironments(List.of(new ComposeEnvironment(
                    "compose-env-1",
                    "demo",
                    "target-1",
                    "workspace-1",
                    "compose.yml",
                    "matrixcode-prod",
                    "api",
                    ComposeEnvironmentStatus.RUNNING,
                    ENV_CREATED_AT,
                    ENV_UPDATED_AT.plusSeconds(60)
            )));

            assertThat(repository.loadComposeEnvironments()).singleElement().satisfies(environment -> {
                assertThat(environment.projectName()).isEqualTo("matrixcode-prod");
                assertThat(environment.serviceName()).isEqualTo("api");
                assertThat(environment.status()).isEqualTo(ComposeEnvironmentStatus.RUNNING);
                assertThat(environment.updatedAt()).isEqualTo(ENV_UPDATED_AT.plusSeconds(60));
            });
            assertThat(tableCount(context.getBean(DataSource.class), "matrixcode_compose_environments")).isEqualTo(1);
        }
    }

    @Test
    void 默认文件模式不创建数据源和部署运行态仓储Bean() {
        try (var context = new SpringApplicationBuilder(MatrixCodeServerApplication.class)
                .web(WebApplicationType.NONE)
                .properties(commonProperties())
                .run("--matrixcode.persistence.mode=file")) {
            assertThat(context.getBeanNamesForType(DataSource.class)).isEmpty();
            assertThat(context.getBeanNamesForType(DeploymentRuntimeRepository.class)).isEmpty();
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
}
