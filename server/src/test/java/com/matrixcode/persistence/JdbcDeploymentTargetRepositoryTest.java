package com.matrixcode.persistence;

import com.matrixcode.deployment.domain.DeploymentTarget;
import com.matrixcode.deployment.domain.DeploymentTargetStatus;
import com.matrixcode.persistence.application.JdbcDeploymentTargetRepository;
import com.matrixcode.persistence.application.PersistenceModeProperties;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;

import java.sql.DriverManager;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class JdbcDeploymentTargetRepositoryTest {

    @Test
    void 保存后从正式部署目标表恢复完整字段() throws Exception {
        var jdbcUrl = "jdbc:h2:mem:deployment_targets_" + System.nanoTime()
                + ";MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1";
        migrate(jdbcUrl);
        var repository = new JdbcDeploymentTargetRepository(properties(jdbcUrl));
        var updatedAt = Instant.parse("2026-06-25T12:00:00Z");
        var target = new DeploymentTarget(
                "target-1",
                "demo",
                "预发环境",
                "https://pre.example.com",
                "deploy@example.com",
                "按发布单部署",
                "https://pre.example.com/health",
                "回滚上一版本",
                DeploymentTargetStatus.RELEASE_READY,
                false,
                updatedAt
        );

        repository.save(List.of(target));

        var restored = repository.load();
        assertThat(restored).singleElement().satisfies(value -> {
            assertThat(value.id()).isEqualTo("target-1");
            assertThat(value.projectId()).isEqualTo("demo");
            assertThat(value.environmentName()).isEqualTo("预发环境");
            assertThat(value.environmentUrl()).isEqualTo("https://pre.example.com");
            assertThat(value.sshAddress()).isEqualTo("deploy@example.com");
            assertThat(value.deployNote()).isEqualTo("按发布单部署");
            assertThat(value.healthCheckUrl()).isEqualTo("https://pre.example.com/health");
            assertThat(value.rollbackNote()).isEqualTo("回滚上一版本");
            assertThat(value.status()).isEqualTo(DeploymentTargetStatus.RELEASE_READY);
            assertThat(value.remoteExecuted()).isFalse();
            assertThat(value.updatedAt()).isEqualTo(updatedAt);
        });
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
