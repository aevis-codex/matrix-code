package com.matrixcode.persistence;

import com.matrixcode.persistence.application.JdbcRuntimeNotificationStore;
import com.matrixcode.persistence.application.JdbcSnapshotRepository;
import com.matrixcode.persistence.application.PersistenceModeProperties;
import com.matrixcode.runtime.application.RuntimeNotificationSnapshot;
import com.matrixcode.runtime.domain.RuntimeNotification;
import com.matrixcode.runtime.domain.RuntimeNotificationLevel;
import com.matrixcode.runtime.domain.RuntimeNotificationSourceType;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;

import java.sql.DriverManager;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class JdbcRuntimeNotificationStoreTest {

    private static final Instant READ_AT = Instant.parse("2026-06-25T08:00:00Z");

    @Test
    void 保存后从正式运行态提醒表恢复且不写旧快照() throws Exception {
        var jdbcUrl = jdbcUrl("runtime_notifications_domain_");
        migrate(jdbcUrl);
        var repository = repository(jdbcUrl);
        var store = new JdbcRuntimeNotificationStore(repository, JsonTestSupport.objectMapper());

        store.save(new RuntimeNotificationSnapshot(1, Map.of("demo", List.of(notification("notice-1", READ_AT)))));

        var restored = new JdbcRuntimeNotificationStore(repository, JsonTestSupport.objectMapper()).load();

        assertThat(restored.projects()).containsKey("demo");
        assertThat(restored.projects().get("demo")).extracting(RuntimeNotification::id).containsExactly("notice-1");
        assertThat(restored.projects().get("demo").getFirst().readAt()).isEqualTo(READ_AT);
        assertThat(tableCount(jdbcUrl, "matrixcode_runtime_notifications")).isEqualTo(1);
        assertThat(snapshotCount(jdbcUrl, "runtime-notifications")).isZero();
    }

    @Test
    void 保存后从正式用户已读表恢复运行态提醒回执() throws Exception {
        var jdbcUrl = jdbcUrl("runtime_notification_reads_domain_");
        migrate(jdbcUrl);
        var repository = repository(jdbcUrl);
        var store = new JdbcRuntimeNotificationStore(repository, JsonTestSupport.objectMapper());

        store.save(new RuntimeNotificationSnapshot(
                1,
                Map.of("demo", List.of(notification("notice-1", null))),
                Map.of("demo", Map.of("user-product", Map.of("notice-1", READ_AT)))
        ));

        var restored = new JdbcRuntimeNotificationStore(repository, JsonTestSupport.objectMapper()).load();

        assertThat(restored.readReceipts().get("demo").get("user-product").get("notice-1")).isEqualTo(READ_AT);
        assertThat(tableCount(jdbcUrl, "matrixcode_runtime_notification_reads")).isEqualTo(1);
    }

    @Test
    void 损坏Payload返回空快照() throws Exception {
        var jdbcUrl = jdbcUrl("runtime_notifications_broken_");
        migrate(jdbcUrl);
        var repository = repository(jdbcUrl);
        repository.save("runtime-notifications", 1, "{broken");
        var store = new JdbcRuntimeNotificationStore(repository, JsonTestSupport.objectMapper());

        var restored = store.load();

        assertThat(restored.projects()).isEmpty();
    }

    @Test
    void 正式表为空时从旧快照恢复并回填正式表() throws Exception {
        var jdbcUrl = jdbcUrl("runtime_notifications_backfill_");
        migrate(jdbcUrl);
        var repository = repository(jdbcUrl);
        repository.save("runtime-notifications", 1, JsonTestSupport.objectMapper().writeValueAsString(
                new RuntimeNotificationSnapshot(1, Map.of("demo", List.of(notification("notice-legacy", READ_AT))))
        ));

        var restored = new JdbcRuntimeNotificationStore(repository, JsonTestSupport.objectMapper()).load();

        assertThat(restored.projects().get("demo")).extracting(RuntimeNotification::id).containsExactly("notice-legacy");
        assertThat(tableCount(jdbcUrl, "matrixcode_runtime_notifications")).isEqualTo(1);
    }

    private String jdbcUrl(String prefix) {
        return "jdbc:h2:mem:" + prefix + System.nanoTime()
                + ";MODE=MySQL;DATABASE_TO_LOWER=TRUE;CASE_INSENSITIVE_IDENTIFIERS=TRUE;DB_CLOSE_DELAY=-1";
    }

    private void migrate(String jdbcUrl) {
        Flyway.configure()
                .dataSource(jdbcUrl, "sa", "")
                .locations("classpath:db/migration")
                .load()
                .migrate();
    }

    private JdbcSnapshotRepository repository(String jdbcUrl) {
        var properties = new PersistenceModeProperties();
        properties.setMode("jdbc");
        properties.getJdbc().setUrl(jdbcUrl);
        properties.getJdbc().setUsername("sa");
        properties.getJdbc().setPassword("");
        return new JdbcSnapshotRepository(properties);
    }

    private int tableCount(String jdbcUrl, String tableName) throws Exception {
        try (var connection = DriverManager.getConnection(jdbcUrl, "sa", "");
             var statement = connection.prepareStatement("select count(*) from " + tableName);
             var resultSet = statement.executeQuery()) {
            resultSet.next();
            return resultSet.getInt(1);
        }
    }

    private int snapshotCount(String jdbcUrl, String sliceKey) throws Exception {
        try (var connection = DriverManager.getConnection(jdbcUrl, "sa", "")) {
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

    private RuntimeNotification notification(String id, Instant readAt) {
        return new RuntimeNotification(
                id,
                "demo",
                RuntimeNotificationLevel.ACTION,
                "需要审批本地命令",
                "git status 等待审批",
                RuntimeNotificationSourceType.APPROVAL,
                "task-1",
                Instant.parse("2026-06-25T00:00:00Z"),
                readAt
        );
    }
}
