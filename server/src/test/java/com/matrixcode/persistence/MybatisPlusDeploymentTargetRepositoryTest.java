package com.matrixcode.persistence;

import com.matrixcode.MatrixCodeServerApplication;
import com.matrixcode.deployment.application.DeploymentTargetRepository;
import com.matrixcode.deployment.domain.DeploymentTarget;
import com.matrixcode.deployment.domain.DeploymentTargetStatus;
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

class MybatisPlusDeploymentTargetRepositoryTest {

    private static final Instant UPDATED_AT = Instant.parse("2026-06-25T00:00:00Z");

    @TempDir
    Path tempDir;

    @Test
    void Jdbc模式下部署目标仓储使用MybatisPlus并保持正式表读写行为() throws Exception {
        var jdbcUrl = jdbcUrl("deployment_target_mp_");

        try (var context = startJdbcContext(jdbcUrl)) {
            var repository = context.getBean(DeploymentTargetRepository.class);
            assertThat(repository.getClass().getName())
                    .contains("MybatisPlusDeploymentTargetRepository");

            repository.save(List.of(target(
                    "target-1",
                    "预发环境",
                    "https://pre.example.com",
                    DeploymentTargetStatus.RELEASE_READY,
                    false,
                    UPDATED_AT
            )));

            assertThat(repository.load()).singleElement().satisfies(value -> {
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
                assertThat(value.updatedAt()).isEqualTo(UPDATED_AT);
            });
            assertThat(tableCount(context.getBean(DataSource.class), "matrixcode_deployment_targets")).isEqualTo(1);
            assertThat(tableCount(context.getBean(DataSource.class), "matrixcode_projects")).isEqualTo(1);
        }
    }

    @Test
    void 保存同一部署目标时执行Upsert不重复插入() throws Exception {
        var jdbcUrl = jdbcUrl("deployment_target_upsert_mp_");

        try (var context = startJdbcContext(jdbcUrl)) {
            var repository = context.getBean(DeploymentTargetRepository.class);

            repository.save(List.of(target(
                    "target-1",
                    "预发环境",
                    "https://pre.example.com",
                    DeploymentTargetStatus.RECORDED,
                    false,
                    UPDATED_AT
            )));
            repository.save(List.of(target(
                    "target-1",
                    "生产环境",
                    "https://prod.example.com",
                    DeploymentTargetStatus.DEPLOYED,
                    true,
                    UPDATED_AT.plusSeconds(60)
            )));

            assertThat(repository.load()).singleElement().satisfies(value -> {
                assertThat(value.environmentName()).isEqualTo("生产环境");
                assertThat(value.environmentUrl()).isEqualTo("https://prod.example.com");
                assertThat(value.healthCheckUrl()).isEqualTo("https://prod.example.com/health");
                assertThat(value.status()).isEqualTo(DeploymentTargetStatus.DEPLOYED);
                assertThat(value.remoteExecuted()).isTrue();
                assertThat(value.updatedAt()).isEqualTo(UPDATED_AT.plusSeconds(60));
            });
            assertThat(tableCount(context.getBean(DataSource.class), "matrixcode_deployment_targets")).isEqualTo(1);
        }
    }

    @Test
    void 默认文件模式不创建数据源和部署目标仓储Bean() {
        try (var context = new SpringApplicationBuilder(MatrixCodeServerApplication.class)
                .web(WebApplicationType.NONE)
                .properties(commonProperties())
                .run("--matrixcode.persistence.mode=file")) {
            assertThat(context.getBeanNamesForType(DataSource.class)).isEmpty();
            assertThat(context.getBeanNamesForType(DeploymentTargetRepository.class)).isEmpty();
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

    private DeploymentTarget target(
            String id,
            String environmentName,
            String environmentUrl,
            DeploymentTargetStatus status,
            boolean remoteExecuted,
            Instant updatedAt
    ) {
        return new DeploymentTarget(
                id,
                "demo",
                environmentName,
                environmentUrl,
                "deploy@example.com",
                "按发布单部署",
                environmentUrl + "/health",
                "回滚上一版本",
                status,
                remoteExecuted,
                updatedAt
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
