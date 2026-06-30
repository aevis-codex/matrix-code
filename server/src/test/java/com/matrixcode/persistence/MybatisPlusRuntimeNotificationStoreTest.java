package com.matrixcode.persistence;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.matrixcode.MatrixCodeServerApplication;
import com.matrixcode.persistence.application.JdbcSnapshotRepository;
import com.matrixcode.runtime.application.RuntimeNotificationSnapshot;
import com.matrixcode.runtime.application.RuntimeNotificationStore;
import com.matrixcode.runtime.domain.RuntimeNotification;
import com.matrixcode.runtime.domain.RuntimeNotificationLevel;
import com.matrixcode.runtime.domain.RuntimeNotificationSourceType;
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

class MybatisPlusRuntimeNotificationStoreTest {

    private static final Instant OCCURRED_AT = Instant.parse("2026-06-25T00:00:00Z");
    private static final Instant READ_AT = Instant.parse("2026-06-25T08:00:00Z");

    @TempDir
    Path tempDir;

    @Test
    void Jdbc模式下运行态提醒Store使用MybatisPlus并保持正式表读写行为() throws Exception {
        var jdbcUrl = jdbcUrl("runtime_notification_mp_");

        try (var context = startJdbcContext(jdbcUrl)) {
            var store = context.getBean(RuntimeNotificationStore.class);
            assertThat(store.getClass().getName())
                    .contains("MybatisPlusRuntimeNotificationStore");

            store.save(new RuntimeNotificationSnapshot(
                    1,
                    Map.of("demo", List.of(notification("notice-1", "user-tester")))
            ));

            var restored = store.load();

            assertThat(restored.projects()).containsKey("demo");
            assertThat(restored.projects().get("demo")).singleElement().satisfies(value -> {
                assertThat(value.id()).isEqualTo("notice-1");
                assertThat(value.level()).isEqualTo(RuntimeNotificationLevel.ACTION);
                assertThat(value.sourceType()).isEqualTo(RuntimeNotificationSourceType.APPROVAL);
                assertThat(value.readAt()).isEqualTo(READ_AT);
                assertThat(value.readByUserId()).isEqualTo("user-tester");
            });
            var dataSource = context.getBean(DataSource.class);
            assertThat(tableCount(dataSource, "matrixcode_runtime_notifications")).isEqualTo(1);
            assertThat(tableCount(dataSource, "matrixcode_projects")).isEqualTo(1);
            assertThat(tableCount(dataSource, "matrixcode_users")).isEqualTo(1);
            assertThat(snapshotCount(dataSource, "runtime-notifications")).isZero();
        }
    }

    @Test
    void Jdbc模式下保存并恢复用户级提醒已读回执() throws Exception {
        var jdbcUrl = jdbcUrl("runtime_notification_read_mp_");

        try (var context = startJdbcContext(jdbcUrl)) {
            var store = context.getBean(RuntimeNotificationStore.class);

            store.save(new RuntimeNotificationSnapshot(
                    1,
                    Map.of("demo", List.of(notification("notice-1", ""))),
                    Map.of("demo", Map.of("user-product", Map.of("notice-1", READ_AT)))
            ));

            var restored = store.load();

            assertThat(restored.readReceipts().get("demo").get("user-product").get("notice-1"))
                    .isEqualTo(READ_AT);
            var dataSource = context.getBean(DataSource.class);
            assertThat(tableCount(dataSource, "matrixcode_runtime_notifications")).isEqualTo(1);
            assertThat(tableCount(dataSource, "matrixcode_runtime_notification_reads")).isEqualTo(1);
            assertThat(tableCount(dataSource, "matrixcode_users")).isEqualTo(1);
        }
    }

    @Test
    void 正式表为空时从旧快照回填到MybatisPlus正式表() throws Exception {
        var jdbcUrl = jdbcUrl("runtime_notification_backfill_mp_");

        try (var context = startJdbcContext(jdbcUrl)) {
            var repository = context.getBean(JdbcSnapshotRepository.class);
            repository.save(
                    "runtime-notifications",
                    1,
                    context.getBean(ObjectMapper.class).writeValueAsString(new RuntimeNotificationSnapshot(
                            1,
                            Map.of("demo", List.of(notification("notice-legacy", "user-ops")))
                    ))
            );

            var store = context.getBean(RuntimeNotificationStore.class);
            var restored = store.load();

            assertThat(restored.projects().get("demo"))
                    .extracting(RuntimeNotification::id)
                    .containsExactly("notice-legacy");
            assertThat(tableCount(context.getBean(DataSource.class), "matrixcode_runtime_notifications"))
                    .isEqualTo(1);
        }
    }

    @Test
    void 默认文件模式不创建数据源且继续使用文件提醒Store() {
        try (var context = new SpringApplicationBuilder(MatrixCodeServerApplication.class)
                .web(WebApplicationType.NONE)
                .properties(commonProperties())
                .run("--matrixcode.persistence.mode=file")) {
            assertThat(context.getBeanNamesForType(DataSource.class)).isEmpty();
            assertThat(context.getBean(RuntimeNotificationStore.class).getClass().getName())
                    .contains("FileRuntimeNotificationStore");
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

    private RuntimeNotification notification(String id, String readByUserId) {
        return new RuntimeNotification(
                id,
                "demo",
                RuntimeNotificationLevel.ACTION,
                "需要审批本地命令",
                "git status 等待审批",
                RuntimeNotificationSourceType.APPROVAL,
                "task-1",
                OCCURRED_AT,
                READ_AT,
                readByUserId
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

    private int snapshotCount(DataSource dataSource, String sliceKey) throws SQLException {
        try (var connection = dataSource.getConnection()) {
            try (var tables = connection.getMetaData().getTables(null, null, "matrixcode_state_snapshots", null)) {
                if (!tables.next()) {
                    return 0;
                }
            }
            try (var statement = connection.prepareStatement(
                    "select count(*) from matrixcode_state_snapshots where slice_key = ?"
            )) {
                statement.setString(1, sliceKey);
                try (var resultSet = statement.executeQuery()) {
                    resultSet.next();
                    return resultSet.getInt(1);
                }
            }
        }
    }
}
