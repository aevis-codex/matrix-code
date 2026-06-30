package com.matrixcode.persistence;

import com.matrixcode.MatrixCodeServerApplication;
import com.matrixcode.localexecution.application.LocalFileService;
import com.matrixcode.localexecution.application.LocalGitDiffService;
import com.matrixcode.localexecution.application.WorkspaceRegistry;
import com.matrixcode.localexecution.domain.FileOperationRecord;
import com.matrixcode.localexecution.domain.FileOperationType;
import com.matrixcode.localexecution.domain.GitDiffSummary;
import com.matrixcode.workbench.application.WorkbenchStateStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;

import javax.sql.DataSource;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

class MybatisPlusLocalWorkspaceActivityRepositoryTest {

    @TempDir
    Path tempDir;

    @Test
    void Jdbc模式下文件操作和GitDiff写入正式表且不再写Workbench快照() throws Exception {
        var jdbcUrl = jdbcUrl("local_workspace_activity_mp_");
        var workspaceRoot = tempDir.resolve("workspace");
        Files.createDirectories(workspaceRoot);
        run(workspaceRoot, "git", "init");
        run(workspaceRoot, "git", "config", "user.email", "matrixcode@example.com");
        run(workspaceRoot, "git", "config", "user.name", "MatrixCode Test");
        Files.writeString(workspaceRoot.resolve("README.md"), "初始内容\n");
        run(workspaceRoot, "git", "add", "README.md");
        run(workspaceRoot, "git", "commit", "-m", "init");
        Files.writeString(workspaceRoot.resolve("README.md"), "初始内容\n新增内容\n");

        try (var context = startJdbcContext(jdbcUrl)) {
            var registry = context.getBean(WorkspaceRegistry.class);
            var localFiles = context.getBean(LocalFileService.class);
            var gitDiff = context.getBean(LocalGitDiffService.class);

            var workspaceId = registry.authorize("demo", "JDBC 工作区", workspaceRoot.toString()).id();
            localFiles.read("demo", workspaceId, "README.md");
            gitDiff.capture("demo", workspaceId);

            var dataSource = context.getBean(DataSource.class);
            assertThat(tableCount(dataSource, "matrixcode_local_file_operations")).isEqualTo(1);
            assertThat(tableCount(dataSource, "matrixcode_local_git_diff_summaries")).isEqualTo(1);
            assertThat(workbenchSnapshotCount(dataSource)).isZero();
        }

        try (var context = startJdbcContext(jdbcUrl)) {
            var localFiles = context.getBean(LocalFileService.class);
            var gitDiff = context.getBean(LocalGitDiffService.class);

            assertThat(localFiles.recentOperations("demo"))
                    .extracting(FileOperationRecord::relativePath)
                    .containsExactly("README.md");
            assertThat(gitDiff.latest("demo"))
                    .satisfies(summary -> {
                        assertThat(summary.repository()).isTrue();
                        assertThat(summary.changedFiles()).containsExactly("README.md");
                    });
        }
    }

    @Test
    void 正式表为空时从旧Workbench快照回填文件操作和GitDiff() throws Exception {
        var jdbcUrl = jdbcUrl("local_workspace_activity_backfill_");
        var createdAt = Instant.parse("2026-06-29T19:30:00Z");
        var fileOperation = new FileOperationRecord(
                "file-op-legacy",
                "demo",
                "workspace-legacy",
                FileOperationType.READ,
                "docs/legacy.md",
                "SUCCESS",
                "读取历史文件",
                createdAt
        );
        var gitDiff = new GitDiffSummary(
                "demo",
                "workspace-legacy",
                true,
                List.of("docs/legacy.md"),
                " docs/legacy.md | 1 +",
                createdAt
        );

        try (var context = startJdbcContext(jdbcUrl, true)) {
            var stateStore = context.getBean(WorkbenchStateStore.class);
            stateStore.saveFileOperations(Map.of("demo", List.of(fileOperation)));
            stateStore.saveGitDiffSummaries(Map.of("demo", gitDiff));
        }

        try (var context = startJdbcContext(jdbcUrl)) {
            var localFiles = context.getBean(LocalFileService.class);
            var localGitDiff = context.getBean(LocalGitDiffService.class);

            assertThat(localFiles.recentOperations("demo")).containsExactly(fileOperation);
            assertThat(localGitDiff.latest("demo")).isEqualTo(gitDiff);
            assertThat(tableCount(context.getBean(DataSource.class), "matrixcode_local_file_operations")).isEqualTo(1);
            assertThat(tableCount(context.getBean(DataSource.class), "matrixcode_local_git_diff_summaries")).isEqualTo(1);
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

    private void run(Path directory, String... command) throws Exception {
        var process = new ProcessBuilder(command)
                .directory(directory.toFile())
                .redirectErrorStream(true)
                .start();
        assertThat(process.waitFor(10, TimeUnit.SECONDS)).isTrue();
        assertThat(process.exitValue()).isEqualTo(0);
    }
}
