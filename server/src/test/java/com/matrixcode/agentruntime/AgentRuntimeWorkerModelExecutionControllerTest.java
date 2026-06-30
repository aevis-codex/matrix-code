package com.matrixcode.agentruntime;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.matrixcode.agent.application.LocalProductDraftAgent;
import com.matrixcode.agentruntime.api.AgentRuntimeWorkerModelExecutionController;
import com.matrixcode.agentruntime.application.AgentRuntimeRepository;
import com.matrixcode.agentruntime.application.AgentRuntimeService;
import com.matrixcode.agentruntime.application.AgentRuntimeWorkerModelExecutionService;
import com.matrixcode.agentruntime.application.AgentRuntimeWorkerService;
import com.matrixcode.agentruntime.domain.AgentRunEventRecord;
import com.matrixcode.agentruntime.domain.AgentRunRecord;
import com.matrixcode.agentruntime.domain.AgentRunStatus;
import com.matrixcode.common.api.RestApiExceptionHandler;
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
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static com.matrixcode.identity.api.RequestActorResolver.CURRENT_USER_HEADER;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class AgentRuntimeWorkerModelExecutionControllerTest {

    @Test
    void 可以通过HTTP触发Worker受控模型请求() throws Exception {
        var repository = new RecordingAgentRuntimeRepository();
        var runtimeService = new AgentRuntimeService(Optional.of(repository), new ObjectMapper(), java.time.Clock.systemUTC());
        var gateway = modelGateway(runtimeService);
        var service = new AgentRuntimeWorkerModelExecutionService(
                runtimeService,
                new AgentRuntimeWorkerService(runtimeService),
                gateway
        );
        queueAndClaim(runtimeService, repository);
        var mockMvc = MockMvcBuilders.standaloneSetup(new AgentRuntimeWorkerModelExecutionController(service))
                .setControllerAdvice(new RestApiExceptionHandler())
                .build();

        mockMvc.perform(post("/api/projects/demo/agent-runs/run-1/worker-model-request")
                        .header(CURRENT_USER_HEADER, "worker-1")
                        .param("workerId", "worker-1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.projectId").value("demo"))
                .andExpect(jsonPath("$.runId").value("run-1"))
                .andExpect(jsonPath("$.workerId").value("worker-1"))
                .andExpect(jsonPath("$.executed").value(true))
                .andExpect(jsonPath("$.requestId").isNotEmpty())
                .andExpect(jsonPath("$.providerId").value("local-deterministic"))
                .andExpect(jsonPath("$.modelName").value("matrixcode-local-developer"))
                .andExpect(jsonPath("$.answerSummary").isNotEmpty());

        assertThat(repository.eventsForRun("run-1")).extracting(AgentRunEventRecord::eventType)
                .contains("WORKER_MODEL_REQUEST_COMPLETED");
    }

    @Test
    void Worker模型请求身份和Worker不一致时返回403() throws Exception {
        var repository = new RecordingAgentRuntimeRepository();
        var runtimeService = new AgentRuntimeService(Optional.of(repository), new ObjectMapper(), java.time.Clock.systemUTC());
        var gateway = modelGateway(runtimeService);
        var service = new AgentRuntimeWorkerModelExecutionService(
                runtimeService,
                new AgentRuntimeWorkerService(runtimeService),
                gateway
        );
        queueAndClaim(runtimeService, repository);
        var mockMvc = MockMvcBuilders.standaloneSetup(new AgentRuntimeWorkerModelExecutionController(service))
                .setControllerAdvice(new RestApiExceptionHandler())
                .build();

        mockMvc.perform(post("/api/projects/demo/agent-runs/run-1/worker-model-request")
                        .header(CURRENT_USER_HEADER, "worker-2")
                        .param("workerId", "worker-1"))
                .andExpect(status().isForbidden());
    }

    @Test
    void Worker模型请求操作人为空时返回400() throws Exception {
        var repository = new RecordingAgentRuntimeRepository();
        var runtimeService = new AgentRuntimeService(Optional.of(repository), new ObjectMapper(), java.time.Clock.systemUTC());
        var gateway = modelGateway(runtimeService);
        var service = new AgentRuntimeWorkerModelExecutionService(
                runtimeService,
                new AgentRuntimeWorkerService(runtimeService),
                gateway
        );
        queueAndClaim(runtimeService, repository);
        var mockMvc = MockMvcBuilders.standaloneSetup(new AgentRuntimeWorkerModelExecutionController(service))
                .setControllerAdvice(new RestApiExceptionHandler())
                .build();

        mockMvc.perform(post("/api/projects/demo/agent-runs/run-1/worker-model-request")
                        .header(CURRENT_USER_HEADER, "worker-1")
                        .param("workerId", " "))
                .andExpect(status().isBadRequest());
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
