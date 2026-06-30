package com.matrixcode.agentruntime;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.matrixcode.agent.application.LocalProductDraftAgent;
import com.matrixcode.agentruntime.application.AgentRuntimeRepository;
import com.matrixcode.agentruntime.application.AgentRuntimeService;
import com.matrixcode.agentruntime.application.AgentRuntimeWorkerModelExecutionService;
import com.matrixcode.agentruntime.application.AgentRuntimeWorkerSchedulerProperties;
import com.matrixcode.agentruntime.application.AgentRuntimeWorkerSchedulerService;
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

class AgentRuntimeWorkerSchedulerServiceTest {

    @Test
    void 调度关闭时不认领队列也不调用模型() {
        var fixture = fixture(false, false);
        fixture.queueRun("run-1");

        var result = fixture.scheduler.runOnce();

        assertThat(result.enabled()).isFalse();
        assertThat(result.ticked()).isFalse();
        assertThat(result.expiredRunCount()).isZero();
        assertThat(result.claimedRunId()).isBlank();
        assertThat(result.modelExecuted()).isFalse();
        assertThat(fixture.repository.eventsForRun("run-1")).isEmpty();
        assertThat(fixture.gateway.recentRequests("demo")).isEmpty();
    }

    @Test
    void 调度开启后会认领下一条队列运行但默认不自动执行模型() {
        var fixture = fixture(true, false);
        fixture.queueRun("run-1");

        var result = fixture.scheduler.runOnce();

        assertThat(result.enabled()).isTrue();
        assertThat(result.ticked()).isTrue();
        assertThat(result.claimedRunId()).isEqualTo("run-1");
        assertThat(result.modelExecuted()).isFalse();
        assertThat(fixture.repository.findRun("run-1")).hasValueSatisfying(run -> {
            assertThat(run.status()).isEqualTo(AgentRunStatus.RUNNING);
            assertThat(run.claimedByUserId()).isEqualTo("matrixcode-worker");
        });
        assertThat(fixture.repository.eventsForRun("run-1"))
                .extracting(AgentRunEventRecord::eventType)
                .containsExactly("RUN_CLAIMED", "RUN_STARTED", "RUN_LEASED");
        assertThat(fixture.gateway.recentRequests("demo")).isEmpty();
    }

    @Test
    void 开启模型步骤后调度会认领并执行受控模型请求() {
        var fixture = fixture(true, true);
        fixture.queueRun("run-1");

        var result = fixture.scheduler.runOnce();

        assertThat(result.ticked()).isTrue();
        assertThat(result.claimedRunId()).isEqualTo("run-1");
        assertThat(result.modelExecuted()).isTrue();
        assertThat(result.modelRequestId()).isNotBlank();
        assertThat(fixture.gateway.recentRequests("demo")).singleElement().satisfies(record -> {
            assertThat(record.agentRunId()).isEqualTo("run-1");
            assertThat(record.actorUserId()).isEqualTo("matrixcode-worker");
        });
        assertThat(fixture.repository.eventsForRun("run-1"))
                .extracting(AgentRunEventRecord::eventType)
                .contains("WORKER_EXECUTION_PREPARED", "TOOL_TRACE", "WORKER_MODEL_REQUEST_COMPLETED");
    }

    private Fixture fixture(boolean enabled, boolean executeModelRequest) {
        var repository = new RecordingAgentRuntimeRepository();
        var runtimeService = new AgentRuntimeService(Optional.of(repository), new ObjectMapper(), java.time.Clock.systemUTC());
        var workerService = new AgentRuntimeWorkerService(runtimeService);
        var gateway = modelGateway(runtimeService);
        var properties = new AgentRuntimeWorkerSchedulerProperties();
        properties.setEnabled(enabled);
        properties.setExecuteModelRequest(executeModelRequest);
        properties.setProjectId("demo");
        properties.setWorkerId("matrixcode-worker");
        var modelExecutionService = new AgentRuntimeWorkerModelExecutionService(runtimeService, workerService, gateway);
        var scheduler = new AgentRuntimeWorkerSchedulerService(properties, workerService, modelExecutionService);
        return new Fixture(repository, runtimeService, gateway, scheduler);
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

    private record Fixture(
            RecordingAgentRuntimeRepository repository,
            AgentRuntimeService runtimeService,
            ModelGatewayService gateway,
            AgentRuntimeWorkerSchedulerService scheduler
    ) {
        void queueRun(String runId) {
            runtimeService.saveRun(
                    runId,
                    "demo",
                    ModelRole.DEVELOPER,
                    "coding",
                    "user-dev",
                    "deepseek",
                    "deepseek-chat",
                    AgentRunStatus.QUEUED,
                    "生成交接文档",
                    "等待自动调度",
                    null,
                    null
            );
        }
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
            return savedRuns.stream()
                    .filter(run -> run.projectId().equals(projectId))
                    .filter(run -> run.status() == AgentRunStatus.QUEUED)
                    .reduce((older, newer) -> newer)
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
                        saveRun(claimed);
                        return claimed;
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
            for (var run : List.copyOf(savedRuns)) {
                if (!seenRunIds.add(run.id()) || expired.size() >= limit) {
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
                saveRun(failed);
                expired.add(failed);
            }
            return expired;
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
