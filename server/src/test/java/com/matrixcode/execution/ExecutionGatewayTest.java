package com.matrixcode.execution;

import com.matrixcode.execution.api.ExecutionAgentController;
import com.matrixcode.execution.application.ExecutionGateway;
import com.matrixcode.execution.application.ExecutionAgentProperties;
import com.matrixcode.execution.domain.AgentHeartbeat;
import com.matrixcode.execution.domain.ExecutionResult;
import com.matrixcode.identity.api.ProjectRequestPermissionGuard;
import com.matrixcode.identity.api.RequestActorResolver;
import com.matrixcode.identity.application.ProjectIdentityRepository;
import com.matrixcode.identity.application.ProjectIdentityService;
import com.matrixcode.identity.application.ProjectMemberPermissionGuard;
import com.matrixcode.identity.domain.MatrixUser;
import com.matrixcode.identity.domain.ProjectMember;
import com.matrixcode.identity.domain.UserAuditRecord;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static com.matrixcode.identity.api.RequestActorResolver.CURRENT_USER_HEADER;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class ExecutionGatewayTest {

    private static final String AGENT_TOKEN_HEADER = "X-MatrixCode-Agent-Token";
    private static final Clock FIXED_CLOCK =
            Clock.fixed(Instant.parse("2026-06-29T03:30:00Z"), ZoneOffset.UTC);

    @Test
    void 执行代理可以上报心跳和任务结果() {
        var gateway = new ExecutionGateway();

        gateway.heartbeat(new AgentHeartbeat("agent-1", "project-1", "user-dev", "ONLINE"));
        gateway.report(new ExecutionResult("task-1", "agent-1", "SUCCESS", "mvn test 通过"));

        assertThat(gateway.lastHeartbeat("agent-1").status()).isEqualTo("ONLINE");
        assertThat(gateway.resultOf("task-1").summary()).isEqualTo("mvn test 通过");
    }

    @Test
    void 心跳按代理隔离且后一次覆盖前一次() {
        var gateway = new ExecutionGateway();

        gateway.heartbeat(new AgentHeartbeat("agent-1", "project-1", "user-dev", "ONLINE"));
        gateway.heartbeat(new AgentHeartbeat("agent-2", "project-1", "user-dev", "ONLINE"));
        gateway.heartbeat(new AgentHeartbeat("agent-1", "project-1", "user-dev", "BUSY"));

        assertThat(gateway.lastHeartbeat("agent-1").status()).isEqualTo("BUSY");
        assertThat(gateway.lastHeartbeat("agent-2").status()).isEqualTo("ONLINE");
    }

    @Test
    void 结果按任务隔离() {
        var gateway = new ExecutionGateway();

        gateway.report(new ExecutionResult("task-1", "agent-1", "SUCCESS", "任务一完成"));
        gateway.report(new ExecutionResult("task-2", "agent-1", "FAILED", "任务二失败"));

        assertThat(gateway.resultOf("task-1").summary()).isEqualTo("任务一完成");
        assertThat(gateway.resultOf("task-2").status()).isEqualTo("FAILED");
    }

    @Test
    void 已记录的任务结果不能被不同结果覆盖() {
        var gateway = new ExecutionGateway();

        assertThat(gateway.report(new ExecutionResult("task-1", "agent-1", "SUCCESS", "任务完成")))
                .isEqualTo(ExecutionGateway.ReportOutcome.RECORDED);
        assertThat(gateway.report(new ExecutionResult("task-1", "agent-1", "SUCCESS", "任务完成")))
                .isEqualTo(ExecutionGateway.ReportOutcome.DUPLICATE);
        assertThat(gateway.report(new ExecutionResult("task-1", "agent-1", "FAILED", "回放覆盖")))
                .isEqualTo(ExecutionGateway.ReportOutcome.REPLAY_REJECTED);

        assertThat(gateway.resultOf("task-1").status()).isEqualTo("SUCCESS");
        assertThat(gateway.resultOf("task-1").summary()).isEqualTo("任务完成");
    }

    @Test
    void 心跳拒绝空白核心字段() {
        assertThatThrownBy(() -> new AgentHeartbeat("", "project-1", "user-dev", "ONLINE"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("agentId");
        assertThatThrownBy(() -> new AgentHeartbeat("agent-1", " ", "user-dev", "ONLINE"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("projectId");
        assertThatThrownBy(() -> new AgentHeartbeat("agent-1", "project-1", null, "ONLINE"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("userId");
        assertThatThrownBy(() -> new AgentHeartbeat("agent-1", "project-1", "user-dev", ""))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("status");
    }

    @Test
    void 结果拒绝空白核心字段() {
        assertThatThrownBy(() -> new ExecutionResult("", "agent-1", "SUCCESS", "任务完成"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("taskId");
        assertThatThrownBy(() -> new ExecutionResult("task-1", " ", "SUCCESS", "任务完成"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("agentId");
        assertThatThrownBy(() -> new ExecutionResult("task-1", "agent-1", null, "任务完成"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("status");
        assertThatThrownBy(() -> new ExecutionResult("task-1", "agent-1", "SUCCESS", ""))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("summary");
    }

    @Test
    void 控制器可以接收心跳和结果上报() throws Exception {
        var fixture = fixture();
        fixture.repository.ensureMember(member("project-1", "user-dev", "DEVELOPER"));

        fixture.mockMvc.perform(post("/api/execution-agents/heartbeat")
                        .header(CURRENT_USER_HEADER, "user-dev")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "agentId": "agent-1",
                                  "projectId": "project-1",
                                  "userId": "user-dev",
                                  "status": "ONLINE"
                                }
                                """))
                .andExpect(status().isAccepted());
        fixture.mockMvc.perform(post("/api/execution-agents/results")
                        .header(CURRENT_USER_HEADER, "user-dev")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "taskId": "task-1",
                                  "agentId": "agent-1",
                                  "status": "SUCCESS",
                                  "summary": "mvn test 通过"
                                }
                                """))
                .andExpect(status().isAccepted());

        assertThat(fixture.gateway.lastHeartbeat("agent-1").status()).isEqualTo("ONLINE");
        assertThat(fixture.gateway.resultOf("task-1").summary()).isEqualTo("mvn test 通过");
    }

    @Test
    void 控制器拒绝同任务不同内容的结果回放() throws Exception {
        var fixture = fixture();
        fixture.repository.ensureMember(member("project-1", "user-dev", "DEVELOPER"));

        fixture.mockMvc.perform(post("/api/execution-agents/heartbeat")
                        .header(CURRENT_USER_HEADER, "user-dev")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "agentId": "agent-1",
                                  "projectId": "project-1",
                                  "userId": "user-dev",
                                  "status": "ONLINE"
                                }
                                """))
                .andExpect(status().isAccepted());
        fixture.mockMvc.perform(post("/api/execution-agents/results")
                        .header(CURRENT_USER_HEADER, "user-dev")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "taskId": "task-1",
                                  "agentId": "agent-1",
                                  "status": "SUCCESS",
                                  "summary": "首次结果"
                                }
                                """))
                .andExpect(status().isAccepted());
        fixture.mockMvc.perform(post("/api/execution-agents/results")
                        .header(CURRENT_USER_HEADER, "user-dev")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "taskId": "task-1",
                                  "agentId": "agent-1",
                                  "status": "FAILED",
                                  "summary": "回放覆盖"
                                }
                                """))
                .andExpect(status().isConflict());

        assertThat(fixture.gateway.resultOf("task-1").status()).isEqualTo("SUCCESS");
        assertThat(fixture.gateway.resultOf("task-1").summary()).isEqualTo("首次结果");
    }

    @Test
    void 控制器拒绝缺少请求身份的心跳() throws Exception {
        var fixture = fixture();
        fixture.repository.ensureMember(member("project-1", "user-dev", "DEVELOPER"));

        fixture.mockMvc.perform(post("/api/execution-agents/heartbeat")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "agentId": "agent-1",
                                  "projectId": "project-1",
                                  "userId": "user-dev",
                                  "status": "ONLINE"
                                }
                                """))
                .andExpect(status().isUnauthorized());

        assertThat(fixture.gateway.lastHeartbeat("agent-1")).isNull();
    }

    @Test
    void 控制器拒绝请求身份和代理用户不一致的心跳() throws Exception {
        var fixture = fixture();
        fixture.repository.ensureMember(member("project-1", "user-dev", "DEVELOPER"));
        fixture.repository.ensureMember(member("project-1", "user-ops", "OPERATIONS"));

        fixture.mockMvc.perform(post("/api/execution-agents/heartbeat")
                        .header(CURRENT_USER_HEADER, "user-ops")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "agentId": "agent-1",
                                  "projectId": "project-1",
                                  "userId": "user-dev",
                                  "status": "ONLINE"
                                }
                                """))
                .andExpect(status().isForbidden());

        assertThat(fixture.gateway.lastHeartbeat("agent-1")).isNull();
    }

    @Test
    void 控制器在配置代理密钥后拒绝缺少代理Token的心跳() throws Exception {
        var fixture = fixture(agentProperties("agent-secret", 120));
        fixture.repository.ensureMember(member("project-1", "user-dev", "DEVELOPER"));

        fixture.mockMvc.perform(post("/api/execution-agents/heartbeat")
                        .header(CURRENT_USER_HEADER, "user-dev")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "agentId": "agent-1",
                                  "projectId": "project-1",
                                  "userId": "user-dev",
                                  "status": "ONLINE"
                                }
                                """))
                .andExpect(status().isUnauthorized());

        assertThat(fixture.gateway.lastHeartbeat("agent-1")).isNull();
    }

    @Test
    void 控制器在配置代理密钥后拒绝错误代理Token的结果上报() throws Exception {
        var fixture = fixture(agentProperties("agent-secret", 120));
        fixture.repository.ensureMember(member("project-1", "user-dev", "DEVELOPER"));

        fixture.mockMvc.perform(post("/api/execution-agents/heartbeat")
                        .header(CURRENT_USER_HEADER, "user-dev")
                        .header(AGENT_TOKEN_HEADER, "agent-secret")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "agentId": "agent-1",
                                  "projectId": "project-1",
                                  "userId": "user-dev",
                                  "status": "ONLINE"
                                }
                                """))
                .andExpect(status().isAccepted());
        fixture.mockMvc.perform(post("/api/execution-agents/results")
                        .header(CURRENT_USER_HEADER, "user-dev")
                        .header(AGENT_TOKEN_HEADER, "wrong-secret")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "taskId": "task-1",
                                  "agentId": "agent-1",
                                  "status": "SUCCESS",
                                  "summary": "mvn test 通过"
                                }
                                """))
                .andExpect(status().isForbidden());

        assertThat(fixture.gateway.resultOf("task-1")).isNull();
    }

    @Test
    void 控制器在凭据轮换期间接受旧代理Token() throws Exception {
        var fixture = fixture(agentProperties("current-secret", 120, "previous-secret"));
        fixture.repository.ensureMember(member("project-1", "user-dev", "DEVELOPER"));

        fixture.mockMvc.perform(post("/api/execution-agents/heartbeat")
                        .header(CURRENT_USER_HEADER, "user-dev")
                        .header(AGENT_TOKEN_HEADER, "previous-secret")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "agentId": "agent-1",
                                  "projectId": "project-1",
                                  "userId": "user-dev",
                                  "status": "ONLINE"
                                }
                                """))
                .andExpect(status().isAccepted());
        fixture.mockMvc.perform(post("/api/execution-agents/results")
                        .header(CURRENT_USER_HEADER, "user-dev")
                        .header(AGENT_TOKEN_HEADER, "previous-secret")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "taskId": "task-1",
                                  "agentId": "agent-1",
                                  "status": "SUCCESS",
                                  "summary": "mvn test 通过"
                                }
                                """))
                .andExpect(status().isAccepted());

        assertThat(fixture.gateway.resultOf("task-1").summary()).isEqualTo("mvn test 通过");
    }

    @Test
    void 旧代理Token配置会清理空白值() {
        var properties = agentProperties("current-secret", 120, " previous-secret ", " ", "older-secret");

        assertThat(properties.getPreviousSharedSecrets()).containsExactly("previous-secret", "older-secret");
    }

    @Test
    void 控制器拒绝过期认证心跳上下文的结果上报() throws Exception {
        var clock = new MutableClock(FIXED_CLOCK.instant());
        var fixture = fixture(agentProperties("", 5), clock);
        fixture.repository.ensureMember(member("project-1", "user-dev", "DEVELOPER"));

        fixture.mockMvc.perform(post("/api/execution-agents/heartbeat")
                        .header(CURRENT_USER_HEADER, "user-dev")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "agentId": "agent-1",
                                  "projectId": "project-1",
                                  "userId": "user-dev",
                                  "status": "ONLINE"
                                }
                                """))
                .andExpect(status().isAccepted());

        clock.advance(Duration.ofSeconds(6));

        fixture.mockMvc.perform(post("/api/execution-agents/results")
                        .header(CURRENT_USER_HEADER, "user-dev")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "taskId": "task-1",
                                  "agentId": "agent-1",
                                  "status": "SUCCESS",
                                  "summary": "mvn test 通过"
                                }
                                """))
                .andExpect(status().isConflict());

        assertThat(fixture.gateway.resultOf("task-1")).isNull();
    }

    @Test
    void 控制器拒绝没有已认证心跳上下文的结果上报() throws Exception {
        var fixture = fixture();
        fixture.repository.ensureMember(member("project-1", "user-dev", "DEVELOPER"));

        fixture.mockMvc.perform(post("/api/execution-agents/results")
                        .header(CURRENT_USER_HEADER, "user-dev")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "taskId": "task-1",
                                  "agentId": "agent-1",
                                  "status": "SUCCESS",
                                  "summary": "mvn test 通过"
                                }
                                """))
                .andExpect(status().isConflict());

        assertThat(fixture.gateway.resultOf("task-1")).isNull();
    }

    @Test
    void 控制器拒绝空白上报字段() throws Exception {
        var fixture = fixture();
        fixture.repository.ensureMember(member("project-1", "user-dev", "DEVELOPER"));

        fixture.mockMvc.perform(post("/api/execution-agents/heartbeat")
                        .header(CURRENT_USER_HEADER, "user-dev")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "agentId": "",
                                  "projectId": "project-1",
                                  "userId": "user-dev",
                                  "status": "ONLINE"
                                }
                                """))
                .andExpect(status().isBadRequest());
        fixture.mockMvc.perform(post("/api/execution-agents/results")
                        .header(CURRENT_USER_HEADER, "user-dev")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "taskId": "task-1",
                                  "agentId": "agent-1",
                                  "status": "SUCCESS",
                                  "summary": " "
                                }
                                """))
                .andExpect(status().isBadRequest());

        assertThat(fixture.gateway.lastHeartbeat("")).isNull();
        assertThat(fixture.gateway.resultOf("task-1")).isNull();
    }

    private ControllerFixture fixture() {
        return fixture(new ExecutionAgentProperties(), FIXED_CLOCK);
    }

    private ControllerFixture fixture(ExecutionAgentProperties properties) {
        return fixture(properties, FIXED_CLOCK);
    }

    private ControllerFixture fixture(ExecutionAgentProperties properties, Clock clock) {
        var gateway = new ExecutionGateway(clock);
        var repository = new RecordingProjectIdentityRepository();
        var identityService = new ProjectIdentityService(repository, FIXED_CLOCK);
        var requestPermissionGuard = new ProjectRequestPermissionGuard(
                new RequestActorResolver(),
                new ProjectMemberPermissionGuard(identityService)
        );
        var mockMvc = MockMvcBuilders.standaloneSetup(
                new ExecutionAgentController(gateway, requestPermissionGuard, properties)
        ).build();
        return new ControllerFixture(gateway, repository, mockMvc);
    }

    private ExecutionAgentProperties agentProperties(String sharedSecret, long heartbeatTtlSeconds, String... previousSharedSecrets) {
        var properties = new ExecutionAgentProperties();
        properties.setSharedSecret(sharedSecret);
        properties.setHeartbeatTtlSeconds(heartbeatTtlSeconds);
        properties.setPreviousSharedSecrets(List.of(previousSharedSecrets));
        return properties;
    }

    private ProjectMember member(String projectId, String userId, String roleKey) {
        var now = FIXED_CLOCK.instant();
        return new ProjectMember(projectId + ":" + userId + ":" + roleKey, projectId, userId, roleKey, "ACTIVE", now, now, now);
    }

    private record ControllerFixture(
            ExecutionGateway gateway,
            RecordingProjectIdentityRepository repository,
            org.springframework.test.web.servlet.MockMvc mockMvc
    ) {
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
            return List.of();
        }

        @Override
        public List<UserAuditRecord> auditRecords(String projectId, String userId) {
            return List.of();
        }
    }

    private static final class MutableClock extends Clock {

        private Instant instant;

        private MutableClock(Instant instant) {
            this.instant = instant;
        }

        private void advance(Duration duration) {
            instant = instant.plus(duration);
        }

        @Override
        public ZoneOffset getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(java.time.ZoneId zone) {
            return Clock.fixed(instant, zone);
        }

        @Override
        public Instant instant() {
            return instant;
        }
    }
}
