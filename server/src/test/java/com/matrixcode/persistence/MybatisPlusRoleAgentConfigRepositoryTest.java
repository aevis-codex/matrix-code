package com.matrixcode.persistence;

import com.matrixcode.MatrixCodeServerApplication;
import com.matrixcode.modelgateway.domain.ModelRole;
import com.matrixcode.roleagent.application.RoleAgentConfigRepository;
import com.matrixcode.roleagent.domain.RoleAgentConfig;
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

class MybatisPlusRoleAgentConfigRepositoryTest {

    @TempDir
    Path tempDir;

    @Test
    void Jdbc模式下角色智能体配置仓储使用MybatisPlus并保持配置读写行为() throws Exception {
        var databaseName = "role_agent_config_mp_" + UUID.randomUUID().toString().replace("-", "");
        var jdbcUrl = "jdbc:h2:mem:" + databaseName
                + ";MODE=MySQL;DATABASE_TO_LOWER=TRUE;CASE_INSENSITIVE_IDENTIFIERS=TRUE;DB_CLOSE_DELAY=-1";

        try (var context = startContext(jdbcUrl)) {
            var repository = context.getBean(RoleAgentConfigRepository.class);
            assertThat(repository.getClass().getName())
                    .contains("MybatisPlusRoleAgentConfigRepository");

            var config = new RoleAgentConfig(
                    "demo",
                    ModelRole.DEVELOPER,
                    "开发智能体 Pro",
                    "coding",
                    "deepseek",
                    "deepseek-chat",
                    "tools-v2",
                    "你是开发编码智能体，必须先读代码。",
                    "任务：{{instruction}}",
                    "#0f766e",
                    "Inter",
                    15,
                    2,
                    true,
                    "deepseek-prefix-v2",
                    "stable-prefix-dynamic-tail",
                    "provider-role",
                    Instant.parse("2026-06-25T00:00:00Z")
            );

            repository.save(List.of(config));

            assertThat(repository.load()).singleElement().satisfies(value -> {
                assertThat(value.projectId()).isEqualTo("demo");
                assertThat(value.role()).isEqualTo(ModelRole.DEVELOPER);
                assertThat(value.displayName()).isEqualTo("开发智能体 Pro");
                assertThat(value.providerId()).isEqualTo("deepseek");
                assertThat(value.model()).isEqualTo("deepseek-chat");
                assertThat(value.systemPrompt()).contains("必须先读代码");
                assertThat(value.userPromptTemplate()).isEqualTo("任务：{{instruction}}");
                assertThat(value.themeColor()).isEqualTo("#0f766e");
                assertThat(value.fontSize()).isEqualTo(15);
                assertThat(value.cachePolicyId()).isEqualTo("deepseek-prefix-v2");
                assertThat(value.volatileSuffixStrategy()).isEqualTo("stable-prefix-dynamic-tail");
                assertThat(value.cacheScopeStrategy()).isEqualTo("provider-role");
                assertThat(value.enabled()).isTrue();
            });
            assertThat(projectCount(context.getBean(DataSource.class))).isEqualTo(1);
        }
    }

    @Test
    void 默认文件模式不创建数据源和角色智能体配置仓储Bean() {
        try (var context = new SpringApplicationBuilder(MatrixCodeServerApplication.class)
                .web(WebApplicationType.NONE)
                .properties(commonProperties())
                .run("--matrixcode.persistence.mode=file")) {
            assertThat(context.getBeanNamesForType(DataSource.class)).isEmpty();
            assertThat(context.getBeanNamesForType(RoleAgentConfigRepository.class)).isEmpty();
        }
    }

    private org.springframework.context.ConfigurableApplicationContext startContext(String jdbcUrl) {
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

    private int projectCount(DataSource dataSource) throws SQLException {
        try (var connection = dataSource.getConnection();
             var statement = connection.prepareStatement("select count(*) from matrixcode_projects");
             var resultSet = statement.executeQuery()) {
            resultSet.next();
            return resultSet.getInt(1);
        }
    }
}
