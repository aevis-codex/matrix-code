package com.matrixcode.persistence;

import com.matrixcode.MatrixCodeServerApplication;
import com.matrixcode.agentruntime.application.AgentRuntimeRepository;
import com.matrixcode.agentruntime.domain.AgentRunEventRecord;
import com.matrixcode.agentruntime.domain.AgentRunRecord;
import com.matrixcode.agentruntime.domain.AgentRunStatus;
import com.matrixcode.persistence.application.MybatisPlusAgentRuntimeRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;

import javax.sql.DataSource;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class MybatisPlusAgentRuntimeRepositoryTest {

    @TempDir
    Path tempDir;

    @Test
    void Jdbc模式下Agent运行仓储使用MybatisPlus并保持事件时间线顺序() {
        var databaseName = "agent_runtime_" + UUID.randomUUID().toString().replace("-", "");
        var jdbcUrl = "jdbc:h2:mem:" + databaseName
                + ";MODE=MySQL;DATABASE_TO_LOWER=TRUE;CASE_INSENSITIVE_IDENTIFIERS=TRUE;DB_CLOSE_DELAY=-1";

        try (var context = startContext(jdbcUrl)) {
            assertThat(context.getBean(AgentRuntimeRepository.class))
                    .isInstanceOf(MybatisPlusAgentRuntimeRepository.class);

            var repository = context.getBean(AgentRuntimeRepository.class);
            var createdAt = Instant.parse("2026-06-25T12:00:00Z");
            var run = new AgentRunRecord(
                    "run-1",
                    "demo",
                    "DEVELOPER",
                    "coding",
                    "user-dev",
                    "deepseek",
                    "deepseek-chat",
                    AgentRunStatus.RUNNING,
                    "修复支付失败重试",
                    "执行中",
                    "测试命令超时",
                    true,
                    "run-0",
                    createdAt,
                    createdAt.plusSeconds(3),
                    null,
                    createdAt.plusSeconds(3)
            );

            repository.saveRun(run);
            repository.appendEvent(new AgentRunEventRecord(
                    "event-2",
                    "run-1",
                    "demo",
                    "TOOL_CALLED",
                    "运行测试命令",
                    "{\"command\":\"git status\"}",
                    createdAt.plusSeconds(20)
            ));
            repository.appendEvent(new AgentRunEventRecord(
                    "event-1",
                    "run-1",
                    "demo",
                    "STEP_STARTED",
                    "开始代码阅读",
                    "{\"step\":\"read-files\"}",
                    createdAt.plusSeconds(10)
            ));

            var recentRuns = repository.recentRuns("demo", 10);
            assertThat(recentRuns).containsExactly(run);
            assertThat(repository.findRun("run-1")).contains(run);
            assertThat(repository.findRun("missing-run")).isEmpty();
            assertThat(recentRuns.getFirst().failureSummary()).isEqualTo("测试命令超时");
            assertThat(recentRuns.getFirst().retryable()).isTrue();
            assertThat(recentRuns.getFirst().retryOfRunId()).isEqualTo("run-0");
            assertThat(repository.eventsForRun("run-1"))
                    .extracting(AgentRunEventRecord::id)
                    .containsExactly("event-1", "event-2");
        }
    }

    @Test
    void 可以按项目认领下一条排队运行并写入租约字段() {
        var databaseName = "agent_runtime_claim_" + UUID.randomUUID().toString().replace("-", "");
        var jdbcUrl = "jdbc:h2:mem:" + databaseName
                + ";MODE=MySQL;DATABASE_TO_LOWER=TRUE;CASE_INSENSITIVE_IDENTIFIERS=TRUE;DB_CLOSE_DELAY=-1";

        try (var context = startContext(jdbcUrl)) {
            var repository = context.getBean(AgentRuntimeRepository.class);
            var createdAt = Instant.parse("2026-06-25T12:00:00Z");
            repository.saveRun(new AgentRunRecord(
                    "run-1",
                    "demo",
                    "DEVELOPER",
                    "coding",
                    "user-dev",
                    "deepseek",
                    "deepseek-chat",
                    AgentRunStatus.QUEUED,
                    "第一个任务",
                    "等待执行",
                    createdAt,
                    null,
                    null,
                    createdAt
            ));
            repository.saveRun(new AgentRunRecord(
                    "run-2",
                    "demo",
                    "DEVELOPER",
                    "coding",
                    "user-dev",
                    "deepseek",
                    "deepseek-chat",
                    AgentRunStatus.QUEUED,
                    "第二个任务",
                    "等待执行",
                    createdAt.plusSeconds(1),
                    null,
                    null,
                    createdAt.plusSeconds(1)
            ));

            var claimed = repository.claimNextQueuedRun(
                    "demo",
                    "worker-1",
                    createdAt.plusSeconds(10),
                    createdAt.plusSeconds(910)
            );
            var next = repository.claimNextQueuedRun(
                    "demo",
                    "worker-2",
                    createdAt.plusSeconds(11),
                    createdAt.plusSeconds(911)
            );

            assertThat(claimed).hasValueSatisfying(run -> {
                assertThat(run.id()).isEqualTo("run-1");
                assertThat(run.status()).isEqualTo(AgentRunStatus.RUNNING);
                assertThat(run.claimedByUserId()).isEqualTo("worker-1");
                assertThat(run.claimedAt()).isEqualTo(createdAt.plusSeconds(10));
                assertThat(run.claimExpiresAt()).isEqualTo(createdAt.plusSeconds(910));
            });
            assertThat(next).hasValueSatisfying(run -> {
                assertThat(run.id()).isEqualTo("run-2");
                assertThat(run.status()).isEqualTo(AgentRunStatus.RUNNING);
                assertThat(run.claimedByUserId()).isEqualTo("worker-2");
            });
            assertThat(repository.claimNextQueuedRun(
                    "demo",
                    "worker-3",
                    createdAt.plusSeconds(12),
                    createdAt.plusSeconds(912)
            )).isEmpty();
        }
    }

    @Test
    void 可以按认领人续期运行租约并拒绝非认领人续期() {
        var databaseName = "agent_runtime_renew_lease_" + UUID.randomUUID().toString().replace("-", "");
        var jdbcUrl = "jdbc:h2:mem:" + databaseName
                + ";MODE=MySQL;DATABASE_TO_LOWER=TRUE;CASE_INSENSITIVE_IDENTIFIERS=TRUE;DB_CLOSE_DELAY=-1";

        try (var context = startContext(jdbcUrl)) {
            var repository = context.getBean(AgentRuntimeRepository.class);
            var createdAt = Instant.parse("2026-06-25T12:00:00Z");
            repository.saveRun(new AgentRunRecord(
                    "run-1",
                    "demo",
                    "DEVELOPER",
                    "coding",
                    "user-dev",
                    "deepseek",
                    "deepseek-chat",
                    AgentRunStatus.QUEUED,
                    "第一个任务",
                    "等待执行",
                    createdAt,
                    null,
                    null,
                    createdAt
            ));
            var claimed = repository.claimNextQueuedRun(
                    "demo",
                    "worker-1",
                    createdAt.plusSeconds(10),
                    createdAt.plusSeconds(910)
            ).orElseThrow();

            var renewed = repository.renewClaimLease(
                    "demo",
                    claimed.id(),
                    "worker-1",
                    createdAt.plusSeconds(20),
                    createdAt.plusSeconds(920)
            );
            var rejected = repository.renewClaimLease(
                    "demo",
                    claimed.id(),
                    "worker-2",
                    createdAt.plusSeconds(21),
                    createdAt.plusSeconds(921)
            );

            assertThat(renewed).hasValueSatisfying(run -> {
                assertThat(run.status()).isEqualTo(AgentRunStatus.RUNNING);
                assertThat(run.claimedByUserId()).isEqualTo("worker-1");
                assertThat(run.claimExpiresAt()).isEqualTo(createdAt.plusSeconds(920));
                assertThat(run.updatedAt()).isEqualTo(createdAt.plusSeconds(20));
            });
            assertThat(rejected).isEmpty();
            assertThat(repository.findRun(claimed.id()))
                    .hasValueSatisfying(run -> assertThat(run.claimExpiresAt()).isEqualTo(createdAt.plusSeconds(920)));
        }
    }

    @Test
    void 可以只回收到期的运行中租约并保留未到期运行() {
        var databaseName = "agent_runtime_expire_lease_" + UUID.randomUUID().toString().replace("-", "");
        var jdbcUrl = "jdbc:h2:mem:" + databaseName
                + ";MODE=MySQL;DATABASE_TO_LOWER=TRUE;CASE_INSENSITIVE_IDENTIFIERS=TRUE;DB_CLOSE_DELAY=-1";

        try (var context = startContext(jdbcUrl)) {
            var repository = context.getBean(AgentRuntimeRepository.class);
            var createdAt = Instant.parse("2026-06-25T12:00:00Z");
            repository.saveRun(new AgentRunRecord(
                    "run-expired",
                    "demo",
                    "DEVELOPER",
                    "coding",
                    "user-dev",
                    "deepseek",
                    "deepseek-chat",
                    AgentRunStatus.QUEUED,
                    "过期任务",
                    "等待执行",
                    createdAt,
                    null,
                    null,
                    createdAt
            ));
            repository.saveRun(new AgentRunRecord(
                    "run-active",
                    "demo",
                    "DEVELOPER",
                    "coding",
                    "user-dev",
                    "deepseek",
                    "deepseek-chat",
                    AgentRunStatus.QUEUED,
                    "未过期任务",
                    "等待执行",
                    createdAt.plusSeconds(1),
                    null,
                    null,
                    createdAt.plusSeconds(1)
            ));
            repository.claimNextQueuedRun("demo", "worker-1", createdAt.plusSeconds(5), createdAt.plusSeconds(9));
            repository.claimNextQueuedRun("demo", "worker-2", createdAt.plusSeconds(6), createdAt.plusSeconds(60));

            var expired = repository.expireRunningLeases(
                    "demo",
                    createdAt.plusSeconds(10),
                    10,
                    "Worker 租约已过期，运行被系统回收"
            );

            assertThat(expired).singleElement().satisfies(run -> {
                assertThat(run.id()).isEqualTo("run-expired");
                assertThat(run.status()).isEqualTo(AgentRunStatus.FAILED);
                assertThat(run.retryable()).isTrue();
                assertThat(run.failureSummary()).isEqualTo("Worker 租约已过期，运行被系统回收");
                assertThat(run.finishedAt()).isEqualTo(createdAt.plusSeconds(10));
            });
            assertThat(repository.findRun("run-active"))
                    .hasValueSatisfying(run -> assertThat(run.status()).isEqualTo(AgentRunStatus.RUNNING));
        }
    }

    @Test
    void 默认文件模式不创建数据源避免正式启动误依赖H2或数据库() {
        try (var context = new SpringApplicationBuilder(MatrixCodeServerApplication.class)
                .web(WebApplicationType.NONE)
                .properties(commonProperties())
                .run("--matrixcode.persistence.mode=file")) {
            assertThat(context.getBeanNamesForType(DataSource.class)).isEmpty();
            assertThat(context.getBeanNamesForType(AgentRuntimeRepository.class)).isEmpty();
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
}
