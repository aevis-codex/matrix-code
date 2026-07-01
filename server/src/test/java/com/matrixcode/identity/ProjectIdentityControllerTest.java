package com.matrixcode.identity;

import com.matrixcode.MatrixCodeServerApplication;
import com.matrixcode.identity.api.RequestActorResolver;
import com.matrixcode.identity.application.ProjectIdentityRepository;
import com.matrixcode.identity.application.ProjectIdentityRepository.StoredProjectInvitation;
import com.matrixcode.identity.domain.MatrixUser;
import com.matrixcode.identity.domain.ProjectInvitation;
import com.matrixcode.identity.domain.ProjectMember;
import com.matrixcode.identity.domain.UserAuditRecord;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.http.MediaType;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasItem;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(classes = {
        MatrixCodeServerApplication.class,
        ProjectIdentityControllerTest.IdentityRepositoryTestConfig.class
})
@AutoConfigureMockMvc
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_EACH_TEST_METHOD)
class ProjectIdentityControllerTest {

    private static final Path LOCAL_EXECUTION_STORAGE =
            Path.of(System.getProperty("java.io.tmpdir"), "matrixcode-test-local-execution-identity-api-" + System.nanoTime() + ".json");
    private static final Path RUNTIME_NOTIFICATION_STORAGE =
            Path.of(System.getProperty("java.io.tmpdir"), "matrixcode-test-runtime-notifications-identity-api-" + System.nanoTime() + ".json");
    private static final Path WORKBENCH_STATE_STORAGE =
            Path.of(System.getProperty("java.io.tmpdir"), "matrixcode-test-workbench-state-identity-api-" + System.nanoTime() + ".json");
    private static final String VALID_INVITATION_EXPIRES_AT = "2099-01-01T00:00:00Z";
    private static final String REISSUED_INVITATION_EXPIRES_AT = "2099-01-02T00:00:00Z";
    private static final String EXPIRED_INVITATION_EXPIRES_AT = "2000-01-01T00:00:00Z";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private FakeProjectIdentityRepository repository;

    @DynamicPropertySource
    static void persistentStorageProperties(DynamicPropertyRegistry registry) {
        registry.add("matrixcode.local-execution.storage-path", LOCAL_EXECUTION_STORAGE::toString);
        registry.add("matrixcode.runtime-notifications.storage-path", RUNTIME_NOTIFICATION_STORAGE::toString);
        registry.add("matrixcode.workbench-state.storage-path", WORKBENCH_STATE_STORAGE::toString);
    }

    @AfterEach
    void cleanPersistentStorage() throws Exception {
        Files.deleteIfExists(LOCAL_EXECUTION_STORAGE);
        Files.deleteIfExists(RUNTIME_NOTIFICATION_STORAGE);
        Files.deleteIfExists(WORKBENCH_STATE_STORAGE);
    }

    @Test
    void 返回项目成员列表() throws Exception {
        repository.ensureMember(member("demo", "user-dev", "DEVELOPER"));

        mockMvc.perform(get("/api/projects/demo/identity/members")
                        .header(RequestActorResolver.CURRENT_USER_HEADER, "user-dev"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].projectId").value("demo"))
                .andExpect(jsonPath("$[0].userId").value("user-dev"))
                .andExpect(jsonPath("$[0].roleKey").value("DEVELOPER"));
    }

    @Test
    void 缺少登录身份时拒绝读取项目成员列表() throws Exception {
        repository.ensureMember(member("demo", "user-dev", "DEVELOPER"));

        mockMvc.perform(get("/api/projects/demo/identity/members"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void 非项目成员不能读取项目成员列表() throws Exception {
        repository.ensureMember(member("demo", "user-dev", "DEVELOPER"));

        mockMvc.perform(get("/api/projects/demo/identity/members")
                        .header(RequestActorResolver.CURRENT_USER_HEADER, "user-outsider"))
                .andExpect(status().isForbidden());
    }

    @Test
    void 可以添加项目成员并归一化输入() throws Exception {
        repository.ensureMember(member("demo", "user-owner", "OWNER"));

        mockMvc.perform(post("/api/projects/demo/identity/members")
                        .header(RequestActorResolver.CURRENT_USER_HEADER, "user-owner")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"userId":" user-tester ","displayName":"测试同学","roleKey":" tester "}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.projectId").value("demo"))
                .andExpect(jsonPath("$.userId").value("user-tester"))
                .andExpect(jsonPath("$.roleKey").value("TESTER"));

        mockMvc.perform(get("/api/projects/demo/identity/members")
                        .header(RequestActorResolver.CURRENT_USER_HEADER, "user-owner"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[*].userId").value(hasItem("user-tester")))
                .andExpect(jsonPath("$[*].roleKey").value(hasItem("TESTER")));
    }

    @Test
    void 缺少登录身份时拒绝添加项目成员() throws Exception {
        mockMvc.perform(post("/api/projects/demo/identity/members")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"userId":"user-tester","displayName":"测试同学","roleKey":"TESTER"}
                                """))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void 普通成员不能添加项目成员() throws Exception {
        repository.ensureMember(member("demo", "user-dev", "DEVELOPER"));

        mockMvc.perform(post("/api/projects/demo/identity/members")
                        .header(RequestActorResolver.CURRENT_USER_HEADER, "user-dev")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"userId":"user-tester","displayName":"测试同学","roleKey":"TESTER"}
                                """))
                .andExpect(status().isForbidden());
    }

    @Test
    void 超级管理员可以创建可登录用户并授予项目角色() throws Exception {
        repository.saveSuperAdmin("admin");

        mockMvc.perform(post("/api/projects/demo/identity/users")
                        .header(RequestActorResolver.CURRENT_USER_HEADER, "admin")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "userId":"user-new",
                                  "username":"new-user",
                                  "displayName":"新用户",
                                  "email":"new@example.com",
                                  "password":"Secret-123",
                                  "roleKey":"DEVELOPER"
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.projectId").value("demo"))
                .andExpect(jsonPath("$.userId").value("user-new"))
                .andExpect(jsonPath("$.roleKey").value("DEVELOPER"));

        assertThat(repository.userCredentialByUsername("new-user"))
                .isPresent()
                .get()
                .satisfies(credential -> {
                    assertThat(credential.passwordHash()).isNotEqualTo("Secret-123");
                    assertThat(credential.superAdmin()).isFalse();
                });
    }

    @Test
    void 非超级管理员不能创建可登录用户() throws Exception {
        repository.ensureMember(member("demo", "user-owner", "OWNER"));

        mockMvc.perform(post("/api/projects/demo/identity/users")
                        .header(RequestActorResolver.CURRENT_USER_HEADER, "user-owner")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"userId":"user-new","username":"new-user","password":"Secret-123","roleKey":"DEVELOPER"}
                                """))
                .andExpect(status().isForbidden());
    }

    @Test
    void 项目管理者可以变更成员角色和状态() throws Exception {
        repository.ensureMember(member("demo", "user-owner", "OWNER"));
        repository.ensureMember(member("demo", "user-dev", "DEVELOPER"));

        mockMvc.perform(patch("/api/projects/demo/identity/members/user-dev")
                        .header(RequestActorResolver.CURRENT_USER_HEADER, "user-owner")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"roleKey":" tester ","status":" active "}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.userId").value("user-dev"))
                .andExpect(jsonPath("$.roleKey").value("TESTER"))
                .andExpect(jsonPath("$.status").value("ACTIVE"));

        mockMvc.perform(get("/api/projects/demo/identity/members")
                        .header(RequestActorResolver.CURRENT_USER_HEADER, "user-owner"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.userId == 'user-dev')].roleKey").value(contains("TESTER")));
    }

    @Test
    void 项目管理者可以创建邀请并由被邀请用户接受() throws Exception {
        repository.ensureMember(member("demo", "user-owner", "OWNER"));

        var issued = mockMvc.perform(post("/api/projects/demo/identity/invitations")
                        .header(RequestActorResolver.CURRENT_USER_HEADER, "user-owner")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"userId":" user-dev ","displayName":"开发同学","roleKey":" developer ","expiresAt":"%s"}
                                """.formatted(VALID_INVITATION_EXPIRES_AT)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.token").isNotEmpty())
                .andExpect(jsonPath("$.invitation.projectId").value("demo"))
                .andExpect(jsonPath("$.invitation.inviteeUserId").value("user-dev"))
                .andExpect(jsonPath("$.invitation.roleKey").value("DEVELOPER"))
                .andExpect(jsonPath("$.invitation.status").value("PENDING"))
                .andReturn();

        mockMvc.perform(get("/api/projects/demo/identity/invitations")
                        .header(RequestActorResolver.CURRENT_USER_HEADER, "user-owner"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].inviteeUserId").value("user-dev"))
                .andExpect(jsonPath("$[0].status").value("PENDING"));

        var token = com.jayway.jsonpath.JsonPath.read(
                issued.getResponse().getContentAsString(),
                "$.token"
        ).toString();
        mockMvc.perform(post("/api/projects/demo/identity/invitations/{token}/accept", token)
                        .header(RequestActorResolver.CURRENT_USER_HEADER, "user-dev"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.userId").value("user-dev"))
                .andExpect(jsonPath("$.roleKey").value("DEVELOPER"));

        mockMvc.perform(get("/api/projects/demo/identity/members")
                        .header(RequestActorResolver.CURRENT_USER_HEADER, "user-owner"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.userId == 'user-dev')].status").value(contains("ACTIVE")));
    }

    @Test
    void 项目管理者可以撤销重发并清理过期邀请() throws Exception {
        repository.ensureMember(member("demo", "user-owner", "OWNER"));

        var issued = mockMvc.perform(post("/api/projects/demo/identity/invitations")
                        .header(RequestActorResolver.CURRENT_USER_HEADER, "user-owner")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"userId":"user-dev","displayName":"开发同学","roleKey":"DEVELOPER","expiresAt":"%s"}
                                """.formatted(VALID_INVITATION_EXPIRES_AT)))
                .andExpect(status().isOk())
                .andReturn();
        var invitationId = com.jayway.jsonpath.JsonPath.read(
                issued.getResponse().getContentAsString(),
                "$.invitation.id"
        ).toString();
        var oldToken = com.jayway.jsonpath.JsonPath.read(
                issued.getResponse().getContentAsString(),
                "$.token"
        ).toString();

        mockMvc.perform(post("/api/projects/demo/identity/invitations/{invitationId}/revoke", invitationId)
                        .header(RequestActorResolver.CURRENT_USER_HEADER, "user-owner"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("REVOKED"));

        mockMvc.perform(post("/api/projects/demo/identity/invitations/{token}/accept", oldToken)
                        .header(RequestActorResolver.CURRENT_USER_HEADER, "user-dev"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("项目邀请不可用"));

        var reissued = mockMvc.perform(post("/api/projects/demo/identity/invitations/{invitationId}/reissue", invitationId)
                        .header(RequestActorResolver.CURRENT_USER_HEADER, "user-owner")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"expiresAt":"%s"}
                                """.formatted(REISSUED_INVITATION_EXPIRES_AT)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.token").isNotEmpty())
                .andExpect(jsonPath("$.invitation.status").value("PENDING"))
                .andExpect(jsonPath("$.invitation.expiresAt").value(REISSUED_INVITATION_EXPIRES_AT))
                .andReturn();
        var newToken = com.jayway.jsonpath.JsonPath.read(
                reissued.getResponse().getContentAsString(),
                "$.token"
        ).toString();

        mockMvc.perform(post("/api/projects/demo/identity/invitations/{token}/accept", newToken)
                        .header(RequestActorResolver.CURRENT_USER_HEADER, "user-dev"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.userId").value("user-dev"));

        mockMvc.perform(post("/api/projects/demo/identity/invitations")
                        .header(RequestActorResolver.CURRENT_USER_HEADER, "user-owner")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"userId":"user-expired","displayName":"过期成员","roleKey":"TESTER","expiresAt":"%s"}
                                """.formatted(EXPIRED_INVITATION_EXPIRES_AT)))
                .andExpect(status().isOk());

        mockMvc.perform(post("/api/projects/demo/identity/invitations:expire")
                        .header(RequestActorResolver.CURRENT_USER_HEADER, "user-owner"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].inviteeUserId").value("user-expired"))
                .andExpect(jsonPath("$[0].status").value("EXPIRED"));
    }

    @Test
    void 普通成员不能创建邀请或批量治理成员() throws Exception {
        repository.ensureMember(member("demo", "user-dev", "DEVELOPER"));
        repository.ensureMember(member("demo", "user-tester", "TESTER"));

        mockMvc.perform(post("/api/projects/demo/identity/invitations")
                        .header(RequestActorResolver.CURRENT_USER_HEADER, "user-dev")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"userId":"user-new","displayName":"新成员","roleKey":"TESTER","expiresAt":"%s"}
                                """.formatted(VALID_INVITATION_EXPIRES_AT)))
                .andExpect(status().isForbidden());

        mockMvc.perform(patch("/api/projects/demo/identity/members:batch")
                        .header(RequestActorResolver.CURRENT_USER_HEADER, "user-dev")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"updates":[{"userId":"user-tester","status":"DISABLED"}]}
                                """))
                .andExpect(status().isForbidden());
    }

    @Test
    void 普通成员不能撤销重发或清理项目邀请() throws Exception {
        repository.ensureMember(member("demo", "user-owner", "OWNER"));
        repository.ensureMember(member("demo", "user-dev", "DEVELOPER"));
        var invitation = new ProjectInvitation(
                "invitation-1",
                "demo",
                "user-tester",
                "测试同学",
                "TESTER",
                "PENDING",
                "user-owner",
                Instant.parse(VALID_INVITATION_EXPIRES_AT),
                null,
                Instant.parse("2026-06-25T13:50:00Z"),
                Instant.parse("2026-06-25T13:50:00Z")
        );
        repository.saveInvitation(new StoredProjectInvitation(invitation, "token-hash"));

        mockMvc.perform(post("/api/projects/demo/identity/invitations/invitation-1/revoke")
                        .header(RequestActorResolver.CURRENT_USER_HEADER, "user-dev"))
                .andExpect(status().isForbidden());

        mockMvc.perform(post("/api/projects/demo/identity/invitations/invitation-1/reissue")
                        .header(RequestActorResolver.CURRENT_USER_HEADER, "user-dev")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"expiresAt":"%s"}
                                """.formatted(REISSUED_INVITATION_EXPIRES_AT)))
                .andExpect(status().isForbidden());

        mockMvc.perform(post("/api/projects/demo/identity/invitations:expire")
                        .header(RequestActorResolver.CURRENT_USER_HEADER, "user-dev"))
                .andExpect(status().isForbidden());
    }

    @Test
    void 项目管理者可以批量治理成员() throws Exception {
        repository.ensureMember(member("demo", "user-owner", "OWNER"));
        repository.ensureMember(member("demo", "user-dev", "DEVELOPER"));
        repository.ensureMember(member("demo", "user-tester", "TESTER"));

        mockMvc.perform(patch("/api/projects/demo/identity/members:batch")
                        .header(RequestActorResolver.CURRENT_USER_HEADER, "user-owner")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"updates":[
                                  {"userId":"user-dev","roleKey":"TESTER","status":"ACTIVE"},
                                  {"userId":"user-tester","status":"DISABLED"}
                                ]}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].userId").value("user-dev"))
                .andExpect(jsonPath("$[0].roleKey").value("TESTER"))
                .andExpect(jsonPath("$[1].userId").value("user-tester"))
                .andExpect(jsonPath("$[1].status").value("DISABLED"));
    }

    @Test
    void 禁用成员后成员本人不能继续读取项目成员列表() throws Exception {
        repository.ensureMember(member("demo", "user-owner", "OWNER"));
        repository.ensureMember(member("demo", "user-dev", "DEVELOPER"));

        mockMvc.perform(patch("/api/projects/demo/identity/members/user-dev")
                        .header(RequestActorResolver.CURRENT_USER_HEADER, "user-owner")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"status":"DISABLED"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("DISABLED"));

        mockMvc.perform(get("/api/projects/demo/identity/members")
                        .header(RequestActorResolver.CURRENT_USER_HEADER, "user-dev"))
                .andExpect(status().isForbidden());
    }

    @Test
    void 普通成员不能变更项目成员() throws Exception {
        repository.ensureMember(member("demo", "user-dev", "DEVELOPER"));
        repository.ensureMember(member("demo", "user-tester", "TESTER"));

        mockMvc.perform(patch("/api/projects/demo/identity/members/user-tester")
                        .header(RequestActorResolver.CURRENT_USER_HEADER, "user-dev")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"status":"DISABLED"}
                                """))
                .andExpect(status().isForbidden());
    }

    @Test
    void 不能移除最后一个项目管理者() throws Exception {
        repository.ensureMember(member("demo", "user-owner", "OWNER"));

        mockMvc.perform(patch("/api/projects/demo/identity/members/user-owner")
                        .header(RequestActorResolver.CURRENT_USER_HEADER, "user-owner")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"status":"REMOVED"}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("项目至少需要保留一个管理成员"));
    }

    @Test
    void 可以读取用户项目和用户级审计记录() throws Exception {
        repository.ensureMember(member("demo", "user-reviewer", "OWNER"));
        repository.auditRecords.add(new UserAuditRecord(
                "audit-1",
                "demo",
                "user-reviewer",
                "OWNER",
                "SHELL",
                "LOCAL_EXECUTION_TASK",
                "task-1",
                "ALLOW",
                "允许执行 git status",
                Instant.parse("2026-06-25T14:00:00Z")
        ));

        mockMvc.perform(get("/api/projects/demo/identity/users/user-reviewer/projects")
                        .header(RequestActorResolver.CURRENT_USER_HEADER, "user-reviewer"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").value(contains("demo")));

        mockMvc.perform(get("/api/projects/demo/identity/users/user-reviewer/audit-records")
                        .header(RequestActorResolver.CURRENT_USER_HEADER, "user-reviewer"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].actorUserId").value("user-reviewer"))
                .andExpect(jsonPath("$[0].actionKey").value("SHELL"))
                .andExpect(jsonPath("$[0].summary").value(containsString("git status")));
    }

    @Test
    void 缺少登录身份时拒绝读取用户项目和用户级审计记录() throws Exception {
        repository.ensureMember(member("demo", "user-reviewer", "OWNER"));

        mockMvc.perform(get("/api/projects/demo/identity/users/user-reviewer/projects"))
                .andExpect(status().isUnauthorized());

        mockMvc.perform(get("/api/projects/demo/identity/users/user-reviewer/audit-records"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void 普通成员不能读取其他用户项目和用户级审计记录() throws Exception {
        repository.ensureMember(member("demo", "user-dev", "DEVELOPER"));
        repository.ensureMember(member("demo", "user-reviewer", "OWNER"));

        mockMvc.perform(get("/api/projects/demo/identity/users/user-reviewer/projects")
                        .header(RequestActorResolver.CURRENT_USER_HEADER, "user-dev"))
                .andExpect(status().isForbidden());

        mockMvc.perform(get("/api/projects/demo/identity/users/user-reviewer/audit-records")
                        .header(RequestActorResolver.CURRENT_USER_HEADER, "user-dev"))
                .andExpect(status().isForbidden());
    }

    @Test
    void 项目管理者可以读取其他用户项目和用户级审计记录() throws Exception {
        repository.ensureMember(member("demo", "user-owner", "OWNER"));
        repository.ensureMember(member("demo", "user-reviewer", "DEVELOPER"));
        repository.auditRecords.add(new UserAuditRecord(
                "audit-2",
                "demo",
                "user-reviewer",
                "DEVELOPER",
                "PATCH",
                "AGENT_RUN",
                "run-1",
                "ALLOW",
                "允许查看用户审计",
                Instant.parse("2026-06-25T15:00:00Z")
        ));

        mockMvc.perform(get("/api/projects/demo/identity/users/user-reviewer/projects")
                        .header(RequestActorResolver.CURRENT_USER_HEADER, "user-owner"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").value(contains("demo")));

        mockMvc.perform(get("/api/projects/demo/identity/users/user-reviewer/audit-records")
                        .header(RequestActorResolver.CURRENT_USER_HEADER, "user-owner"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].actorUserId").value("user-reviewer"))
                .andExpect(jsonPath("$[0].actionKey").value("PATCH"));
    }

    private ProjectMember member(String projectId, String userId, String roleKey) {
        var now = Instant.parse("2026-06-25T13:50:00Z");
        return new ProjectMember(projectId + ":" + userId + ":" + roleKey, projectId, userId, roleKey, "ACTIVE", now, now, now);
    }

    @TestConfiguration
    static class IdentityRepositoryTestConfig {
        @Bean
        FakeProjectIdentityRepository fakeProjectIdentityRepository() {
            return new FakeProjectIdentityRepository();
        }
    }

    static class FakeProjectIdentityRepository implements ProjectIdentityRepository {

        private final Map<String, MatrixUser> users = new LinkedHashMap<>();
        private final Map<String, StoredUserCredential> userCredentials = new LinkedHashMap<>();
        private final Map<String, ProjectMember> members = new LinkedHashMap<>();
        private final List<UserAuditRecord> auditRecords = new ArrayList<>();
        private final Map<String, StoredProjectInvitation> invitations = new LinkedHashMap<>();

        @Override
        public void ensureUser(MatrixUser user) {
            users.put(user.id(), user);
        }

        private void saveSuperAdmin(String userId) {
            var now = Instant.parse("2026-06-25T13:50:00Z");
            saveUserCredential(new StoredUserCredential(
                    new MatrixUser(userId, userId, "超级管理员", "", "ACTIVE", now, now),
                    "test-hash",
                    true,
                    now
            ));
        }

        @Override
        public Optional<StoredUserCredential> userCredentialByUsername(String username) {
            return userCredentials.values().stream()
                    .filter(credential -> credential.user().username().equals(username))
                    .findFirst();
        }

        @Override
        public Optional<StoredUserCredential> userCredentialById(String userId) {
            return Optional.ofNullable(userCredentials.get(userId));
        }

        @Override
        public void saveUserCredential(StoredUserCredential credential) {
            users.put(credential.user().id(), credential.user());
            userCredentials.put(credential.user().id(), credential);
        }

        @Override
        public void ensureProject(String projectId, String name, String ownerUserId, String currentStage) {
        }

        @Override
        public void ensureMember(ProjectMember member) {
            members.put(member.projectId() + ":" + member.userId() + ":" + member.roleKey(), member);
        }

        @Override
        public void replaceMember(ProjectMember member) {
            members.keySet().removeIf(key -> {
                var existing = members.get(key);
                return existing.projectId().equals(member.projectId()) && existing.userId().equals(member.userId());
            });
            ensureMember(member);
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
                    .sorted()
                    .toList();
        }

        @Override
        public List<UserAuditRecord> auditRecords(String projectId, String userId) {
            return auditRecords.stream()
                    .filter(record -> record.projectId().equals(projectId))
                    .filter(record -> record.actorUserId().equals(userId))
                    .toList();
        }

        @Override
        public void recordAudit(UserAuditRecord record) {
            auditRecords.add(record);
        }

        @Override
        public void saveInvitation(StoredProjectInvitation invitation) {
            invitations.put(invitation.invitation().id(), invitation);
        }

        @Override
        public void replaceInvitation(ProjectInvitation invitation) {
            var current = invitations.get(invitation.id());
            invitations.put(invitation.id(), new StoredProjectInvitation(invitation, current.tokenHash()));
        }

        @Override
        public void replaceInvitation(StoredProjectInvitation invitation) {
            invitations.put(invitation.invitation().id(), invitation);
        }

        @Override
        public List<ProjectInvitation> invitations(String projectId) {
            return invitations.values().stream()
                    .map(StoredProjectInvitation::invitation)
                    .filter(invitation -> invitation.projectId().equals(projectId))
                    .toList();
        }

        @Override
        public Optional<ProjectInvitation> invitation(String projectId, String invitationId) {
            return invitations.values().stream()
                    .map(StoredProjectInvitation::invitation)
                    .filter(invitation -> invitation.projectId().equals(projectId))
                    .filter(invitation -> invitation.id().equals(invitationId))
                    .findFirst();
        }

        @Override
        public Optional<StoredProjectInvitation> invitationByTokenHash(String tokenHash) {
            return invitations.values().stream()
                    .filter(invitation -> invitation.tokenHash().equals(tokenHash))
                    .findFirst();
        }

        @Override
        public List<ProjectInvitation> expiredPendingInvitations(String projectId, Instant now) {
            return invitations.values().stream()
                    .map(StoredProjectInvitation::invitation)
                    .filter(invitation -> invitation.projectId().equals(projectId))
                    .filter(invitation -> "PENDING".equals(invitation.status()))
                    .filter(invitation -> invitation.expiresAt() != null && !invitation.expiresAt().isAfter(now))
                    .toList();
        }
    }
}
