package com.matrixcode.identity;

import com.matrixcode.identity.api.ProjectRequestPermissionGuard;
import com.matrixcode.identity.api.RequestActorResolver;
import com.matrixcode.identity.application.ProjectIdentityRepository;
import com.matrixcode.identity.application.ProjectIdentityService;
import com.matrixcode.identity.application.ProjectMemberPermissionGuard;
import com.matrixcode.identity.domain.MatrixUser;
import com.matrixcode.identity.domain.ProjectMember;
import com.matrixcode.identity.domain.UserAuditRecord;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.server.ResponseStatusException;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;

import static com.matrixcode.identity.api.RequestActorResolver.CURRENT_USER_HEADER;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ProjectRequestPermissionGuardTest {

    private static final Clock FIXED_CLOCK =
            Clock.fixed(Instant.parse("2026-06-29T01:00:00Z"), ZoneOffset.UTC);

    @Test
    void 项目成员请求校验通过时返回当前用户() {
        var fixture = fixture();
        fixture.repository.ensureMember(member("demo", "user-dev", "DEVELOPER", "ACTIVE"));
        var request = request("user-dev");

        var actorId = fixture.guard.assertProjectMember(request, "demo");

        assertThat(actorId).isEqualTo("user-dev");
    }

    @Test
    void 缺少请求身份时项目成员校验返回401() {
        var fixture = fixture();

        assertThatThrownBy(() -> fixture.guard.assertProjectMember(new MockHttpServletRequest(), "demo"))
                .isInstanceOfSatisfying(ResponseStatusException.class, exception ->
                        assertThat(exception.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED));
    }

    @Test
    void 非项目成员请求校验返回403() {
        var fixture = fixture();
        fixture.repository.ensureMember(member("demo", "user-dev", "DEVELOPER", "ACTIVE"));

        assertThatThrownBy(() -> fixture.guard.assertProjectMember(request("user-outsider"), "demo"))
                .isInstanceOfSatisfying(ResponseStatusException.class, exception ->
                        assertThat(exception.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN));
    }

    @Test
    void 项目管理者请求校验通过时返回当前用户() {
        var fixture = fixture();
        fixture.repository.ensureMember(member("demo", "user-owner", "OWNER", "ACTIVE"));

        var actorId = fixture.guard.assertCanManageProject(request("user-owner"), "demo");

        assertThat(actorId).isEqualTo("user-owner");
    }

    @Test
    void 普通成员项目管理请求校验返回403() {
        var fixture = fixture();
        fixture.repository.ensureMember(member("demo", "user-dev", "DEVELOPER", "ACTIVE"));

        assertThatThrownBy(() -> fixture.guard.assertCanManageProject(request("user-dev"), "demo"))
                .isInstanceOfSatisfying(ResponseStatusException.class, exception ->
                        assertThat(exception.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN));
    }

    @Test
    void 操作者为空时返回400() {
        var fixture = fixture();

        assertThatThrownBy(() -> fixture.guard.assertActor(request("user-dev"), " "))
                .isInstanceOfSatisfying(ResponseStatusException.class, exception ->
                        assertThat(exception.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST));
    }

    @Test
    void 请求身份和操作者不一致时返回403() {
        var fixture = fixture();

        assertThatThrownBy(() -> fixture.guard.assertActor(request("user-dev"), "user-ops"))
                .isInstanceOfSatisfying(ResponseStatusException.class, exception ->
                        assertThat(exception.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN));
    }

    @Test
    void 请求身份和操作者一致时返回当前用户() {
        var fixture = fixture();

        var actorId = fixture.guard.assertActor(request("user-dev"), " user-dev ");

        assertThat(actorId).isEqualTo("user-dev");
    }

    @Test
    void 项目成员操作者一致性校验通过时返回当前用户() {
        var fixture = fixture();
        fixture.repository.ensureMember(member("demo", "user-ops", "OPERATIONS", "ACTIVE"));

        var actorId = fixture.guard.assertProjectMemberActor(request("user-ops"), "demo", " user-ops ");

        assertThat(actorId).isEqualTo("user-ops");
    }

    @Test
    void 项目成员操作者不一致时返回403() {
        var fixture = fixture();
        fixture.repository.ensureMember(member("demo", "user-ops", "OPERATIONS", "ACTIVE"));
        fixture.repository.ensureMember(member("demo", "user-reviewer", "TESTER", "ACTIVE"));

        assertThatThrownBy(() -> fixture.guard.assertProjectMemberActor(request("user-reviewer"), "demo", "user-ops"))
                .isInstanceOfSatisfying(ResponseStatusException.class, exception ->
                        assertThat(exception.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN));
    }

    @Test
    void 非项目成员操作者一致时仍返回403() {
        var fixture = fixture();

        assertThatThrownBy(() -> fixture.guard.assertProjectMemberActor(request("user-outsider"), "demo", "user-outsider"))
                .isInstanceOfSatisfying(ResponseStatusException.class, exception ->
                        assertThat(exception.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN));
    }

    private GuardFixture fixture() {
        var repository = new FakeProjectIdentityRepository();
        var identityService = new ProjectIdentityService(repository, FIXED_CLOCK);
        var permissionGuard = new ProjectMemberPermissionGuard(identityService);
        return new GuardFixture(new ProjectRequestPermissionGuard(new RequestActorResolver(), permissionGuard), repository);
    }

    private MockHttpServletRequest request(String actorId) {
        var request = new MockHttpServletRequest();
        request.addHeader(CURRENT_USER_HEADER, actorId);
        return request;
    }

    private ProjectMember member(String projectId, String userId, String roleKey, String status) {
        return new ProjectMember(
                projectId + ":" + userId + ":" + roleKey,
                projectId,
                userId,
                roleKey,
                status,
                FIXED_CLOCK.instant(),
                FIXED_CLOCK.instant(),
                FIXED_CLOCK.instant()
        );
    }

    private record GuardFixture(ProjectRequestPermissionGuard guard, FakeProjectIdentityRepository repository) {
    }

    private static class FakeProjectIdentityRepository implements ProjectIdentityRepository {
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
