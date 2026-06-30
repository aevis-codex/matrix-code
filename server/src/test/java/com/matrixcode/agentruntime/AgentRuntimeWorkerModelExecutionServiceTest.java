package com.matrixcode.agentruntime;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.matrixcode.agent.application.LocalProductDraftAgent;
import com.matrixcode.agentruntime.application.AgentRuntimeRepository;
import com.matrixcode.agentruntime.application.AgentRuntimeService;
import com.matrixcode.agentruntime.application.AgentRuntimeWorkerModelExecutionService;
import com.matrixcode.agentruntime.application.AgentRuntimeWorkerService;
import com.matrixcode.agentruntime.domain.AgentRunEventRecord;
import com.matrixcode.agentruntime.domain.AgentRunRecord;
import com.matrixcode.agentruntime.domain.AgentRunStatus;
import com.matrixcode.context.application.ContextEngine;
import com.matrixcode.modelgateway.application.DeterministicModelAdapter;
import com.matrixcode.modelgateway.application.ModelGatewayService;
import com.matrixcode.modelgateway.application.ModelProviderRegistry;
import com.matrixcode.modelgateway.application.PromptCacheEstimator;
import com.matrixcode.modelgateway.application.PromptContractBuilder;
import com.matrixcode.modelgateway.application.RoleModelBindingService;
import com.matrixcode.modelgateway.domain.ModelRole;
import com.matrixcode.realtime.application.ProjectEventBus;
import com.matrixcode.roleagent.application.RoleAgentConfigService;
import com.matrixcode.usage.application.UsageCalculator;
import com.matrixcode.workbench.application.InMemoryWorkbenchStateStore;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class AgentRuntimeWorkerModelExecutionServiceTest {

    @Test
    void 当前认领人且租约有效时会执行模型请求并写入运行事件() {
        var repository = new RecordingAgentRuntimeRepository();
        var runtimeService = new AgentRuntimeService(Optional.of(repository), new ObjectMapper(), java.time.Clock.systemUTC());
        var gateway = modelGateway(runtimeService);
        var service = new AgentRuntimeWorkerModelExecutionService(
                runtimeService,
                new AgentRuntimeWorkerService(runtimeService),
                gateway
        );
        queueAndClaim(runtimeService, repository);

        var result = service.executeModelRequest("demo", "run-1", "worker-1");

        assertThat(result.executed()).isTrue();
        assertThat(result.blockedReason()).isBlank();
        assertThat(result.requestId()).isNotBlank();
        assertThat(result.providerId()).isEqualTo("local-deterministic");
        assertThat(result.modelName()).isEqualTo("matrixcode-local-developer");
        assertThat(result.answerSummary()).contains("开发模型建议");
        assertThat(gateway.recentRequests("demo")).singleElement().satisfies(record -> {
            assertThat(record.requestId()).isEqualTo(result.requestId());
            assertThat(record.actorUserId()).isEqualTo("worker-1");
            assertThat(record.agentRunId()).isEqualTo("run-1");
        });
        assertThat(repository.eventsForRun("run-1")).extracting(AgentRunEventRecord::eventType)
                .contains("WORKER_EXECUTION_PREPARED", "TOOL_TRACE", "WORKER_MODEL_REQUEST_COMPLETED");
    }

    @Test
    void 非认领人会返回阻塞结果且不调用模型网关() {
        var repository = new RecordingAgentRuntimeRepository();
        var runtimeService = new AgentRuntimeService(Optional.of(repository), new ObjectMapper(), java.time.Clock.systemUTC());
        var gateway = modelGateway(runtimeService);
        var service = new AgentRuntimeWorkerModelExecutionService(
                runtimeService,
                new AgentRuntimeWorkerService(runtimeService),
                gateway
        );
        queueAndClaim(runtimeService, repository);
        repository.events.clear();

        var result = service.executeModelRequest("demo", "run-1", "worker-2");

        assertThat(result.executed()).isFalse();
        assertThat(result.blockedReason()).isEqualTo("当前 Worker 不是运行认领人");
        assertThat(result.requestId()).isBlank();
        assertThat(gateway.recentRequests("demo")).isEmpty();
        assertThat(repository.eventsForRun("run-1")).isEmpty();
    }

    private void queueAndClaim(
            AgentRuntimeService runtimeService,
            RecordingAgentRuntimeRepository repository
    ) {
        runtimeService.saveRun(
                "run-1",
                "demo",
                ModelRole.DEVELOPER,
                "coding",
                "user-dev",
                "deepseek",
                "deepseek-chat",
                AgentRunStatus.QUEUED,
                "生成交接文档",
                "等待执行",
                null,
                null
        );
        runtimeService.claimNextQueuedRun("demo", "worker-1").orElseThrow();
        repository.forceLeaseExpiry("run-1", Instant.now().plusSeconds(900));
    }

    private ModelGatewayService modelGateway(AgentRuntimeService runtimeService) {
        var store = new InMemoryWorkbenchStateStore();
        var providers = new ModelProviderRegistry();
        return new ModelGatewayService(
                providers,
                new RoleModelBindingService(providers),
                new PromptContractBuilder(),
                new PromptCacheEstimator(),
                new UsageCalculator(),
                new ContextEngine(),
                List.of(new DeterministicModelAdapter(new LocalProductDraftAgent())),
                new ProjectEventBus(),
                new RoleAgentConfigService(store),
                store,
                Optional.of(runtimeService)
        );
    }

    private static final class RecordingAgentRuntimeRepository implements AgentRuntimeRepository {

        private final List<AgentRunRecord> savedRuns = new ArrayList<>();
        private final List<AgentRunEventRecord> events = new ArrayList<>();

        @Override
        public void saveRun(AgentRunRecord run) {
            savedRuns.removeIf(existing -> existing.id().equals(run.id()));
            savedRuns.add(0, run);
        }

        @Override
        public void appendEvent(AgentRunEventRecord event) {
            events.add(event);
        }

        @Override
        public Optional<AgentRunRecord> findRun(String runId) {
            return savedRuns.stream()
                    .filter(run -> run.id().equals(runId))
                    .findFirst();
        }

        @Override
        public Optional<AgentRunRecord> claimNextQueuedRun(
                String projectId,
                String claimedByUserId,
                Instant claimedAt,
                Instant claimExpiresAt
        ) {
            var queuedRun = savedRuns.stream()
                    .filter(run -> run.projectId().equals(projectId))
                    .filter(run -> run.status() == AgentRunStatus.QUEUED)
                    .reduce((older, newer) -> newer);
            if (queuedRun.isEmpty()) {
                return Optional.empty();
            }
            var run = queuedRun.get();
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
            saveRun(claimed);
            return Optional.of(claimed);
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
            for (var run : List.copyOf(savedRuns)) {
                if (!seenRunIds.add(run.id())) {
                    continue;
                }
                if (expired.size() >= limit) {
                    break;
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
                saveRun(failed);
                expired.add(failed);
            }
            return expired;
        }

        void forceLeaseExpiry(String runId, Instant claimExpiresAt) {
            findRun(runId).ifPresent(run -> saveRun(new AgentRunRecord(
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
