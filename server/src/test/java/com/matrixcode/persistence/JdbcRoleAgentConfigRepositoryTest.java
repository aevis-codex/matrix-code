package com.matrixcode.persistence;

import com.matrixcode.modelgateway.domain.ModelRole;
import com.matrixcode.persistence.application.JdbcRoleAgentConfigRepository;
import com.matrixcode.persistence.application.PersistenceModeProperties;
import com.matrixcode.roleagent.domain.RoleAgentConfig;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;

import java.sql.DriverManager;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class JdbcRoleAgentConfigRepositoryTest {

    @Test
    void 保存后从正式角色智能体配置表恢复配置() throws Exception {
        var jdbcUrl = "jdbc:h2:mem:role_agent_config_" + System.nanoTime()
                + ";MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1";
        migrate(jdbcUrl);
        var repository = new JdbcRoleAgentConfigRepository(properties(jdbcUrl));
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
        var restored = repository.load();

        assertThat(restored).singleElement().satisfies(value -> {
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
