package com.matrixcode.persistence;

import com.matrixcode.MatrixCodeServerApplication;
import com.matrixcode.bug.application.BugRepository;
import com.matrixcode.bug.domain.BugSeverity;
import com.matrixcode.bug.domain.BugStatus;
import com.matrixcode.bug.domain.ProjectBug;
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

class MybatisPlusBugRepositoryTest {

    private static final Instant UPDATED_AT = Instant.parse("2026-06-29T18:00:00Z");

    @TempDir
    Path tempDir;

    @Test
    void Jdbc模式下Bug仓储使用MybatisPlus并保持正式表读写行为() throws Exception {
        var jdbcUrl = jdbcUrl("bugs_mp_");

        try (var context = startJdbcContext(jdbcUrl)) {
            var repository = context.getBean(BugRepository.class);
            assertThat(repository.getClass().getName())
                    .contains("MybatisPlusBugRepository");

            repository.save(List.of(bug(
                    "bug-1",
                    "支付失败未提示原因",
                    BugSeverity.HIGH,
                    BugStatus.FIXING,
                    "提交支付",
                    "展示失败原因",
                    "只返回空白页",
                    "测试",
                    "开发",
                    "开发处理中",
                    UPDATED_AT
            )));

            assertThat(repository.load()).singleElement().satisfies(value -> {
                assertThat(value.id()).isEqualTo("bug-1");
                assertThat(value.projectId()).isEqualTo("demo");
                assertThat(value.title()).isEqualTo("支付失败未提示原因");
                assertThat(value.severity()).isEqualTo(BugSeverity.HIGH);
                assertThat(value.status()).isEqualTo(BugStatus.FIXING);
                assertThat(value.steps()).isEqualTo("提交支付");
                assertThat(value.expected()).isEqualTo("展示失败原因");
                assertThat(value.actual()).isEqualTo("只返回空白页");
                assertThat(value.createdByRole()).isEqualTo("测试");
                assertThat(value.currentOwnerRole()).isEqualTo("开发");
                assertThat(value.lastNote()).isEqualTo("开发处理中");
                assertThat(value.updatedAt()).isEqualTo(UPDATED_AT);
            });
            assertThat(tableCount(context.getBean(DataSource.class), "matrixcode_bugs")).isEqualTo(1);
            assertThat(tableCount(context.getBean(DataSource.class), "matrixcode_projects")).isEqualTo(1);
        }
    }

    @Test
    void 保存同一Bug时执行Upsert不重复插入() throws Exception {
        var jdbcUrl = jdbcUrl("bugs_upsert_mp_");

        try (var context = startJdbcContext(jdbcUrl)) {
            var repository = context.getBean(BugRepository.class);
            repository.save(List.of(bug(
                    "bug-1",
                    "支付失败未提示原因",
                    BugSeverity.HIGH,
                    BugStatus.NEW,
                    "提交支付",
                    "展示失败原因",
                    "只返回空白页",
                    "测试",
                    "开发",
                    "待处理",
                    UPDATED_AT
            )));

            repository.save(List.of(bug(
                    "bug-1",
                    "支付失败未提示原因",
                    BugSeverity.MEDIUM,
                    BugStatus.CLOSED,
                    "提交支付并等待回调",
                    "展示失败原因并可重试",
                    "已恢复提示",
                    "测试",
                    "测试",
                    "回归通过",
                    UPDATED_AT.plusSeconds(60)
            )));

            assertThat(repository.load()).singleElement().satisfies(value -> {
                assertThat(value.severity()).isEqualTo(BugSeverity.MEDIUM);
                assertThat(value.status()).isEqualTo(BugStatus.CLOSED);
                assertThat(value.steps()).isEqualTo("提交支付并等待回调");
                assertThat(value.expected()).isEqualTo("展示失败原因并可重试");
                assertThat(value.actual()).isEqualTo("已恢复提示");
                assertThat(value.currentOwnerRole()).isEqualTo("测试");
                assertThat(value.lastNote()).isEqualTo("回归通过");
                assertThat(value.updatedAt()).isEqualTo(UPDATED_AT.plusSeconds(60));
            });
            assertThat(tableCount(context.getBean(DataSource.class), "matrixcode_bugs")).isEqualTo(1);
        }
    }

    @Test
    void 默认文件模式不创建数据源和Bug仓储Bean() {
        try (var context = new SpringApplicationBuilder(MatrixCodeServerApplication.class)
                .web(WebApplicationType.NONE)
                .properties(commonProperties())
                .run("--matrixcode.persistence.mode=file")) {
            assertThat(context.getBeanNamesForType(DataSource.class)).isEmpty();
            assertThat(context.getBeanNamesForType(BugRepository.class)).isEmpty();
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

    private ProjectBug bug(
            String id,
            String title,
            BugSeverity severity,
            BugStatus status,
            String steps,
            String expected,
            String actual,
            String createdByRole,
            String currentOwnerRole,
            String lastNote,
            Instant updatedAt
    ) {
        return new ProjectBug(
                id,
                "demo",
                title,
                severity,
                status,
                steps,
                expected,
                actual,
                createdByRole,
                currentOwnerRole,
                lastNote,
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
