package com.matrixcode.codingagent;

import com.matrixcode.MatrixCodeServerApplication;
import com.matrixcode.agentruntime.application.AgentRuntimeRepository;
import com.matrixcode.agentruntime.domain.AgentRunEventRecord;
import com.matrixcode.agentruntime.domain.AgentRunRecord;
import com.matrixcode.identity.application.ProjectIdentityRepository;
import com.matrixcode.identity.domain.MatrixUser;
import com.matrixcode.identity.domain.ProjectMember;
import com.matrixcode.identity.domain.UserAuditRecord;
import com.matrixcode.localexecution.application.WorkspaceRegistry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.web.servlet.MockMvc;

import java.nio.file.Path;
import java.nio.file.Files;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static com.matrixcode.identity.api.RequestActorResolver.CURRENT_USER_HEADER;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(classes = MatrixCodeServerApplication.class)
@AutoConfigureMockMvc
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_EACH_TEST_METHOD)
@Import(CodingAgentControllerTest.AgentRuntimeTestConfiguration.class)
class CodingAgentControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private WorkspaceRegistry workspaceRegistry;

    @Autowired
    private ProjectIdentityRepository identityRepository;

    @TempDir
    Path tempDir;

    @Test
    void 创建编码任务缺少请求身份时返回401() throws Exception {
        mockMvc.perform(post("/api/projects/demo/roles/developer/coding-agent/tasks")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"goal":"实现登录接口","workspaceId":"workspace-main","actorId":"user-dev"}
                                """))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void 创建编码任务请求身份和操作者不一致时返回403() throws Exception {
        mockMvc.perform(post("/api/projects/demo/roles/developer/coding-agent/tasks")
                        .header(CURRENT_USER_HEADER, "user-other")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"goal":"实现登录接口","workspaceId":"workspace-main","actorId":"user-dev"}
                                """))
                .andExpect(status().isForbidden());
    }

    @Test
    void 执行准备请求身份和操作者不一致时返回403() throws Exception {
        var workspace = workspaceRegistry.authorize("demo", "当前项目", tempDir.toString());

        mockMvc.perform(post("/api/projects/demo/roles/developer/coding-agent/execution-plans")
                        .header(CURRENT_USER_HEADER, "user-other")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"goal":"实现登录接口","workspaceId":"%s","actorId":"user-dev","testCommand":"git status"}
                                """.formatted(workspace.id())))
                .andExpect(status().isForbidden());
    }

    @Test
    void 应用Patch请求身份和操作者不一致时返回403() throws Exception {
        var workspace = workspaceRegistry.authorize("demo", "当前项目", tempDir.toString());
        Files.createDirectories(tempDir.resolve("src"));
        Files.writeString(tempDir.resolve("src/App.java"), "class App {}\n");

        mockMvc.perform(post("/api/projects/demo/roles/developer/coding-agent/patches")
                        .header(CURRENT_USER_HEADER, "user-other")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "workspaceId":"%s",
                                  "actorId":"user-dev",
                                  "relativePath":"src/App.java",
                                  "expectedContent":"class App {}\\n",
                                  "nextContent":"class App { void run() {} }\\n",
                                  "summary":"补充入口",
                                  "approved":true
                                }
                                """.formatted(workspace.id())))
                .andExpect(status().isForbidden());
    }

    @Test
    void 交付回溯请求身份和操作者不一致时返回403() throws Exception {
        mockMvc.perform(post("/api/projects/demo/roles/developer/coding-agent/handoffs")
                        .header(CURRENT_USER_HEADER, "user-other")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "workspaceId":"workspace-1",
                                  "actorId":"user-dev",
                                  "goal":"实现登录接口",
                                  "relativePath":"src/App.java",
                                  "patchSummary":"补充入口",
                                  "diffSummary":"1 file changed",
                                  "testTaskId":"task-1",
                                  "testTaskStatus":"SUCCESS",
                                  "testCommand":"mvn test",
                                  "deliveryConclusion":"测试通过，可以交付"
                                }
                                """))
                .andExpect(status().isForbidden());
    }

    @Test
    void 可以创建开发角色编码智能体任务计划() throws Exception {
        mockMvc.perform(post("/api/projects/demo/roles/developer/coding-agent/tasks")
                        .header(CURRENT_USER_HEADER, "user-dev")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"goal":"实现登录接口","workspaceId":"workspace-main","actorId":"user-dev"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.projectId").value("demo"))
                .andExpect(jsonPath("$.role").value("DEVELOPER"))
                .andExpect(jsonPath("$.goal").value("实现登录接口"))
                .andExpect(jsonPath("$.steps[0].type").value("CONTEXT_RECALL"))
                .andExpect(jsonPath("$.steps[4].type").value("TEST_COMMAND"))
                .andExpect(jsonPath("$.steps[4].requiresApproval").value(true));
    }

    @Test
    void 创建编码任务后可以查询Agent运行和事件时间线() throws Exception {
        identityRepository.ensureMember(member("demo", "user-dev", "DEVELOPER"));

        var taskResponse = mockMvc.perform(post("/api/projects/demo/roles/developer/coding-agent/tasks")
                        .header(CURRENT_USER_HEADER, "user-dev")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"goal":"实现登录接口","workspaceId":"workspace-main","actorId":"user-dev"}
                                """))
                .andExpect(status().isOk())
                .andReturn();
        var taskId = com.jayway.jsonpath.JsonPath.read(taskResponse.getResponse().getContentAsString(), "$.taskId").toString();

        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/api/projects/demo/agent-runs")
                        .header(CURRENT_USER_HEADER, "user-dev"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].id").value(taskId))
                .andExpect(jsonPath("$[0].actorUserId").value("user-dev"))
                .andExpect(jsonPath("$[0].status").value("QUEUED"))
                .andExpect(jsonPath("$[0].goal").value("实现登录接口"));

        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/api/projects/demo/agent-runs/" + taskId + "/events")
                        .header(CURRENT_USER_HEADER, "user-dev"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].runId").value(taskId))
                .andExpect(jsonPath("$[0].eventType").value("TASK_PLANNED"))
                .andExpect(jsonPath("$[0].eventPayload").value(org.hamcrest.Matchers.containsString("workspace-main")));
    }

    private ProjectMember member(String projectId, String userId, String roleKey) {
        var now = Instant.parse("2026-06-29T00:00:00Z");
        return new ProjectMember(projectId + ":" + userId + ":" + roleKey, projectId, userId, roleKey, "ACTIVE", now, now, now);
    }

    @Test
    void 可以创建编码智能体执行准备报告() throws Exception {
        var workspace = workspaceRegistry.authorize("demo", "当前项目", tempDir.toString());

        mockMvc.perform(post("/api/projects/demo/roles/developer/coding-agent/execution-plans")
                        .header(CURRENT_USER_HEADER, "user-dev")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"goal":"实现登录接口","workspaceId":"%s","actorId":"user-dev","testCommand":"git status"}
                                """.formatted(workspace.id())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.task.role").value("DEVELOPER"))
                .andExpect(jsonPath("$.testCommandTask.status").value("APPROVAL_PENDING"))
                .andExpect(jsonPath("$.executionSteps[3].type").value("CODE_EDIT"))
                .andExpect(jsonPath("$.executionSteps[3].status").value("APPROVAL_REQUIRED"))
                .andExpect(jsonPath("$.gitDiffSummary.workspaceId").value(workspace.id()));
    }

    @Test
    void 可以应用受控编码智能体patch() throws Exception {
        var workspace = workspaceRegistry.authorize("demo", "当前项目", tempDir.toString());
        Files.createDirectories(tempDir.resolve("src"));
        Files.writeString(tempDir.resolve("src/App.java"), "class App {}\n");

        mockMvc.perform(post("/api/projects/demo/roles/developer/coding-agent/patches")
                        .header(CURRENT_USER_HEADER, "user-dev")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "workspaceId":"%s",
                                  "actorId":"user-dev",
                                  "relativePath":"src/App.java",
                                  "expectedContent":"class App {}\\n",
                                  "nextContent":"class App { void run() {} }\\n",
                                  "summary":"补充入口",
                                  "approved":true
                                }
                                """.formatted(workspace.id())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.projectId").value("demo"))
                .andExpect(jsonPath("$.role").value("DEVELOPER"))
                .andExpect(jsonPath("$.relativePath").value("src/App.java"))
                .andExpect(jsonPath("$.bytesWritten").isNumber())
                .andExpect(jsonPath("$.gitDiffSummary.workspaceId").value(workspace.id()));
    }

    @Test
    void 可以记录编码智能体交付回溯() throws Exception {
        mockMvc.perform(post("/api/projects/demo/roles/developer/coding-agent/handoffs")
                        .header(CURRENT_USER_HEADER, "user-dev")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "workspaceId":"workspace-1",
                                  "actorId":"user-dev",
                                  "goal":"实现登录接口",
                                  "relativePath":"src/App.java",
                                  "patchSummary":"补充入口",
                                  "diffSummary":"1 file changed",
                                  "testTaskId":"task-1",
                                  "testTaskStatus":"SUCCESS",
                                  "testCommand":"mvn test",
                                  "deliveryConclusion":"测试通过，可以交付"
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.type").value("CODING_AGENT_HANDOFF"))
                .andExpect(jsonPath("$.title").value("编码智能体交付回溯"))
                .andExpect(jsonPath("$.content").value(org.hamcrest.Matchers.containsString("Patch 摘要：补充入口")))
                .andExpect(jsonPath("$.content").value(org.hamcrest.Matchers.containsString("测试状态：SUCCESS")))
                .andExpect(jsonPath("$.content").value(org.hamcrest.Matchers.containsString("交付结论：测试通过，可以交付")));
    }

    @TestConfiguration
    static class AgentRuntimeTestConfiguration {

        @Bean
        AgentRuntimeRepository agentRuntimeRepository() {
            return new RecordingAgentRuntimeRepository();
        }

        @Bean
        ProjectIdentityRepository projectIdentityRepository() {
            return new RecordingProjectIdentityRepository();
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
            for (var index = savedRuns.size() - 1; index >= 0; index--) {
                var run = savedRuns.get(index);
                if (run.id().equals(runId)) {
                    return Optional.of(run);
                }
            }
            return Optional.empty();
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

    private static final class RecordingProjectIdentityRepository implements ProjectIdentityRepository {

        private final List<ProjectMember> members = new ArrayList<>();

        @Override
        public void ensureUser(MatrixUser user) {
        }

        @Override
        public void ensureProject(String projectId, String name, String ownerUserId, String currentStage) {
        }

        @Override
        public void ensureMember(ProjectMember member) {
            members.removeIf(existing -> existing.projectId().equals(member.projectId())
                    && existing.userId().equals(member.userId())
                    && existing.roleKey().equals(member.roleKey()));
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
            return members.stream()
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
