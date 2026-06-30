package com.matrixcode.persistence;

import com.matrixcode.MatrixCodeServerApplication;
import com.matrixcode.modelgateway.application.RoleModelBindingService;
import com.matrixcode.modelgateway.domain.ModelProtocol;
import com.matrixcode.modelgateway.domain.ModelProvider;
import com.matrixcode.modelgateway.domain.ModelRole;
import com.matrixcode.modelgateway.domain.RoleModelBinding;
import com.matrixcode.workbench.application.WorkbenchStateStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;

import javax.sql.DataSource;
import java.nio.file.Path;
import java.sql.SQLException;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class MybatisPlusRoleModelBindingRepositoryTest {

    @TempDir
    Path tempDir;

    @Test
    void Jdbc模式下角色模型绑定写入正式表且不再写Workbench快照() throws Exception {
        var jdbcUrl = jdbcUrl("role_binding_mp_");

        try (var context = startJdbcContext(jdbcUrl)) {
            var service = context.getBean(RoleModelBindingService.class);
            context.getBean(com.matrixcode.modelgateway.application.ModelProviderRegistry.class)
                    .upsert(new ModelProvider("qwen", "Qwen 兼容", ModelProtocol.OPENAI_COMPATIBLE,
                            "https://dashscope.aliyuncs.com/compatible-mode/v1", "MATRIXCODE_QWEN_KEY", true));

            service.bind("demo", ModelRole.DEVELOPER, "qwen", "qwen-max", "CNY",
                    0.15, 1.5, 6.0, 48_000, "tools-v2");

            var dataSource = context.getBean(DataSource.class);
            assertThat(tableCount(dataSource, "matrixcode_role_model_bindings")).isEqualTo(1);
            assertThat(workbenchSnapshotCount(dataSource)).isZero();
        }

        try (var context = startJdbcContext(jdbcUrl)) {
            var service = context.getBean(RoleModelBindingService.class);

            assertThat(service.require("demo", ModelRole.DEVELOPER))
                    .satisfies(binding -> {
                        assertThat(binding.providerId()).isEqualTo("qwen");
                        assertThat(binding.model()).isEqualTo("qwen-max");
                        assertThat(binding.contextBudgetTokens()).isEqualTo(48_000);
                        assertThat(binding.toolContractVersion()).isEqualTo("tools-v2");
                    });
        }
    }

    @Test
    void 正式表为空时从旧Workbench快照回填角色模型绑定() throws Exception {
        var jdbcUrl = jdbcUrl("role_binding_backfill_");

        try (var context = startJdbcContext(jdbcUrl, true)) {
            context.getBean(WorkbenchStateStore.class).saveModelBindings(java.util.List.of(
                    new RoleModelBinding("demo", ModelRole.TESTER, "kimi", "kimi-k2.5", "CNY",
                            0.2, 2.0, 8.0, 64_000, "tools-v3")
            ));
        }

        try (var context = startJdbcContext(jdbcUrl)) {
            var service = context.getBean(RoleModelBindingService.class);

            assertThat(service.require("demo", ModelRole.TESTER))
                    .satisfies(binding -> {
                        assertThat(binding.providerId()).isEqualTo("kimi");
                        assertThat(binding.model()).isEqualTo("kimi-k2.5");
                        assertThat(binding.contextBudgetTokens()).isEqualTo(64_000);
                    });
            assertThat(tableCount(context.getBean(DataSource.class), "matrixcode_role_model_bindings")).isEqualTo(1);
        }
    }

    private org.springframework.context.ConfigurableApplicationContext startJdbcContext(String jdbcUrl) {
        return startJdbcContext(jdbcUrl, false);
    }

    private org.springframework.context.ConfigurableApplicationContext startJdbcContext(
            String jdbcUrl,
            boolean legacySnapshotWritesEnabled
    ) {
        return new SpringApplicationBuilder(MatrixCodeServerApplication.class)
                .web(WebApplicationType.NONE)
                .properties(commonProperties())
                .run(
                        "--matrixcode.persistence.mode=jdbc",
                        "--matrixcode.persistence.jdbc.url=" + jdbcUrl,
                        "--matrixcode.persistence.jdbc.username=sa",
                        "--matrixcode.persistence.jdbc.password=",
                        "--matrixcode.persistence.jdbc.migrate-on-startup=true",
                        "--matrixcode.persistence.jdbc.legacy-snapshot-writes-enabled=" + legacySnapshotWritesEnabled
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

    private int workbenchSnapshotCount(DataSource dataSource) throws SQLException {
        try (var connection = dataSource.getConnection();
             var statement = connection.prepareStatement("""
                     select count(*)
                     from matrixcode_state_snapshots
                     where slice_key = 'workbench-state'
                     """);
             var resultSet = statement.executeQuery()) {
            resultSet.next();
            return resultSet.getInt(1);
        }
    }
}
