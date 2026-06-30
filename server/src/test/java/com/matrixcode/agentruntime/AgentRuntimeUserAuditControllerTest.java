package com.matrixcode.agentruntime;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.matrixcode.agent.application.LocalProductDraftAgent;
import com.matrixcode.agentruntime.api.AgentRuntimeUserAuditController;
import com.matrixcode.agentruntime.application.AgentRuntimeRepository;
import com.matrixcode.agentruntime.application.AgentRuntimeService;
import com.matrixcode.agentruntime.application.AgentRuntimeUserAuditService;
import com.matrixcode.agentruntime.domain.AgentRunEventRecord;
import com.matrixcode.agentruntime.domain.AgentRunRecord;
import com.matrixcode.agentruntime.domain.AgentRunStatus;
import com.matrixcode.common.api.RestApiExceptionHandler;
import com.matrixcode.context.application.ContextEngine;
import com.matrixcode.identity.api.RequestActorResolver;
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
import com.matrixcode.realtime.application.ProjectEventBus;
import com.matrixcode.roleagent.application.RoleAgentConfigService;
import com.matrixcode.usage.application.UsageCalculator;
import com.matrixcode.workbench.application.InMemoryWorkbenchStateStore;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class AgentRuntimeUserAuditControllerTest {

    private static final String CURRENT_USER_HEADER = "X-MatrixCode-User-Id";

    @Test
    void 当前用户可以通过HTTP查询自己的责任审计报告() throws Exception {
        var fixture = fixture();

        fixture.mockMvc.perform(get("/api/projects/demo/agent-runs/user-audit")
                        .header(CURRENT_USER_HEADER, "worker-1")
                        .param("userId", "worker-1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.projectId").value("demo"))
                .andExpect(jsonPath("$.userId").value("worker-1"))
                .andExpect(jsonPath("$.totalRuns").value(1))
                .andExpect(jsonPath("$.activeResponsibilities").value(1))
                .andExpect(jsonPath("$.entries[0].runId").value("run-1"))
                .andExpect(jsonPath("$.entries[0].responsibleUserId").value("worker-1"))
                .andExpect(jsonPath("$.entries[0].responsibilitySource").value("CLAIMED_WORKER"));
    }

    @Test
    void 缺少请求身份时拒绝查询用户责任审计报告() throws Exception {
        var fixture = fixture();

        fixture.mockMvc.perform(get("/api/projects/demo/agent-runs/user-audit")
                        .param("userId", "worker-1"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void 项目负责人可以查询其他用户责任审计报告() throws Exception {
        var fixture = fixture();
        fixture.identityRepository.ensureMember(member("demo", "user-owner", "OWNER"));

        fixture.mockMvc.perform(get("/api/projects/demo/agent-runs/user-audit")
                        .header(CURRENT_USER_HEADER, "user-owner")
                        .param("userId", "worker-1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.userId").value("worker-1"))
                .andExpect(jsonPath("$.totalRuns").value(1));
    }

    @Test
    void 普通成员不能查询其他用户责任审计报告() throws Exception {
        var fixture = fixture();
        fixture.identityRepository.ensureMember(member("demo", "user-dev", "DEVELOPER"));

        fixture.mockMvc.perform(get("/api/projects/demo/agent-runs/user-audit")
                        .header(CURRENT_USER_HEADER, "user-dev")
                        .param("userId", "worker-1"))
                .andExpect(status().isForbidden());
    }

    private ControllerFixture fixture() {
        var runtimeRepository = new RecordingAgentRuntimeRepository();
        var identityRepository = new RecordingProjectIdentityRepository();
        var runtimeService = new AgentRuntimeService(Optional.of(runtimeRepository), new ObjectMapper(), java.time.Clock.systemUTC());
        var identityService = new ProjectIdentityService(identityRepository, java.time.Clock.systemUTC());
        var auditService = new AgentRuntimeUserAuditService(
                runtimeService,
                modelGateway(runtimeService),
                identityService
        );
        runtimeRepository.saveRun(new AgentRunRecord(
                "run-1",
                "demo",
                "DEVELOPER",
                "coding",
                "user-dev",
                "deepseek",
                "deepseek-chat",
                AgentRunStatus.RUNNING,
                "实现责任审计",
                "运行中",
                "",
                false,
                "",
                Instant.parse("2026-06-27T01:00:00Z"),
                Instant.parse("2026-06-27T01:00:00Z"),
                null,
                Instant.parse("2026-06-27T01:00:00Z"),
                "worker-1",
                Instant.parse("2026-06-27T01:00:00Z"),
                Instant.parse("2026-06-27T01:15:00Z")
        ));
        var mockMvc = MockMvcBuilders.standaloneSetup(
                        new AgentRuntimeUserAuditController(auditService, new RequestActorResolver(), identityService)
                )
                .setControllerAdvice(new RestApiExceptionHandler())
                .build();
        return new ControllerFixture(mockMvc, identityRepository);
    }

    private ProjectMember member(String projectId, String userId, String roleKey) {
        var now = Instant.parse("2026-06-27T01:00:00Z");
        return new ProjectMember(projectId + ":" + userId + ":" + roleKey, projectId, userId, roleKey, "ACTIVE", now, now, now);
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

    private record ControllerFixture(MockMvc mockMvc, RecordingProjectIdentityRepository identityRepository) {
    }

    private static final class RecordingAgentRuntimeRepository implements AgentRuntimeRepository {

        private final List<AgentRunRecord> savedRuns = new ArrayList<>();

        @Override
        public void saveRun(AgentRunRecord run) {
            savedRuns.removeIf(existing -> existing.id().equals(run.id()));
            savedRuns.add(0, run);
        }

        @Override
        public void appendEvent(AgentRunEventRecord event) {
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
            return List.of();
        }
    }

    private static final class RecordingProjectIdentityRepository implements ProjectIdentityRepository {

        private final Map<String, MatrixUser> users = new LinkedHashMap<>();
        private final Map<String, ProjectMember> members = new LinkedHashMap<>();

        @Override
        public void ensureUser(MatrixUser user) {
            users.put(user.id(), user);
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
            return members.values().stream()
                    .filter(member -> member.userId().equals(userId))
                    .map(ProjectMember::projectId)
                    .distinct()
                    .toList();
        }

        @Override
        public List<UserAuditRecord> auditRecords(String projectId, String userId) {
            return List.of();
        }
    }
}
