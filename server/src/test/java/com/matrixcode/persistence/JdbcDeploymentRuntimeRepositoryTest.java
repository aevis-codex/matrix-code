package com.matrixcode.persistence;

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
import com.matrixcode.persistence.application.JdbcDeploymentRuntimeRepository;
import com.matrixcode.persistence.application.JdbcDeploymentTargetRepository;
import com.matrixcode.persistence.application.PersistenceModeProperties;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;

import java.sql.DriverManager;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class JdbcDeploymentRuntimeRepositoryTest {

    @Test
    void 保存后从正式部署运行态表恢复完整字段() throws Exception {
        var jdbcUrl = "jdbc:h2:mem:deployment_runtime_" + System.nanoTime()
                + ";MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1";
        migrate(jdbcUrl);
        var repository = new JdbcDeploymentRuntimeRepository(properties(jdbcUrl));
        var operationTime = Instant.parse("2026-06-25T12:00:00Z");
        var checkTime = Instant.parse("2026-06-25T12:01:00Z");
        var envCreatedAt = Instant.parse("2026-06-25T12:02:00Z");
        var envUpdatedAt = Instant.parse("2026-06-25T12:03:00Z");
        var composeOperationTime = Instant.parse("2026-06-25T12:04:00Z");
        var operation = new DeploymentOperationRecord(
                "deploy-op-1",
                "demo",
                "target-1",
                "user-ops",
                DeploymentOperationType.DEPLOYMENT,
                DeploymentOperationStatus.SUCCEEDED,
                "预发部署完成",
                operationTime
        );
        var check = new DeploymentHealthCheck(
                "health-1",
                "demo",
                "target-1",
                "user-ops",
                DeploymentHealthStatus.HEALTHY,
                204,
                18,
                "HTTP 204",
                checkTime
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
                envCreatedAt,
                envUpdatedAt
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
                composeOperationTime
        );

        repository.saveDeploymentOperations(Map.of("demo", List.of(operation)));
        repository.saveDeploymentHealthChecks(Map.of("demo", List.of(check)));
        repository.saveComposeEnvironments(List.of(environment));
        repository.saveComposeOperations(Map.of("demo", List.of(composeOperation)));

        assertThat(repository.loadDeploymentOperations().get("demo")).containsExactly(operation);
        assertThat(repository.loadDeploymentHealthChecks().get("demo")).containsExactly(check);
        assertThat(repository.loadComposeEnvironments()).containsExactly(environment);
        assertThat(repository.loadComposeOperations().get("demo")).containsExactly(composeOperation);
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
}
