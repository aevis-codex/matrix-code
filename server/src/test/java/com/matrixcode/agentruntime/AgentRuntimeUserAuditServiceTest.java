package com.matrixcode.agentruntime;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.matrixcode.agent.application.LocalProductDraftAgent;
import com.matrixcode.agentruntime.application.AgentRuntimeRepository;
import com.matrixcode.agentruntime.application.AgentRuntimeService;
import com.matrixcode.agentruntime.application.AgentRuntimeUserAuditService;
import com.matrixcode.agentruntime.domain.AgentRunEventRecord;
import com.matrixcode.agentruntime.domain.AgentRunRecord;
import com.matrixcode.agentruntime.domain.AgentRunStatus;
import com.matrixcode.context.application.ContextEngine;
import com.matrixcode.identity.application.ProjectIdentityRepository;
import com.matrixcode.identity.application.ProjectIdentityService;
import com.matrixcode.identity.domain.MatrixUser;
import com.matrixcode.identity.domain.ProjectMember;
import com.matrixcode.identity.domain.UserAuditRecord;
import com.matrixcode.modelgateway.application.DeterministicModelAdapter;
import com.matrixcode.modelgateway.application.ModelGatewayService;
import com.matrixcode.modelgateway.application.ModelProviderRegistry;
import com.matrixcode.modelgateway.application.PromptCacheEstimator;
import com.matrixcode.modelgateway.application.PromptContractBuilder;
import com.matrixcode.modelgateway.application.RoleModelBindingService;
import com.matrixcode.modelgateway.domain.ModelRequestCommand;
import com.matrixcode.modelgateway.domain.ModelRole;
import com.matrixcode.realtime.application.ProjectEventBus;
import com.matrixcode.roleagent.application.RoleAgentConfigService;
import com.matrixcode.usage.application.UsageCalculator;
import com.matrixcode.workbench.application.InMemoryWorkbenchStateStore;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class AgentRuntimeUserAuditServiceTest {

    @Test
    void 当前认领Worker可以看到自己的运行责任和模型请求摘要() {
        var repository = new RecordingAgentRuntimeRepository();
        var runtimeService = new AgentRuntimeService(Optional.of(repository), new ObjectMapper(), java.time.Clock.systemUTC());
        var gateway = modelGateway(runtimeService);
        var service = new AgentRuntimeUserAuditService(runtimeService, gateway, new ProjectIdentityService(null, java.time.Clock.systemUTC()));
        var run = runningRun("run-1", "user-dev", "worker-1", AgentRunStatus.RUNNING);
        repository.saveRun(run);
        repository.appendEvent(event("event-1", "run-1", "TOOL_TRACE", "模型请求 trace"));
        repository.appendEvent(event("event-2", "run-1", "WORKER_MODEL_REQUEST_COMPLETED", "Worker 模型请求已完成"));
        gateway.request(new ModelRequestCommand("demo", ModelRole.DEVELOPER, "worker-1", "run-1", "给出阶段性建议", List.of()));

        var report = service.audit("demo", "worker-1", 20);

        assertThat(report.projectId()).isEqualTo("demo");
        assertThat(report.userId()).isEqualTo("worker-1");
        assertThat(report.totalRuns()).isEqualTo(1);
        assertThat(report.activeResponsibilities()).isEqualTo(1);
        assertThat(report.modelRequestCount()).isEqualTo(1);
        assertThat(report.entries()).singleElement().satisfies(entry -> {
            assertThat(entry.runId()).isEqualTo("run-1");
            assertThat(entry.responsibleUserId()).isEqualTo("worker-1");
            assertThat(entry.responsibilitySource()).isEqualTo("CLAIMED_WORKER");
            assertThat(entry.actorUserId()).isEqualTo("user-dev");
            assertThat(entry.claimedByUserId()).isEqualTo("worker-1");
            assertThat(entry.eventCount()).isEqualTo(3);
            assertThat(entry.toolTraceCount()).isEqualTo(2);
            assertThat(entry.modelRequestCount()).isEqualTo(1);
            assertThat(entry.lastModelRequestId()).isNotBlank();
            assertThat(entry.lastEventType()).isEqualTo("TOOL_TRACE");
        });
    }

    @Test
    void 用户无运行责任且无模型请求时返回空报告() {
        var repository = new RecordingAgentRuntimeRepository();
        var runtimeService = new AgentRuntimeService(Optional.of(repository), new ObjectMapper(), java.time.Clock.systemUTC());
        var gateway = modelGateway(runtimeService);
        var service = new AgentRuntimeUserAuditService(runtimeService, gateway, new ProjectIdentityService(null, java.time.Clock.systemUTC()));
        repository.saveRun(runningRun("run-1", "user-dev", "worker-1", AgentRunStatus.RUNNING));

        var report = service.audit("demo", "user-unrelated", 20);

        assertThat(report.totalRuns()).isZero();
        assertThat(report.activeResponsibilities()).isZero();
        assertThat(report.modelRequestCount()).isZero();
        assertThat(report.entries()).isEmpty();
    }

    @Test
    void 模型请求操作者可以看到关联运行但不接管责任人() {
        var repository = new RecordingAgentRuntimeRepository();
        var runtimeService = new AgentRuntimeService(Optional.of(repository), new ObjectMapper(), java.time.Clock.systemUTC());
        var gateway = modelGateway(runtimeService);
        var service = new AgentRuntimeUserAuditService(runtimeService, gateway, new ProjectIdentityService(null, java.time.Clock.systemUTC()));
        repository.saveRun(runningRun("run-1", "user-product", "worker-1", AgentRunStatus.RUNNING));
        gateway.request(new ModelRequestCommand("demo", ModelRole.DEVELOPER, "user-dev", "run-1", "给出阶段性建议", List.of()));

        var report = service.audit("demo", "user-dev", 20);

        assertThat(report.entries()).singleElement().satisfies(entry -> {
            assertThat(entry.runId()).isEqualTo("run-1");
            assertThat(entry.responsibleUserId()).isEqualTo("worker-1");
            assertThat(entry.responsibilitySource()).isEqualTo("CLAIMED_WORKER");
            assertThat(entry.modelRequestCount()).isEqualTo(1);
        });
    }

    @Test
    void 无认领人时按项目成员角色回退责任人() {
        var repository = new RecordingAgentRuntimeRepository();
        var runtimeService = new AgentRuntimeService(Optional.of(repository), new ObjectMapper(), java.time.Clock.systemUTC());
        var gateway = modelGateway(runtimeService);
        var identityRepository = new RecordingIdentityRepository();
        var identityService = new ProjectIdentityService(identityRepository, java.time.Clock.systemUTC());
        identityService.ensureMember("demo", "user-dev", "DEVELOPER", "开发");
        var service = new AgentRuntimeUserAuditService(runtimeService, gateway, identityService);
        repository.saveRun(new AgentRunRecord(
                "run-1",
                "demo",
                "DEVELOPER",
                "coding",
                "user-product",
                "deepseek",
                "deepseek-chat",
                AgentRunStatus.QUEUED,
                "实现审计",
                "等待认领",
                "",
                false,
                "",
                Instant.parse("2026-06-27T01:00:00Z"),
                null,
                null,
                Instant.parse("2026-06-27T01:00:00Z")
        ));

        var report = service.audit("demo", "user-dev", 20);

        assertThat(report.entries()).singleElement().satisfies(entry -> {
            assertThat(entry.responsibleUserId()).isEqualTo("user-dev");
            assertThat(entry.responsibilitySource()).isEqualTo("ROLE_MEMBER");
            assertThat(entry.status()).isEqualTo("QUEUED");
        });
    }

    private AgentRunRecord runningRun(
            String runId,
            String actorUserId,
            String claimedByUserId,
            AgentRunStatus status
    ) {
        var now = Instant.parse("2026-06-27T01:00:00Z");
        return new AgentRunRecord(
                runId,
                "demo",
                "DEVELOPER",
                "coding",
                actorUserId,
                "deepseek",
                "deepseek-chat",
                status,
                "实现用户责任审计",
                "运行中",
                "",
                false,
                "",
                now,
                now,
                null,
                now,
                claimedByUserId,
                now,
                now.plusSeconds(900)
        );
    }

    private AgentRunEventRecord event(String eventId, String runId, String eventType, String title) {
        return new AgentRunEventRecord(
                eventId,
                runId,
                "demo",
                eventType,
                title,
                "{}",
                Instant.parse("2026-06-27T01:00:00Z").plusSeconds(Integer.parseInt(eventId.substring(6)))
        );
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

    private static final class RecordingIdentityRepository implements ProjectIdentityRepository {

        private final LinkedHashMap<String, ProjectMember> members = new LinkedHashMap<>();

        @Override
        public void ensureUser(MatrixUser user) {
        }

        @Override
        public void ensureProject(String projectId, String name, String ownerUserId, String currentStage) {
        }

        @Override
        public void ensureMember(ProjectMember member) {
            members.put(member.projectId() + ":" + member.userId() + ":" + member.roleKey(), member);
        }

        @Override
        public List<ProjectMember> members(String projectId) {
            return members.values().stream()
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
