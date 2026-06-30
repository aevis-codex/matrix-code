package com.matrixcode.persistence;

import com.matrixcode.bug.domain.BugSeverity;
import com.matrixcode.bug.domain.BugStatus;
import com.matrixcode.bug.domain.ProjectBug;
import com.matrixcode.persistence.application.JdbcBugRepository;
import com.matrixcode.persistence.application.PersistenceModeProperties;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;

import java.sql.DriverManager;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class JdbcBugRepositoryTest {

    @Test
    void 保存后从正式Bug表恢复完整流转字段() throws Exception {
        var jdbcUrl = "jdbc:h2:mem:bugs_" + System.nanoTime()
                + ";MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1";
        migrate(jdbcUrl);
        var repository = new JdbcBugRepository(properties(jdbcUrl));
        var updatedAt = Instant.parse("2026-06-25T10:00:00Z");
        var bug = new ProjectBug(
                "bug-1",
                "demo",
                "支付失败未提示原因",
                BugSeverity.HIGH,
                BugStatus.FIXING,
                "提交支付",
                "展示失败原因",
                "只返回空白页",
                "测试",
                "开发",
                "开发处理中",
                updatedAt
        );

        repository.save(List.of(bug));

        var restored = repository.load();
        assertThat(restored).singleElement().satisfies(value -> {
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
