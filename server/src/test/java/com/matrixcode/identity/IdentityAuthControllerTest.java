package com.matrixcode.identity;

import com.matrixcode.common.api.RestApiExceptionHandler;
import com.matrixcode.identity.api.IdentityAuthController;
import com.matrixcode.identity.api.RequestActorResolver;
import com.matrixcode.identity.application.ActorSessionTerminator;
import com.matrixcode.identity.application.ActorTokenIssuer;
import com.matrixcode.identity.application.IssuedActorToken;
import com.matrixcode.identity.application.MatrixCodeAuthProperties;
import com.matrixcode.identity.application.PasswordHashingService;
import com.matrixcode.identity.application.ProjectIdentityRepository;
import com.matrixcode.identity.application.ProjectIdentityService;
import com.matrixcode.identity.application.SignedActorTokenService;
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

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class IdentityAuthControllerTest {

    private static final Clock FIXED_CLOCK =
            Clock.fixed(Instant.parse("2026-06-27T05:00:00Z"), ZoneOffset.UTC);

    @Test
    void 可以为项目成员签发身份令牌() throws Exception {
        var fixture = fixture();
        fixture.repository.ensureMember(member("demo", "user-dev", "DEVELOPER"));

        fixture.mockMvc.perform(post("/api/projects/demo/identity/auth/actor-token")
                        .header("X-MatrixCode-Bootstrap-Token", "bootstrap-token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"userId":"user-dev","ttlSeconds":300}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.userId").value("user-dev"))
                .andExpect(jsonPath("$.token").value("sa-token-user-dev"))
                .andExpect(jsonPath("$.expiresAt").value("2026-06-27T05:05:00Z"));

        assertThat(fixture.tokenIssuer.lastTtl).isEqualTo(Duration.ofSeconds(300));
    }

    @Test
    void 可以通过登录接口获取SaToken() throws Exception {
        var fixture = fixture();
        fixture.repository.saveLoginUser("user-dev", "user-dev", "secret", false);
        fixture.repository.ensureMember(member("demo", "user-dev", "DEVELOPER"));

        fixture.mockMvc.perform(post("/api/projects/demo/identity/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"username":"user-dev","password":"secret"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.userId").value("user-dev"))
                .andExpect(jsonPath("$.token").value("sa-token-user-dev"))
                .andExpect(jsonPath("$.expiresAt").value("2026-06-27T05:10:00Z"));

        assertThat(fixture.tokenIssuer.lastTtl).isEqualTo(Duration.ofSeconds(600));

        assertThat(fixture.repository.auditRecords).extracting(UserAuditRecord::actionKey)
                .containsExactly("IDENTITY_LOGIN");
    }

    @Test
    void 错误密码不能登录() throws Exception {
        var fixture = fixture();
        fixture.repository.saveLoginUser("user-dev", "user-dev", "secret", false);
        fixture.repository.ensureMember(member("demo", "user-dev", "DEVELOPER"));

        fixture.mockMvc.perform(post("/api/projects/demo/identity/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"username":"user-dev","password":"bad-secret"}
                                """))
                .andExpect(status().isUnauthorized());

        assertThat(fixture.tokenIssuer.lastTtl).isEqualTo(Duration.ZERO);
    }

    @Test
    void 强制SaToken模式下仍可通过密码登录() throws Exception {
        var fixture = fixture(true);
        fixture.repository.saveLoginUser("user-dev", "user-dev", "secret", false);
        fixture.repository.ensureMember(member("demo", "user-dev", "DEVELOPER"));

        fixture.mockMvc.perform(post("/api/projects/demo/identity/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"username":"user-dev","password":"secret"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.userId").value("user-dev"))
                .andExpect(jsonPath("$.token").value("sa-token-user-dev"))
                .andExpect(jsonPath("$.expiresAt").value("2026-06-27T05:10:00Z"));
    }

    @Test
    void 超级管理员无需项目成员身份也可以登录项目工作台() throws Exception {
        var fixture = fixture();
        fixture.repository.saveLoginUser("admin", "admin", "secret", true);

        fixture.mockMvc.perform(post("/api/projects/demo/identity/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"username":"admin","password":"secret"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.userId").value("admin"))
                .andExpect(jsonPath("$.token").value("sa-token-admin"));
    }

    @Test
    void 可以退出当前SaToken会话() throws Exception {
        var fixture = fixture();
        fixture.repository.ensureMember(member("demo", "user-dev", "DEVELOPER"));

        fixture.mockMvc.perform(post("/api/projects/demo/identity/auth/logout")
                        .header(RequestActorResolver.CURRENT_USER_HEADER, "user-dev"))
                .andExpect(status().isNoContent());

        assertThat(fixture.sessionTerminator.logoutCalled).isTrue();
        assertThat(fixture.repository.auditRecords).extracting(UserAuditRecord::actionKey)
                .containsExactly("IDENTITY_LOGOUT");
    }

    @Test
    void 可以读取当前SaToken会话() throws Exception {
        var fixture = fixture();
        fixture.repository.ensureMember(member("demo", "user-dev", "DEVELOPER"));

        fixture.mockMvc.perform(get("/api/projects/demo/identity/auth/session")
                        .header(RequestActorResolver.CURRENT_USER_HEADER, "user-dev"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.authenticated").value(true))
                .andExpect(jsonPath("$.userId").value("user-dev"));
    }

    @Test
    void 可以续期当前SaToken会话并记录审计() throws Exception {
        var fixture = fixture();
        fixture.repository.ensureMember(member("demo", "user-dev", "DEVELOPER"));

        fixture.mockMvc.perform(post("/api/projects/demo/identity/auth/session/renew")
                        .header(RequestActorResolver.CURRENT_USER_HEADER, "user-dev"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.tokenFingerprint").value("fp-user-dev"))
                .andExpect(jsonPath("$.timeoutSeconds").value(600));

        assertThat(fixture.sessionTerminator.lastRenewTtl).isEqualTo(Duration.ofSeconds(600));
        assertThat(fixture.repository.auditRecords).extracting(UserAuditRecord::actionKey)
                .containsExactly("IDENTITY_SESSION_RENEW");
    }

    @Test
    void 项目管理者可以查看并踢下线成员会话() throws Exception {
        var fixture = fixture();
        fixture.repository.ensureMember(member("demo", "user-owner", "OWNER"));
        fixture.repository.ensureMember(member("demo", "user-dev", "DEVELOPER"));

        fixture.mockMvc.perform(get("/api/projects/demo/identity/auth/users/user-dev/sessions")
                        .header(RequestActorResolver.CURRENT_USER_HEADER, "user-owner"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].tokenFingerprint").value("fp-user-dev"));

        fixture.mockMvc.perform(post("/api/projects/demo/identity/auth/users/user-dev/sessions/kickout")
                        .header(RequestActorResolver.CURRENT_USER_HEADER, "user-owner"))
                .andExpect(status().isNoContent());

        assertThat(fixture.sessionTerminator.kickedUserId).isEqualTo("user-dev");
        assertThat(fixture.repository.auditRecords).extracting(UserAuditRecord::actionKey)
                .containsExactly("IDENTITY_SESSION_KICKOUT");
    }

    @Test
    void 普通成员不能踢下线其他成员会话() throws Exception {
        var fixture = fixture();
        fixture.repository.ensureMember(member("demo", "user-dev", "DEVELOPER"));
        fixture.repository.ensureMember(member("demo", "user-tester", "TESTER"));

        fixture.mockMvc.perform(post("/api/projects/demo/identity/auth/users/user-tester/sessions/kickout")
                        .header(RequestActorResolver.CURRENT_USER_HEADER, "user-dev"))
                .andExpect(status().isForbidden());

        assertThat(fixture.sessionTerminator.kickedUserId).isNull();
    }

    @Test
    void 项目管理者不能治理非项目成员会话() throws Exception {
        var fixture = fixture();
        fixture.repository.ensureMember(member("demo", "user-owner", "OWNER"));

        fixture.mockMvc.perform(get("/api/projects/demo/identity/auth/users/user-outsider/sessions")
                        .header(RequestActorResolver.CURRENT_USER_HEADER, "user-owner"))
                .andExpect(status().isForbidden());

        fixture.mockMvc.perform(post("/api/projects/demo/identity/auth/users/user-outsider/sessions/kickout")
                        .header(RequestActorResolver.CURRENT_USER_HEADER, "user-owner"))
                .andExpect(status().isForbidden());

        assertThat(fixture.sessionTerminator.kickedUserId).isNull();
    }

    @Test
    void 强制SaToken模式下普通用户头不能读取会话() throws Exception {
        var fixture = fixture(true);

        fixture.mockMvc.perform(get("/api/projects/demo/identity/auth/session")
                        .header(RequestActorResolver.CURRENT_USER_HEADER, "user-dev"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void 强制SaToken模式下普通用户头不能退出会话() throws Exception {
        var fixture = fixture(true);

        fixture.mockMvc.perform(post("/api/projects/demo/identity/auth/logout")
                        .header(RequestActorResolver.CURRENT_USER_HEADER, "user-dev"))
                .andExpect(status().isUnauthorized());

        assertThat(fixture.sessionTerminator.logoutCalled).isFalse();
    }

    @Test
    void 缺少BootstrapToken时拒绝签发() throws Exception {
        var fixture = fixture();
        fixture.repository.ensureMember(member("demo", "user-dev", "DEVELOPER"));

        fixture.mockMvc.perform(post("/api/projects/demo/identity/auth/actor-token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"userId":"user-dev","ttlSeconds":300}
                                """))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void 空请求体返回明确参数错误() throws Exception {
        var fixture = fixture();
        fixture.repository.ensureMember(member("demo", "user-dev", "DEVELOPER"));

        fixture.mockMvc.perform(post("/api/projects/demo/identity/auth/actor-token")
                        .header("X-MatrixCode-Bootstrap-Token", "bootstrap-token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("用户编号不能为空"));
    }

    @Test
    void Null请求体返回格式错误() throws Exception {
        var fixture = fixture();
        fixture.repository.ensureMember(member("demo", "user-dev", "DEVELOPER"));

        fixture.mockMvc.perform(post("/api/projects/demo/identity/auth/actor-token")
                        .header("X-MatrixCode-Bootstrap-Token", "bootstrap-token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("null"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("请求内容格式不正确"));
    }

    @Test
    void 非项目成员不能签发身份令牌() throws Exception {
        var fixture = fixture();

        fixture.mockMvc.perform(post("/api/projects/demo/identity/auth/actor-token")
                        .header("X-MatrixCode-Bootstrap-Token", "bootstrap-token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"userId":"user-dev","ttlSeconds":300}
                                """))
                .andExpect(status().isForbidden());
    }

    private ControllerFixture fixture() {
        return fixture(false);
    }

    private ControllerFixture fixture(boolean requireSaToken) {
        var properties = new MatrixCodeAuthProperties();
        properties.setActorTokenSecret("test-signing-secret");
        properties.setBootstrapToken("bootstrap-token");
        properties.setDefaultTokenTtlSeconds(600);
        properties.setRequireSaToken(requireSaToken);
        var repository = new RecordingProjectIdentityRepository();
        var passwordHashingService = new PasswordHashingService();
        var identityService = new ProjectIdentityService(repository, FIXED_CLOCK, passwordHashingService);
        var tokenIssuer = new RecordingActorTokenIssuer(FIXED_CLOCK);
        var resolver = new RequestActorResolver(new SignedActorTokenService(properties, FIXED_CLOCK), properties);
        var sessionTerminator = new RecordingActorSessionTerminator();
        var mockMvc = MockMvcBuilders.standaloneSetup(
                        new IdentityAuthController(
                                properties,
                                identityService,
                                tokenIssuer,
                                resolver,
                                sessionTerminator,
                                new com.matrixcode.identity.application.ProjectMemberPermissionGuard(identityService)
                        )
                )
                .setControllerAdvice(new RestApiExceptionHandler())
                .build();
        return new ControllerFixture(mockMvc, repository, tokenIssuer, sessionTerminator);
    }

    private ProjectMember member(String projectId, String userId, String roleKey) {
        var now = FIXED_CLOCK.instant();
        return new ProjectMember(projectId + ":" + userId + ":" + roleKey, projectId, userId, roleKey, "ACTIVE", now, now, now);
    }

    private record ControllerFixture(
            org.springframework.test.web.servlet.MockMvc mockMvc,
            RecordingProjectIdentityRepository repository,
            RecordingActorTokenIssuer tokenIssuer,
            RecordingActorSessionTerminator sessionTerminator
    ) {
    }

    private static final class RecordingActorSessionTerminator implements ActorSessionTerminator {

        private boolean logoutCalled;
        private Duration lastRenewTtl = Duration.ZERO;
        private String kickedUserId;

        @Override
        public void logout() {
            logoutCalled = true;
        }

        @Override
        public com.matrixcode.identity.application.ActorSessionInfo renewCurrent(Duration ttl) {
            lastRenewTtl = ttl;
            return new com.matrixcode.identity.application.ActorSessionInfo(
                    "fp-user-dev",
                    "default",
                    "",
                    FIXED_CLOCK.instant(),
                    ttl.toSeconds()
            );
        }

        @Override
        public List<com.matrixcode.identity.application.ActorSessionInfo> sessions(String userId) {
            return List.of(new com.matrixcode.identity.application.ActorSessionInfo(
                    "fp-" + userId,
                    "default",
                    "",
                    FIXED_CLOCK.instant(),
                    600
            ));
        }

        @Override
        public void kickout(String userId) {
            kickedUserId = userId;
        }
    }

    private static final class RecordingActorTokenIssuer implements ActorTokenIssuer {

        private final Clock clock;
        private Duration lastTtl = Duration.ZERO;

        private RecordingActorTokenIssuer(Clock clock) {
            this.clock = clock;
        }

        @Override
        public IssuedActorToken issue(String userId, Duration ttl) {
            lastTtl = ttl;
            return new IssuedActorToken(userId, "sa-token-" + userId, clock.instant().plus(ttl));
        }
    }

    private static final class RecordingProjectIdentityRepository implements ProjectIdentityRepository {

        private final Map<String, MatrixUser> users = new LinkedHashMap<>();
        private final Map<String, ProjectIdentityRepository.StoredUserCredential> userCredentials = new LinkedHashMap<>();
        private final Map<String, ProjectMember> members = new LinkedHashMap<>();
        private final List<UserAuditRecord> auditRecords = new java.util.ArrayList<>();
        private final PasswordHashingService passwordHashingService = new PasswordHashingService();

        @Override
        public void ensureUser(MatrixUser user) {
            users.put(user.id(), user);
        }

        private void saveLoginUser(String userId, String username, String password, boolean superAdmin) {
            var now = FIXED_CLOCK.instant();
            saveUserCredential(new ProjectIdentityRepository.StoredUserCredential(
                    new MatrixUser(userId, username, userId, "", "ACTIVE", now, now),
                    passwordHashingService.hash(password),
                    superAdmin,
                    now
            ));
        }

        @Override
        public java.util.Optional<ProjectIdentityRepository.StoredUserCredential> userCredentialByUsername(String username) {
            return userCredentials.values().stream()
                    .filter(credential -> credential.user().username().equals(username))
                    .findFirst();
        }

        @Override
        public java.util.Optional<ProjectIdentityRepository.StoredUserCredential> userCredentialById(String userId) {
            return java.util.Optional.ofNullable(userCredentials.get(userId));
        }

        @Override
        public void saveUserCredential(ProjectIdentityRepository.StoredUserCredential credential) {
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
            return auditRecords.stream()
                    .filter(record -> record.projectId().equals(projectId))
                    .filter(record -> record.actorUserId().equals(userId))
                    .toList();
        }

        @Override
        public void recordAudit(UserAuditRecord record) {
            auditRecords.add(record);
        }
    }
}
