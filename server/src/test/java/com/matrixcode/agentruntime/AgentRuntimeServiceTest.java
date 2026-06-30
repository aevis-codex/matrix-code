package com.matrixcode.agentruntime;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.matrixcode.agentruntime.application.AgentRuntimeRepository;
import com.matrixcode.agentruntime.application.AgentRuntimeService;
import com.matrixcode.agentruntime.domain.AgentRunEventRecord;
import com.matrixcode.agentruntime.domain.AgentRunRecord;
import com.matrixcode.agentruntime.domain.AgentRunStatus;
import com.matrixcode.modelgateway.domain.ModelRole;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AgentRuntimeServiceTest {

    private final Clock fixedClock = Clock.fixed(Instant.parse("2026-06-25T12:00:00Z"), ZoneOffset.UTC);

    @Test
    void 记录运行会归一操作者并写入运行主记录和结构化事件() {
        var repository = new RecordingAgentRuntimeRepository();
        var service = new AgentRuntimeService(Optional.of(repository), new ObjectMapper(), fixedClock);

        var run = service.saveRun(
                "run-1",
                "demo",
                ModelRole.DEVELOPER,
                "coding",
                " ",
                "deepseek",
                "deepseek-chat",
                AgentRunStatus.QUEUED,
                "实现登录接口",
                "编码任务已规划",
                null,
                null
        );
        var event = service.appendEvent(
                "run-1",
                "demo",
                "TASK_PLANNED",
                "编码任务已规划",
                java.util.Map.of("workspaceId", "workspace-main", "goal", "实现登录接口")
        );

        assertThat(run.actorUserId()).isEqualTo("system");
        assertThat(run.createdAt()).isEqualTo(fixedClock.instant());
        assertThat(repository.savedRuns).containsExactly(run);
        assertThat(repository.events).containsExactly(event);
        assertThat(event.eventPayload()).contains("\"workspaceId\":\"workspace-main\"");
        assertThat(event.eventPayload()).contains("\"goal\":\"实现登录接口\"");
    }

    @Test
    void 更新运行状态会保留运行编号并写入新的状态摘要() {
        var repository = new RecordingAgentRuntimeRepository();
        var service = new AgentRuntimeService(Optional.of(repository), new ObjectMapper(), fixedClock);

        var queued = service.saveRun(
                "run-1",
                "demo",
                ModelRole.DEVELOPER,
                "coding",
                "user-dev",
                "qwen",
                "qwen-max",
                AgentRunStatus.QUEUED,
                "实现登录接口",
                "编码任务已规划",
                null,
                null
        );
        var running = service.saveRun(
                queued.id(),
                queued.projectId(),
                ModelRole.DEVELOPER,
                queued.agentKind(),
                queued.actorUserId(),
                queued.providerId(),
                queued.modelName(),
                AgentRunStatus.RUNNING,
                queued.goal(),
                "执行准备已生成",
                fixedClock.instant(),
                null
        );

        assertThat(repository.savedRuns).containsExactly(queued, running);
        assertThat(running.id()).isEqualTo("run-1");
        assertThat(running.status()).isEqualTo(AgentRunStatus.RUNNING);
        assertThat(running.startedAt()).isEqualTo(fixedClock.instant());
    }

    @Test
    void 标记运行中会保存主记录并追加开始事件() {
        var repository = new RecordingAgentRuntimeRepository();
        var service = new AgentRuntimeService(Optional.of(repository), new ObjectMapper(), fixedClock);

        var run = service.markRunning(
                "run-1",
                "demo",
                ModelRole.DEVELOPER,
                "coding",
                "user-dev",
                "qwen",
                "qwen-plus",
                "实现登录接口",
                "执行准备已生成"
        );

        assertThat(run.status()).isEqualTo(AgentRunStatus.RUNNING);
        assertThat(run.startedAt()).isEqualTo(fixedClock.instant());
        assertThat(run.finishedAt()).isNull();
        assertThat(repository.savedRuns).containsExactly(run);
        assertThat(repository.events).singleElement().satisfies(event -> {
            assertThat(event.eventType()).isEqualTo("RUN_STARTED");
            assertThat(event.eventTitle()).isEqualTo("运行开始");
            assertThat(event.eventPayload()).contains("\"summary\":\"执行准备已生成\"");
            assertThat(event.eventPayload()).contains("\"providerId\":\"qwen\"");
            assertThat(event.eventPayload()).contains("\"modelName\":\"qwen-plus\"");
            assertThat(event.eventPayload()).contains("\"role\":\"DEVELOPER\"");
            assertThat(event.eventPayload()).contains("\"agentKind\":\"coding\"");
        });
    }

    @Test
    void 标记成功会保存主记录并追加成功事件() {
        var repository = new RecordingAgentRuntimeRepository();
        var service = new AgentRuntimeService(Optional.of(repository), new ObjectMapper(), fixedClock);

        var run = service.markSucceeded(
                "run-1",
                "demo",
                ModelRole.DEVELOPER,
                "coding",
                "user-dev",
                "deepseek",
                "deepseek-chat",
                "实现登录接口",
                "编码交付已完成"
        );

        assertThat(run.status()).isEqualTo(AgentRunStatus.SUCCEEDED);
        assertThat(run.startedAt()).isEqualTo(fixedClock.instant());
        assertThat(run.finishedAt()).isEqualTo(fixedClock.instant());
        assertThat(repository.savedRuns).containsExactly(run);
        assertThat(repository.events).singleElement().satisfies(event -> {
            assertThat(event.eventType()).isEqualTo("RUN_SUCCEEDED");
            assertThat(event.eventTitle()).isEqualTo("运行成功");
            assertThat(event.eventPayload()).contains("\"summary\":\"编码交付已完成\"");
            assertThat(event.eventPayload()).contains("\"providerId\":\"deepseek\"");
            assertThat(event.eventPayload()).contains("\"modelName\":\"deepseek-chat\"");
            assertThat(event.eventPayload()).contains("\"role\":\"DEVELOPER\"");
            assertThat(event.eventPayload()).contains("\"agentKind\":\"coding\"");
        });
    }

    @Test
    void 标记失败会保存可恢复主记录并追加失败事件() {
        var repository = new RecordingAgentRuntimeRepository();
        var service = new AgentRuntimeService(Optional.of(repository), new ObjectMapper(), fixedClock);

        var run = service.markFailed(
                "run-1",
                "demo",
                ModelRole.DEVELOPER,
                "coding",
                "user-dev",
                "deepseek",
                "deepseek-chat",
                "修复支付失败重试",
                "测试命令超时，未产生交接文档",
                true,
                "run-0"
        );

        assertThat(run.status()).isEqualTo(AgentRunStatus.FAILED);
        assertThat(run.failureSummary()).isEqualTo("测试命令超时，未产生交接文档");
        assertThat(run.retryable()).isTrue();
        assertThat(run.retryOfRunId()).isEqualTo("run-0");
        assertThat(run.startedAt()).isEqualTo(fixedClock.instant());
        assertThat(run.finishedAt()).isEqualTo(fixedClock.instant());
        assertThat(repository.savedRuns).containsExactly(run);
        assertThat(repository.events).hasSize(1);
        assertThat(repository.events.getFirst().eventType()).isEqualTo("RUN_FAILED");
        assertThat(repository.events.getFirst().eventTitle()).isEqualTo("运行失败");
        assertThat(repository.events.getFirst().eventPayload()).contains("\"failureSummary\":\"测试命令超时，未产生交接文档\"");
        assertThat(repository.events.getFirst().eventPayload()).contains("\"retryable\":true");
        assertThat(repository.events.getFirst().eventPayload()).contains("\"retryOfRunId\":\"run-0\"");
    }

    @Test
    void 可重试失败运行会生成恢复计划并排队新运行() {
        var repository = new RecordingAgentRuntimeRepository();
        var service = new AgentRuntimeService(Optional.of(repository), new ObjectMapper(), fixedClock);
        var failedRun = service.markFailed(
                "run-failed",
                "demo",
                ModelRole.DEVELOPER,
                "coding",
                "user-dev",
                "deepseek",
                "deepseek-chat",
                "生成交接文档",
                "工具执行超时",
                true,
                null
        );

        var plan = service.recoveryPlan("demo", failedRun.id());
        var retryRun = service.queueRetry("demo", failedRun.id(), "user-reviewer");

        assertThat(plan.sourceRunId()).isEqualTo(failedRun.id());
        assertThat(plan.canRetry()).isTrue();
        assertThat(plan.blockedReason()).isEmpty();
        assertThat(plan.sourceRun()).contains(failedRun);
        assertThat(retryRun.status()).isEqualTo(AgentRunStatus.QUEUED);
        assertThat(retryRun.retryOfRunId()).isEqualTo(failedRun.id());
        assertThat(retryRun.actorUserId()).isEqualTo("user-reviewer");
        assertThat(retryRun.failureSummary()).isEqualTo("工具执行超时");
        assertThat(retryRun.retryable()).isFalse();
        assertThat(repository.savedRuns).contains(failedRun, retryRun);
        assertThat(repository.events).extracting(AgentRunEventRecord::eventType)
                .containsExactly("RUN_FAILED", "RUN_RETRY_REQUESTED", "RUN_RETRY_QUEUED");
        assertThat(repository.events.get(1).eventPayload()).contains("\"retryRunId\":\"" + retryRun.id() + "\"");
        assertThat(repository.events.get(2).eventPayload()).contains("\"sourceRunId\":\"" + failedRun.id() + "\"");
    }

    @Test
    void 不可重试失败运行会返回阻塞计划并拒绝排队() {
        var repository = new RecordingAgentRuntimeRepository();
        var service = new AgentRuntimeService(Optional.of(repository), new ObjectMapper(), fixedClock);
        var failedRun = service.markFailed(
                "run-failed",
                "demo",
                ModelRole.DEVELOPER,
                "coding",
                "user-dev",
                "deepseek",
                "deepseek-chat",
                "生成交接文档",
                "需求已冻结，不能自动恢复",
                false,
                null
        );

        var plan = service.recoveryPlan("demo", failedRun.id());

        assertThat(plan.canRetry()).isFalse();
        assertThat(plan.blockedReason()).isEqualTo("该失败运行标记为不可重试");
        assertThatThrownBy(() -> service.queueRetry("demo", failedRun.id(), "user-reviewer"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("该失败运行标记为不可重试");
    }

    @Test
    void 非失败运行会返回阻塞计划并拒绝排队() {
        var repository = new RecordingAgentRuntimeRepository();
        var service = new AgentRuntimeService(Optional.of(repository), new ObjectMapper(), fixedClock);
        var succeededRun = service.markSucceeded(
                "run-succeeded",
                "demo",
                ModelRole.DEVELOPER,
                "coding",
                "user-dev",
                "qwen",
                "qwen-plus",
                "生成交接文档",
                "已完成"
        );

        var plan = service.recoveryPlan("demo", succeededRun.id());

        assertThat(plan.canRetry()).isFalse();
        assertThat(plan.blockedReason()).isEqualTo("只有失败运行可以恢复");
        assertThatThrownBy(() -> service.queueRetry("demo", succeededRun.id(), "user-reviewer"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("只有失败运行可以恢复");
    }

    @Test
    void 排队运行认领后进入运行中并保留恢复来源() {
        var repository = new RecordingAgentRuntimeRepository();
        var service = new AgentRuntimeService(Optional.of(repository), new ObjectMapper(), fixedClock);
        var failedRun = service.markFailed(
                "run-failed",
                "demo",
                ModelRole.DEVELOPER,
                "coding",
                "user-dev",
                "deepseek",
                "deepseek-chat",
                "生成交接文档",
                "工具执行超时",
                true,
                null
        );
        var retryRun = service.queueRetry("demo", failedRun.id(), "user-reviewer");

        var claimed = service.claimQueuedRun("demo", retryRun.id(), "worker-1");

        assertThat(claimed.id()).isEqualTo(retryRun.id());
        assertThat(claimed.status()).isEqualTo(AgentRunStatus.RUNNING);
        assertThat(claimed.actorUserId()).isEqualTo(retryRun.actorUserId());
        assertThat(claimed.retryOfRunId()).isEqualTo(failedRun.id());
        assertThat(claimed.failureSummary()).isEqualTo("工具执行超时");
        assertThat(claimed.createdAt()).isEqualTo(retryRun.createdAt());
        assertThat(claimed.startedAt()).isEqualTo(fixedClock.instant());
        assertThat(claimed.finishedAt()).isNull();
        assertThat(repository.events).extracting(AgentRunEventRecord::eventType)
                .containsExactly(
                        "RUN_FAILED",
                        "RUN_RETRY_REQUESTED",
                        "RUN_RETRY_QUEUED",
                        "RUN_CLAIMED",
                        "RUN_STARTED",
                        "RUN_LEASED"
                );
        assertThat(repository.events.get(3).eventPayload()).contains("\"claimedBy\":\"worker-1\"");
        assertThat(repository.events.get(4).eventPayload()).contains("\"summary\":\"运行已认领\"");
        assertThat(repository.events.get(5).eventPayload()).contains("\"claimExpiresAt\":\"2026-06-25T12:15:00Z\"");
    }

    @Test
    void 非排队运行不能被认领() {
        var repository = new RecordingAgentRuntimeRepository();
        var service = new AgentRuntimeService(Optional.of(repository), new ObjectMapper(), fixedClock);
        var runningRun = service.markRunning(
                "run-running",
                "demo",
                ModelRole.DEVELOPER,
                "coding",
                "user-dev",
                "deepseek",
                "deepseek-chat",
                "生成交接文档",
                "执行中"
        );

        assertThatThrownBy(() -> service.claimQueuedRun("demo", runningRun.id(), "worker-1"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("只有排队运行可以认领");
    }

    @Test
    void 可以从项目队列认领最早的排队运行并写入租约事件() {
        var repository = new RecordingAgentRuntimeRepository();
        var service = new AgentRuntimeService(Optional.of(repository), new ObjectMapper(), fixedClock);
        var first = service.saveRun(
                "run-1",
                "demo",
                ModelRole.DEVELOPER,
                "coding",
                "user-dev",
                "deepseek",
                "deepseek-chat",
                AgentRunStatus.QUEUED,
                "第一个任务",
                "等待执行",
                null,
                null
        );
        service.saveRun(
                "run-2",
                "demo",
                ModelRole.DEVELOPER,
                "coding",
                "user-dev",
                "deepseek",
                "deepseek-chat",
                AgentRunStatus.QUEUED,
                "第二个任务",
                "等待执行",
                null,
                null
        );

        var claimed = service.claimNextQueuedRun("demo", "worker-1");

        assertThat(claimed).hasValueSatisfying(run -> {
            assertThat(run.id()).isEqualTo(first.id());
            assertThat(run.status()).isEqualTo(AgentRunStatus.RUNNING);
            assertThat(run.claimedByUserId()).isEqualTo("worker-1");
            assertThat(run.claimedAt()).isEqualTo(fixedClock.instant());
            assertThat(run.claimExpiresAt()).isEqualTo(fixedClock.instant().plusSeconds(900));
        });
        assertThat(repository.events).extracting(AgentRunEventRecord::eventType)
                .containsExactly("RUN_CLAIMED", "RUN_STARTED", "RUN_LEASED");
    }

    @Test
    void 没有排队运行时认领下一条返回空且不写事件() {
        var repository = new RecordingAgentRuntimeRepository();
        var service = new AgentRuntimeService(Optional.of(repository), new ObjectMapper(), fixedClock);
        service.markRunning(
                "run-running",
                "demo",
                ModelRole.DEVELOPER,
                "coding",
                "user-dev",
                "deepseek",
                "deepseek-chat",
                "已开始任务",
                "执行中"
        );
        repository.events.clear();

        var claimed = service.claimNextQueuedRun("demo", "worker-1");

        assertThat(claimed).isEmpty();
        assertThat(repository.events).isEmpty();
    }

    @Test
    void 当前认领人可以续期运行租约并写入事件() {
        var repository = new RecordingAgentRuntimeRepository();
        var service = new AgentRuntimeService(Optional.of(repository), new ObjectMapper(), fixedClock);
        service.saveRun(
                "run-1",
                "demo",
                ModelRole.DEVELOPER,
                "coding",
                "user-dev",
                "deepseek",
                "deepseek-chat",
                AgentRunStatus.QUEUED,
                "修复问题",
                "等待执行",
                null,
                null
        );
        var claimed = service.claimNextQueuedRun("demo", "worker-1").orElseThrow();

        var renewed = service.renewClaimLease("demo", claimed.id(), "worker-1");

        assertThat(renewed).hasValueSatisfying(run -> {
            assertThat(run.status()).isEqualTo(AgentRunStatus.RUNNING);
            assertThat(run.claimedByUserId()).isEqualTo("worker-1");
            assertThat(run.claimExpiresAt()).isEqualTo(fixedClock.instant().plusSeconds(900));
        });
        assertThat(repository.events).extracting(AgentRunEventRecord::eventType)
                .contains("RUN_LEASE_RENEWED");
    }

    @Test
    void 非认领人续期运行租约会返回空且不写事件() {
        var repository = new RecordingAgentRuntimeRepository();
        var service = new AgentRuntimeService(Optional.of(repository), new ObjectMapper(), fixedClock);
        service.saveRun(
                "run-1",
                "demo",
                ModelRole.DEVELOPER,
                "coding",
                "user-dev",
                "deepseek",
                "deepseek-chat",
                AgentRunStatus.QUEUED,
                "修复问题",
                "等待执行",
                null,
                null
        );
        var claimed = service.claimNextQueuedRun("demo", "worker-1").orElseThrow();
        repository.events.clear();

        var renewed = service.renewClaimLease("demo", claimed.id(), "worker-2");

        assertThat(renewed).isEmpty();
        assertThat(repository.events).isEmpty();
    }

    @Test
    void 可以把过期运行回收为可重试失败并写入审计事件() {
        var repository = new RecordingAgentRuntimeRepository();
        var service = new AgentRuntimeService(Optional.of(repository), new ObjectMapper(), fixedClock);
        service.saveRun(
                "run-1",
                "demo",
                ModelRole.DEVELOPER,
                "coding",
                "user-dev",
                "deepseek",
                "deepseek-chat",
                AgentRunStatus.QUEUED,
                "修复问题",
                "等待执行",
                null,
                null
        );
        var claimed = service.claimNextQueuedRun("demo", "worker-1").orElseThrow();
        repository.forceLeaseExpiry(claimed.id(), fixedClock.instant().minusSeconds(1));

        var expired = service.expireRunningLeases("demo", 10);

        assertThat(expired).hasSize(1);
        assertThat(expired.getFirst().status()).isEqualTo(AgentRunStatus.FAILED);
        assertThat(expired.getFirst().retryable()).isTrue();
        assertThat(expired.getFirst().failureSummary()).isEqualTo("Worker 租约已过期，运行被系统回收");
        assertThat(repository.events).extracting(AgentRunEventRecord::eventType)
                .contains("RUN_LEASE_EXPIRED", "RUN_FAILED");
    }

    @Test
    void 追加工具Trace会写入统一事件格式() {
        var repository = new RecordingAgentRuntimeRepository();
        var service = new AgentRuntimeService(Optional.of(repository), new ObjectMapper(), fixedClock);

        var event = service.appendToolTrace(
                "run-1",
                "demo",
                "local-execution.commands",
                "submit-test-command",
                "APPROVAL_PENDING",
                "task-1",
                "测试命令已提交审批",
                java.util.Map.of("workspaceId", "workspace-main")
        );

        assertThat(event.eventType()).isEqualTo("TOOL_TRACE");
        assertThat(event.eventTitle()).isEqualTo("工具调用 trace");
        assertThat(event.eventPayload()).contains("\"toolName\":\"local-execution.commands\"");
        assertThat(event.eventPayload()).contains("\"action\":\"submit-test-command\"");
        assertThat(event.eventPayload()).contains("\"status\":\"APPROVAL_PENDING\"");
        assertThat(event.eventPayload()).contains("\"referenceId\":\"task-1\"");
        assertThat(event.eventPayload()).contains("\"summary\":\"测试命令已提交审批\"");
        assertThat(event.eventPayload()).contains("\"workspaceId\":\"workspace-main\"");
        assertThat(repository.events).containsExactly(event);
    }

    @Test
    void 追加模型请求Trace会写入供应商和缓存摘要() {
        var repository = new RecordingAgentRuntimeRepository();
        var service = new AgentRuntimeService(Optional.of(repository), new ObjectMapper(), fixedClock);

        var event = service.appendModelRequestTrace(
                "run-1",
                "demo",
                "request-1",
                "deepseek",
                "deepseek-chat",
                "PROVIDER",
                "fp-cache-001",
                "stable-platform-prefix-v1",
                "role-prompt-and-dynamic-context",
                "deepseek-reasonix-partitions-v1",
                "partition-fp-001",
                2,
                3
        );

        assertThat(event.eventType()).isEqualTo("TOOL_TRACE");
        assertThat(event.eventTitle()).isEqualTo("工具调用 trace");
        assertThat(event.eventPayload()).contains("\"toolName\":\"model-gateway.model-requests\"");
        assertThat(event.eventPayload()).contains("\"action\":\"complete-model-request\"");
        assertThat(event.eventPayload()).contains("\"status\":\"COMPLETED\"");
        assertThat(event.eventPayload()).contains("\"referenceId\":\"request-1\"");
        assertThat(event.eventPayload()).contains("\"summary\":\"模型请求已完成\"");
        assertThat(event.eventPayload()).contains("\"providerId\":\"deepseek\"");
        assertThat(event.eventPayload()).contains("\"modelName\":\"deepseek-chat\"");
        assertThat(event.eventPayload()).contains("\"cacheSource\":\"PROVIDER\"");
        assertThat(event.eventPayload()).contains("\"stablePrefixHash\":\"fp-cache-001\"");
        assertThat(event.eventPayload()).contains("\"cachePolicyId\":\"stable-platform-prefix-v1\"");
        assertThat(event.eventPayload()).contains("\"volatileSuffixStrategy\":\"role-prompt-and-dynamic-context\"");
        assertThat(event.eventPayload()).contains("\"promptPartitionPolicyId\":\"deepseek-reasonix-partitions-v1\"");
        assertThat(event.eventPayload()).contains("\"promptPartitionFingerprint\":\"partition-fp-001\"");
        assertThat(event.eventPayload()).contains("\"stablePartitionCount\":2");
        assertThat(event.eventPayload()).contains("\"volatilePartitionCount\":3");
        assertThat(repository.events).containsExactly(event);
    }

    @Test
    void 没有正式仓储时记录方法不阻断主流程且查询返回空列表() {
        var service = new AgentRuntimeService(Optional.empty(), new ObjectMapper(), fixedClock);

        var run = service.saveRun(
                "run-1",
                "demo",
                ModelRole.DEVELOPER,
                "coding",
                "user-dev",
                "deepseek",
                "deepseek-chat",
                AgentRunStatus.SUCCEEDED,
                "实现登录接口",
                "已完成",
                fixedClock.instant(),
                fixedClock.instant()
        );
        var event = service.appendEvent("run-1", "demo", "HANDOFF_RECORDED", "交接已记录", java.util.Map.of());

        assertThat(run.id()).isEqualTo("run-1");
        assertThat(event.runId()).isEqualTo("run-1");
        assertThat(service.recentRuns("demo", 10)).isEmpty();
        assertThat(service.eventsForRun("run-1")).isEmpty();
    }

    private static final class RecordingAgentRuntimeRepository implements AgentRuntimeRepository {

        private final List<AgentRunRecord> savedRuns = new ArrayList<>();
        private final List<AgentRunEventRecord> events = new ArrayList<>();

        @Override
        public void saveRun(AgentRunRecord run) {
            savedRuns.add(run);
        }

        @Override
        public void appendEvent(AgentRunEventRecord event) {
            events.add(event);
        }

        @Override
        public Optional<AgentRunRecord> findRun(String runId) {
            for (var index = savedRuns.size() - 1; index >= 0; index--) {
                var run = savedRuns.get(index);
                if (run.id().equals(runId)) {
                    return Optional.of(run);
                }
            }
            return Optional.empty();
        }

        @Override
        public Optional<AgentRunRecord> claimNextQueuedRun(
                String projectId,
                String claimedByUserId,
                Instant claimedAt,
                Instant claimExpiresAt
        ) {
            return savedRuns.stream()
                    .filter(run -> run.projectId().equals(projectId))
                    .filter(run -> run.status() == AgentRunStatus.QUEUED)
                    .findFirst()
                    .map(run -> {
                        var claimed = new AgentRunRecord(
                                run.id(),
                                run.projectId(),
                                run.roleKey(),
                                run.agentKind(),
                                run.actorUserId(),
                                run.providerId(),
                                run.modelName(),
                                AgentRunStatus.RUNNING,
                                run.goal(),
                                "运行已认领",
                                run.failureSummary(),
                                run.retryable(),
                                run.retryOfRunId(),
                                run.createdAt(),
                                claimedAt,
                                null,
                                claimedAt,
                                claimedByUserId,
                                claimedAt,
                                claimExpiresAt
                        );
                        savedRuns.add(claimed);
                        return claimed;
                    });
        }

        @Override
        public Optional<AgentRunRecord> renewClaimLease(
                String projectId,
                String runId,
                String claimedByUserId,
                Instant renewedAt,
                Instant claimExpiresAt
        ) {
            return findRun(runId)
                    .filter(run -> run.projectId().equals(projectId))
                    .filter(run -> run.status() == AgentRunStatus.RUNNING)
                    .filter(run -> claimedByUserId.equals(run.claimedByUserId()))
                    .map(run -> {
                        var renewed = new AgentRunRecord(
                                run.id(),
                                run.projectId(),
                                run.roleKey(),
                                run.agentKind(),
                                run.actorUserId(),
                                run.providerId(),
                                run.modelName(),
                                run.status(),
                                run.goal(),
                                run.summary(),
                                run.failureSummary(),
                                run.retryable(),
                                run.retryOfRunId(),
                                run.createdAt(),
                                run.startedAt(),
                                run.finishedAt(),
                                renewedAt,
                                run.claimedByUserId(),
                                run.claimedAt(),
                                claimExpiresAt
                        );
                        savedRuns.add(renewed);
                        return renewed;
                    });
        }

        @Override
        public List<AgentRunRecord> expireRunningLeases(
                String projectId,
                Instant now,
                int limit,
                String failureSummary
        ) {
            var seenRunIds = new HashSet<String>();
            var expired = new ArrayList<AgentRunRecord>();
            for (var index = savedRuns.size() - 1; index >= 0 && expired.size() < limit; index--) {
                var run = savedRuns.get(index);
                if (!seenRunIds.add(run.id())) {
                    continue;
                }
                if (!run.projectId().equals(projectId)
                        || run.status() != AgentRunStatus.RUNNING
                        || run.claimExpiresAt() == null
                        || run.claimExpiresAt().isAfter(now)) {
                    continue;
                }
                var failed = new AgentRunRecord(
                        run.id(),
                        run.projectId(),
                        run.roleKey(),
                        run.agentKind(),
                        run.actorUserId(),
                        run.providerId(),
                        run.modelName(),
                        AgentRunStatus.FAILED,
                        run.goal(),
                        failureSummary,
                        failureSummary,
                        true,
                        run.retryOfRunId(),
                        run.createdAt(),
                        run.startedAt(),
                        now,
                        now,
                        run.claimedByUserId(),
                        run.claimedAt(),
                        run.claimExpiresAt()
                );
                savedRuns.add(failed);
                expired.add(failed);
            }
            return expired;
        }

        void forceLeaseExpiry(String runId, Instant claimExpiresAt) {
            findRun(runId).ifPresent(run -> savedRuns.add(new AgentRunRecord(
                    run.id(),
                    run.projectId(),
                    run.roleKey(),
                    run.agentKind(),
                    run.actorUserId(),
                    run.providerId(),
                    run.modelName(),
                    run.status(),
                    run.goal(),
                    run.summary(),
                    run.failureSummary(),
                    run.retryable(),
                    run.retryOfRunId(),
                    run.createdAt(),
                    run.startedAt(),
                    run.finishedAt(),
                    run.updatedAt(),
                    run.claimedByUserId(),
                    run.claimedAt(),
                    claimExpiresAt
            )));
        }

        @Override
        public List<AgentRunRecord> recentRuns(String projectId, int limit) {
            return savedRuns.stream()
                    .filter(run -> run.projectId().equals(projectId))
                    .limit(limit)
                    .toList();
        }

        @Override
        public List<AgentRunEventRecord> eventsForRun(String runId) {
            return events.stream()
                    .filter(event -> event.runId().equals(runId))
                    .toList();
        }
    }
}
