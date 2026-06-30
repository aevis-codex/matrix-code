package com.matrixcode.agentruntime;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.matrixcode.agentruntime.api.AgentRuntimeController;
import com.matrixcode.agentruntime.application.AgentRuntimeRepository;
import com.matrixcode.agentruntime.application.AgentRuntimeService;
import com.matrixcode.agentruntime.application.AgentRuntimeWorkerService;
import com.matrixcode.agentruntime.domain.AgentRunEventRecord;
import com.matrixcode.agentruntime.domain.AgentRunRecord;
import com.matrixcode.agentruntime.domain.AgentRunStatus;
import com.matrixcode.common.api.RestApiExceptionHandler;
import com.matrixcode.identity.api.ProjectRequestPermissionGuard;
import com.matrixcode.identity.api.RequestActorResolver;
import com.matrixcode.identity.application.ProjectIdentityRepository;
import com.matrixcode.identity.application.ProjectIdentityService;
import com.matrixcode.identity.application.ProjectMemberPermissionGuard;
import com.matrixcode.identity.domain.MatrixUser;
import com.matrixcode.identity.domain.ProjectMember;
import com.matrixcode.identity.domain.UserAuditRecord;
import com.matrixcode.modelgateway.domain.ModelRole;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static com.matrixcode.identity.api.RequestActorResolver.CURRENT_USER_HEADER;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class AgentRuntimeControllerTest {

    private final Clock fixedClock = Clock.fixed(Instant.parse("2026-06-25T12:00:00Z"), ZoneOffset.UTC);

    @Test
    void 可以查询恢复计划并创建恢复排队运行() throws Exception {
        var fixture = fixture();
        var repository = fixture.runtimeRepository();
        var service = fixture.service();
        var mockMvc = fixture.mockMvc();
        fixture.identityRepository().ensureMember(member("demo", "user-dev", "DEVELOPER"));
        service.markFailed(
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

        mockMvc.perform(get("/api/projects/demo/agent-runs/run-failed/recovery-plan")
                        .header(CURRENT_USER_HEADER, "user-dev"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.sourceRunId").value("run-failed"))
                .andExpect(jsonPath("$.canRetry").value(true))
                .andExpect(jsonPath("$.blockedReason").value(""));

        mockMvc.perform(post("/api/projects/demo/agent-runs/run-failed/retry")
                        .header(CURRENT_USER_HEADER, "user-reviewer")
                        .param("actorUserId", "user-reviewer"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.projectId").value("demo"))
                .andExpect(jsonPath("$.status").value("QUEUED"))
                .andExpect(jsonPath("$.retryOfRunId").value("run-failed"))
                .andExpect(jsonPath("$.actorUserId").value("user-reviewer"));

        assertThat(repository.events).extracting(AgentRunEventRecord::eventType)
                .contains("RUN_RETRY_REQUESTED", "RUN_RETRY_QUEUED");
    }

    @Test
    void 运行列表缺少请求身份时返回401() throws Exception {
        var fixture = fixture();

        fixture.mockMvc().perform(get("/api/projects/demo/agent-runs"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void 非项目成员不能读取运行列表() throws Exception {
        var fixture = fixture();
        fixture.identityRepository().ensureMember(member("demo", "user-dev", "DEVELOPER"));

        fixture.mockMvc().perform(get("/api/projects/demo/agent-runs")
                        .header(CURRENT_USER_HEADER, "user-outsider"))
                .andExpect(status().isForbidden());
    }

    @Test
    void 项目成员可以读取运行列表() throws Exception {
        var fixture = fixture();
        fixture.identityRepository().ensureMember(member("demo", "user-dev", "DEVELOPER"));
        fixture.service().markRunning(
                "run-1",
                "demo",
                ModelRole.DEVELOPER,
                "coding",
                "user-dev",
                "deepseek",
                "deepseek-chat",
                "生成交接文档",
                "执行中"
        );

        fixture.mockMvc().perform(get("/api/projects/demo/agent-runs")
                        .header(CURRENT_USER_HEADER, "user-dev"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].id").value("run-1"));
    }

    @Test
    void 运行事件缺少请求身份时返回401() throws Exception {
        var fixture = fixture();

        fixture.mockMvc().perform(get("/api/projects/demo/agent-runs/run-1/events"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void 非项目成员不能读取运行事件() throws Exception {
        var fixture = fixture();
        fixture.identityRepository().ensureMember(member("demo", "user-dev", "DEVELOPER"));

        fixture.mockMvc().perform(get("/api/projects/demo/agent-runs/run-1/events")
                        .header(CURRENT_USER_HEADER, "user-outsider"))
                .andExpect(status().isForbidden());
    }

    @Test
    void 恢复计划缺少请求身份时返回401() throws Exception {
        var fixture = fixture();

        fixture.mockMvc().perform(get("/api/projects/demo/agent-runs/run-failed/recovery-plan"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void 非项目成员不能读取恢复计划() throws Exception {
        var fixture = fixture();
        fixture.identityRepository().ensureMember(member("demo", "user-dev", "DEVELOPER"));

        fixture.mockMvc().perform(get("/api/projects/demo/agent-runs/run-failed/recovery-plan")
                        .header(CURRENT_USER_HEADER, "user-outsider"))
                .andExpect(status().isForbidden());
    }

    @Test
    void 重试运行缺少请求身份时返回401() throws Exception {
        var repository = new RecordingAgentRuntimeRepository();
        var service = new AgentRuntimeService(Optional.of(repository), new ObjectMapper(), fixedClock);
        var mockMvc = MockMvcBuilders.standaloneSetup(new AgentRuntimeController(service))
                .setControllerAdvice(new RestApiExceptionHandler())
                .build();
        service.markFailed(
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

        mockMvc.perform(post("/api/projects/demo/agent-runs/run-failed/retry")
                        .param("actorUserId", "user-reviewer"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void 重试运行请求身份和操作人不一致时返回403() throws Exception {
        var repository = new RecordingAgentRuntimeRepository();
        var service = new AgentRuntimeService(Optional.of(repository), new ObjectMapper(), fixedClock);
        var mockMvc = MockMvcBuilders.standaloneSetup(new AgentRuntimeController(service))
                .setControllerAdvice(new RestApiExceptionHandler())
                .build();
        service.markFailed(
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

        mockMvc.perform(post("/api/projects/demo/agent-runs/run-failed/retry")
                        .header(CURRENT_USER_HEADER, "user-other")
                        .param("actorUserId", "user-reviewer"))
                .andExpect(status().isForbidden());
    }

    @Test
    void 重试运行操作人为空时返回400() throws Exception {
        var repository = new RecordingAgentRuntimeRepository();
        var service = new AgentRuntimeService(Optional.of(repository), new ObjectMapper(), fixedClock);
        var mockMvc = MockMvcBuilders.standaloneSetup(new AgentRuntimeController(service))
                .setControllerAdvice(new RestApiExceptionHandler())
                .build();
        service.markFailed(
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

        mockMvc.perform(post("/api/projects/demo/agent-runs/run-failed/retry")
                        .header(CURRENT_USER_HEADER, "user-reviewer")
                        .param("actorUserId", " "))
                .andExpect(status().isBadRequest());
    }

    @Test
    void 不可重试失败运行在重试接口返回409() throws Exception {
        var repository = new RecordingAgentRuntimeRepository();
        var service = new AgentRuntimeService(Optional.of(repository), new ObjectMapper(), fixedClock);
        var mockMvc = MockMvcBuilders.standaloneSetup(new AgentRuntimeController(service))
                .setControllerAdvice(new RestApiExceptionHandler())
                .build();
        service.markFailed(
                "run-failed",
                "demo",
                ModelRole.DEVELOPER,
                "coding",
                "user-dev",
                "deepseek",
                "deepseek-chat",
                "生成交接文档",
                "需求冻结后不允许恢复",
                false,
                null
        );

        mockMvc.perform(post("/api/projects/demo/agent-runs/run-failed/retry")
                        .header(CURRENT_USER_HEADER, "user-reviewer")
                        .param("actorUserId", "user-reviewer"))
                .andExpect(status().isConflict());
    }

    @Test
    void 排队运行可以通过认领接口进入运行中() throws Exception {
        var repository = new RecordingAgentRuntimeRepository();
        var service = new AgentRuntimeService(Optional.of(repository), new ObjectMapper(), fixedClock);
        var mockMvc = MockMvcBuilders.standaloneSetup(new AgentRuntimeController(service))
                .setControllerAdvice(new RestApiExceptionHandler())
                .build();
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

        mockMvc.perform(post("/api/projects/demo/agent-runs/" + retryRun.id() + "/claim")
                        .header(CURRENT_USER_HEADER, "worker-1")
                        .param("actorUserId", "worker-1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.projectId").value("demo"))
                .andExpect(jsonPath("$.status").value("RUNNING"))
                .andExpect(jsonPath("$.retryOfRunId").value(failedRun.id()));

        assertThat(repository.events).extracting(AgentRunEventRecord::eventType)
                .contains("RUN_CLAIMED", "RUN_STARTED");
    }

    @Test
    void 可以通过项目队列认领下一条排队运行() throws Exception {
        var repository = new RecordingAgentRuntimeRepository();
        var service = new AgentRuntimeService(Optional.of(repository), new ObjectMapper(), fixedClock);
        var mockMvc = MockMvcBuilders.standaloneSetup(new AgentRuntimeController(service))
                .setControllerAdvice(new RestApiExceptionHandler())
                .build();
        service.saveRun(
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
        service.saveRun(
                "run-2",
                "demo",
                ModelRole.TESTER,
                "testing",
                "user-tester",
                "qwen",
                "qwen-plus",
                AgentRunStatus.QUEUED,
                "补充回归用例",
                "等待执行",
                null,
                null
        );

        mockMvc.perform(post("/api/projects/demo/agent-runs/claim-next")
                        .header(CURRENT_USER_HEADER, "worker-1")
                        .param("actorUserId", "worker-1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value("run-1"))
                .andExpect(jsonPath("$.status").value("RUNNING"))
                .andExpect(jsonPath("$.claimedByUserId").value("worker-1"));

        assertThat(repository.events).extracting(AgentRunEventRecord::eventType)
                .contains("RUN_CLAIMED", "RUN_STARTED", "RUN_LEASED");
    }

    @Test
    void 项目队列为空时认领下一条返回204() throws Exception {
        var repository = new RecordingAgentRuntimeRepository();
        var service = new AgentRuntimeService(Optional.of(repository), new ObjectMapper(), fixedClock);
        var mockMvc = MockMvcBuilders.standaloneSetup(new AgentRuntimeController(service))
                .setControllerAdvice(new RestApiExceptionHandler())
                .build();

        mockMvc.perform(post("/api/projects/demo/agent-runs/claim-next")
                        .header(CURRENT_USER_HEADER, "worker-1")
                        .param("actorUserId", "worker-1"))
                .andExpect(status().isNoContent());

        assertThat(repository.events).isEmpty();
    }

    @Test
    void 运行租约可以通过续期接口刷新() throws Exception {
        var repository = new RecordingAgentRuntimeRepository();
        var service = new AgentRuntimeService(Optional.of(repository), new ObjectMapper(), fixedClock);
        var mockMvc = MockMvcBuilders.standaloneSetup(new AgentRuntimeController(service))
                .setControllerAdvice(new RestApiExceptionHandler())
                .build();
        service.saveRun(
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
        service.claimNextQueuedRun("demo", "worker-1").orElseThrow();

        mockMvc.perform(post("/api/projects/demo/agent-runs/run-1/renew-lease")
                        .header(CURRENT_USER_HEADER, "worker-1")
                        .param("actorUserId", "worker-1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value("run-1"))
                .andExpect(jsonPath("$.status").value("RUNNING"))
                .andExpect(jsonPath("$.claimedByUserId").value("worker-1"));

        assertThat(repository.events).extracting(AgentRunEventRecord::eventType)
                .contains("RUN_LEASE_RENEWED");
    }

    @Test
    void 非认领人续期接口返回204() throws Exception {
        var repository = new RecordingAgentRuntimeRepository();
        var service = new AgentRuntimeService(Optional.of(repository), new ObjectMapper(), fixedClock);
        var mockMvc = MockMvcBuilders.standaloneSetup(new AgentRuntimeController(service))
                .setControllerAdvice(new RestApiExceptionHandler())
                .build();
        service.saveRun(
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
        service.claimNextQueuedRun("demo", "worker-1").orElseThrow();
        repository.events.clear();

        mockMvc.perform(post("/api/projects/demo/agent-runs/run-1/renew-lease")
                        .header(CURRENT_USER_HEADER, "worker-2")
                        .param("actorUserId", "worker-2"))
                .andExpect(status().isNoContent());

        assertThat(repository.events).isEmpty();
    }

    @Test
    void WorkerTick会先回收过期租约再认领下一条排队运行() throws Exception {
        var repository = new RecordingAgentRuntimeRepository();
        var service = new AgentRuntimeService(Optional.of(repository), new ObjectMapper(), fixedClock);
        var mockMvc = MockMvcBuilders.standaloneSetup(new AgentRuntimeController(service))
                .setControllerAdvice(new RestApiExceptionHandler())
                .build();
        service.saveRun(
                "run-expired",
                "demo",
                ModelRole.DEVELOPER,
                "coding",
                "user-dev",
                "deepseek",
                "deepseek-chat",
                AgentRunStatus.QUEUED,
                "已过期任务",
                "等待执行",
                null,
                null
        );
        var expiredClaim = service.claimNextQueuedRun("demo", "worker-old").orElseThrow();
        repository.forceLeaseExpiry(expiredClaim.id(), fixedClock.instant().minusSeconds(1));
        service.saveRun(
                "run-next",
                "demo",
                ModelRole.TESTER,
                "testing",
                "user-tester",
                "qwen",
                "qwen-plus",
                AgentRunStatus.QUEUED,
                "下一条任务",
                "等待执行",
                null,
                null
        );

        mockMvc.perform(post("/api/projects/demo/agent-runs/worker-tick")
                        .header(CURRENT_USER_HEADER, "worker-1")
                        .param("workerId", "worker-1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.projectId").value("demo"))
                .andExpect(jsonPath("$.workerId").value("worker-1"))
                .andExpect(jsonPath("$.expiredRunCount").value(1))
                .andExpect(jsonPath("$.claimedRun.id").value("run-next"))
                .andExpect(jsonPath("$.claimedRun.status").value("RUNNING"));

        assertThat(repository.events).extracting(AgentRunEventRecord::eventType)
                .contains("RUN_LEASE_EXPIRED", "RUN_FAILED", "RUN_CLAIMED", "RUN_STARTED", "RUN_LEASED");
    }

    @Test
    void WorkerTick操作人为空时返回400() throws Exception {
        var repository = new RecordingAgentRuntimeRepository();
        var service = new AgentRuntimeService(Optional.of(repository), new ObjectMapper(), fixedClock);
        var mockMvc = MockMvcBuilders.standaloneSetup(new AgentRuntimeController(service))
                .setControllerAdvice(new RestApiExceptionHandler())
                .build();

        mockMvc.perform(post("/api/projects/demo/agent-runs/worker-tick")
                        .header(CURRENT_USER_HEADER, "worker-1")
                        .param("workerId", " "))
                .andExpect(status().isBadRequest());
    }

    @Test
    void 当前认领人可以生成Worker执行计划并写入审计事件() throws Exception {
        var repository = new RecordingAgentRuntimeRepository();
        var service = new AgentRuntimeService(Optional.of(repository), new ObjectMapper(), fixedClock);
        var mockMvc = MockMvcBuilders.standaloneSetup(new AgentRuntimeController(service))
                .setControllerAdvice(new RestApiExceptionHandler())
                .build();
        service.saveRun(
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
        service.claimNextQueuedRun("demo", "worker-1").orElseThrow();
        repository.forceLeaseExpiry("run-1", Instant.now().plusSeconds(900));
        repository.events.clear();

        mockMvc.perform(post("/api/projects/demo/agent-runs/run-1/worker-execution-plan")
                        .header(CURRENT_USER_HEADER, "worker-1")
                        .param("workerId", "worker-1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.projectId").value("demo"))
                .andExpect(jsonPath("$.runId").value("run-1"))
                .andExpect(jsonPath("$.workerId").value("worker-1"))
                .andExpect(jsonPath("$.executable").value(true))
                .andExpect(jsonPath("$.blockedReason").value(""))
                .andExpect(jsonPath("$.steps.length()").value(7))
                .andExpect(jsonPath("$.steps[0].stepKey").value("CONTEXT_RECALL"))
                .andExpect(jsonPath("$.steps[3].requiresApproval").value(true));

        assertThat(repository.events).extracting(AgentRunEventRecord::eventType)
                .containsExactly("WORKER_EXECUTION_PREPARED");
    }

    @Test
    void 非认领人生成Worker执行计划会返回阻塞结果且不写审计事件() throws Exception {
        var repository = new RecordingAgentRuntimeRepository();
        var service = new AgentRuntimeService(Optional.of(repository), new ObjectMapper(), fixedClock);
        var mockMvc = MockMvcBuilders.standaloneSetup(new AgentRuntimeController(service))
                .setControllerAdvice(new RestApiExceptionHandler())
                .build();
        service.saveRun(
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
        service.claimNextQueuedRun("demo", "worker-1").orElseThrow();
        repository.forceLeaseExpiry("run-1", Instant.now().plusSeconds(900));
        repository.events.clear();

        mockMvc.perform(post("/api/projects/demo/agent-runs/run-1/worker-execution-plan")
                        .header(CURRENT_USER_HEADER, "worker-2")
                        .param("workerId", "worker-2"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.executable").value(false))
                .andExpect(jsonPath("$.blockedReason").value("当前 Worker 不是运行认领人"))
                .andExpect(jsonPath("$.steps.length()").value(0));

        assertThat(repository.events).isEmpty();
    }

    @Test
    void 过期租约生成Worker执行计划会返回阻塞结果且不写审计事件() throws Exception {
        var repository = new RecordingAgentRuntimeRepository();
        var service = new AgentRuntimeService(Optional.of(repository), new ObjectMapper(), fixedClock);
        var mockMvc = MockMvcBuilders.standaloneSetup(new AgentRuntimeController(service))
                .setControllerAdvice(new RestApiExceptionHandler())
                .build();
        service.saveRun(
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
        service.claimNextQueuedRun("demo", "worker-1").orElseThrow();
        repository.forceLeaseExpiry("run-1", Instant.now().minusSeconds(1));
        repository.events.clear();

        mockMvc.perform(post("/api/projects/demo/agent-runs/run-1/worker-execution-plan")
                        .header(CURRENT_USER_HEADER, "worker-1")
                        .param("workerId", "worker-1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.executable").value(false))
                .andExpect(jsonPath("$.blockedReason").value("运行租约已过期"))
                .andExpect(jsonPath("$.steps.length()").value(0));

        assertThat(repository.events).isEmpty();
    }

    @Test
    void 非排队运行在认领接口返回409() throws Exception {
        var repository = new RecordingAgentRuntimeRepository();
        var service = new AgentRuntimeService(Optional.of(repository), new ObjectMapper(), fixedClock);
        var mockMvc = MockMvcBuilders.standaloneSetup(new AgentRuntimeController(service))
                .setControllerAdvice(new RestApiExceptionHandler())
                .build();
        service.markRunning(
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

        mockMvc.perform(post("/api/projects/demo/agent-runs/run-running/claim")
                        .header(CURRENT_USER_HEADER, "worker-1")
                        .param("actorUserId", "worker-1"))
                .andExpect(status().isConflict());
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
                        saveRun(renewed);
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

    private ControllerFixture fixture() {
        var runtimeRepository = new RecordingAgentRuntimeRepository();
        var service = new AgentRuntimeService(Optional.of(runtimeRepository), new ObjectMapper(), fixedClock);
        var identityRepository = new FakeProjectIdentityRepository();
        var identityService = new ProjectIdentityService(identityRepository, fixedClock);
        var requestPermissionGuard = new ProjectRequestPermissionGuard(
                new RequestActorResolver(),
                new ProjectMemberPermissionGuard(identityService)
        );
        var mockMvc = MockMvcBuilders.standaloneSetup(new AgentRuntimeController(
                        service,
                        new AgentRuntimeWorkerService(service),
                        requestPermissionGuard
                ))
                .setControllerAdvice(new RestApiExceptionHandler())
                .build();
        return new ControllerFixture(runtimeRepository, service, identityRepository, mockMvc);
    }

    private ProjectMember member(String projectId, String userId, String roleKey) {
        return new ProjectMember(
                projectId + ":" + userId + ":" + roleKey,
                projectId,
                userId,
                roleKey,
                "ACTIVE",
                fixedClock.instant(),
                fixedClock.instant(),
                fixedClock.instant()
        );
    }

    private record ControllerFixture(
            RecordingAgentRuntimeRepository runtimeRepository,
            AgentRuntimeService service,
            FakeProjectIdentityRepository identityRepository,
            MockMvc mockMvc
    ) {
    }

    private static final class FakeProjectIdentityRepository implements ProjectIdentityRepository {
        private final List<ProjectMember> members = new ArrayList<>();

        @Override
        public void ensureUser(MatrixUser user) {
        }

        @Override
        public void ensureProject(String projectId, String name, String ownerUserId, String currentStage) {
        }

        @Override
        public void ensureMember(ProjectMember member) {
            members.add(member);
        }

        @Override
        public List<ProjectMember> members(String projectId) {
            return members.stream()
                    .filter(member -> member.projectId().equals(projectId))
                    .toList();
        }

        @Override
        public List<String> projectsForUser(String userId) {
            return List.of();
        }

        @Override
        public List<UserAuditRecord> auditRecords(String projectId, String userId) {
            return List.of();
        }
    }
}
