package com.matrixcode.identity;

import com.matrixcode.identity.application.ProjectIdentityRepository;
import com.matrixcode.identity.application.ProjectIdentityService;
import com.matrixcode.identity.domain.MatrixUser;
import com.matrixcode.identity.domain.ProjectInvitation;
import com.matrixcode.identity.domain.ProjectMember;
import com.matrixcode.identity.domain.UserAuditRecord;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ProjectIdentityServiceTest {

    private static final Clock FIXED_CLOCK =
            Clock.fixed(Instant.parse("2026-06-29T06:00:00Z"), ZoneOffset.UTC);

    @Test
    void 空项目可以安全创建首个Owner() {
        var repository = new RecordingProjectIdentityRepository();
        var service = new ProjectIdentityService(repository, FIXED_CLOCK);

        var created = service.ensureProjectOwnerWhenNoMembers(
                " demo ",
                " MatrixCode Demo ",
                " user-owner ",
                "项目负责人",
                "真实库初始化"
        );

        assertThat(created).isTrue();
        assertThat(repository.projects).containsEntry("demo", new ProjectSnapshot("MatrixCode Demo", "user-owner", "真实库初始化"));
        assertThat(repository.users).containsKey("user-owner");
        assertThat(repository.members("demo"))
                .extracting(ProjectMember::userId, ProjectMember::roleKey, ProjectMember::status)
                .containsExactly(org.assertj.core.groups.Tuple.tuple("user-owner", "OWNER", "ACTIVE"));
    }

    @Test
    void 已有任何成员时不会覆盖项目成员() {
        var repository = new RecordingProjectIdentityRepository();
        repository.ensureMember(member("demo", "user-dev", "DEVELOPER"));
        var service = new ProjectIdentityService(repository, FIXED_CLOCK);

        var created = service.ensureProjectOwnerWhenNoMembers(
                "demo",
                "MatrixCode Demo",
                "user-owner",
                "项目负责人",
                "真实库初始化"
        );

        assertThat(created).isFalse();
        assertThat(repository.members("demo"))
                .extracting(ProjectMember::userId, ProjectMember::roleKey)
                .containsExactly(org.assertj.core.groups.Tuple.tuple("user-dev", "DEVELOPER"));
    }

    @Test
    void 可以创建项目邀请并由被邀请用户接受() {
        var repository = new RecordingProjectIdentityRepository();
        repository.ensureMember(member("demo", "user-owner", "OWNER"));
        var service = new ProjectIdentityService(repository, FIXED_CLOCK);

        var issued = service.createInvitation(
                " demo ",
                " user-dev ",
                "开发同学",
                " developer ",
                " user-owner ",
                FIXED_CLOCK.instant().plusSeconds(3600)
        );

        assertThat(issued.token()).isNotBlank();
        assertThat(issued.invitation())
                .extracting(ProjectInvitation::projectId, ProjectInvitation::inviteeUserId, ProjectInvitation::roleKey,
                        ProjectInvitation::status)
                .containsExactly("demo", "user-dev", "DEVELOPER", "PENDING");
        assertThat(repository.storedInvitations.values())
                .singleElement()
                .satisfies(stored -> assertThat(stored.tokenHash()).isNotEqualTo(issued.token()));

        var member = service.acceptInvitation(" demo ", issued.token(), " user-dev ");

        assertThat(member)
                .extracting(ProjectMember::projectId, ProjectMember::userId, ProjectMember::roleKey, ProjectMember::status)
                .containsExactly("demo", "user-dev", "DEVELOPER", "ACTIVE");
        assertThat(repository.invitations("demo"))
                .singleElement()
                .satisfies(invitation -> {
                    assertThat(invitation.status()).isEqualTo("ACCEPTED");
                    assertThat(invitation.acceptedAt()).isEqualTo(FIXED_CLOCK.instant());
                });
        assertThat(repository.auditRecords)
                .extracting(UserAuditRecord::actionKey)
                .contains("IDENTITY_INVITATION_CREATED", "IDENTITY_INVITATION_ACCEPTED");
    }

    @Test
    void 过期邀请和非被邀请用户不能接受() {
        var repository = new RecordingProjectIdentityRepository();
        repository.ensureMember(member("demo", "user-owner", "OWNER"));
        var service = new ProjectIdentityService(repository, FIXED_CLOCK);
        var expired = service.createInvitation(
                "demo",
                "user-dev",
                "开发同学",
                "DEVELOPER",
                "user-owner",
                FIXED_CLOCK.instant().minusSeconds(1)
        );
        var active = service.createInvitation(
                "demo",
                "user-tester",
                "测试同学",
                "TESTER",
                "user-owner",
                FIXED_CLOCK.instant().plusSeconds(3600)
        );

        assertThatThrownBy(() -> service.acceptInvitation("demo", expired.token(), "user-dev"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("项目邀请已过期");
        assertThatThrownBy(() -> service.acceptInvitation("demo", active.token(), "user-dev"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("项目邀请用户不匹配");
    }

    @Test
    void 可以撤销项目邀请并阻止原令牌继续接受() {
        var repository = new RecordingProjectIdentityRepository();
        repository.ensureMember(member("demo", "user-owner", "OWNER"));
        var service = new ProjectIdentityService(repository, FIXED_CLOCK);
        var issued = service.createInvitation(
                "demo",
                "user-dev",
                "开发同学",
                "DEVELOPER",
                "user-owner",
                FIXED_CLOCK.instant().plusSeconds(3600)
        );

        var revoked = service.revokeInvitation("demo", issued.invitation().id(), "user-owner");

        assertThat(revoked.status()).isEqualTo("REVOKED");
        assertThatThrownBy(() -> service.acceptInvitation("demo", issued.token(), "user-dev"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("项目邀请不可用");
        assertThat(repository.auditRecords)
                .extracting(UserAuditRecord::actionKey)
                .contains("IDENTITY_INVITATION_REVOKED");
    }

    @Test
    void 可以重发项目邀请并轮换令牌哈希() {
        var repository = new RecordingProjectIdentityRepository();
        repository.ensureMember(member("demo", "user-owner", "OWNER"));
        var service = new ProjectIdentityService(repository, FIXED_CLOCK);
        var issued = service.createInvitation(
                "demo",
                "user-dev",
                "开发同学",
                "DEVELOPER",
                "user-owner",
                FIXED_CLOCK.instant().plusSeconds(3600)
        );
        var oldHash = repository.storedInvitations.get(issued.invitation().id()).tokenHash();

        var reissued = service.reissueInvitation(
                "demo",
                issued.invitation().id(),
                "user-owner",
                FIXED_CLOCK.instant().plusSeconds(7200)
        );

        assertThat(reissued.token()).isNotEqualTo(issued.token());
        assertThat(reissued.invitation().status()).isEqualTo("PENDING");
        assertThat(reissued.invitation().expiresAt()).isEqualTo(FIXED_CLOCK.instant().plusSeconds(7200));
        assertThat(repository.storedInvitations.get(issued.invitation().id()).tokenHash()).isNotEqualTo(oldHash);
        assertThatThrownBy(() -> service.acceptInvitation("demo", issued.token(), "user-dev"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("项目邀请不存在");
        assertThat(service.acceptInvitation("demo", reissued.token(), "user-dev").status()).isEqualTo("ACTIVE");
        assertThat(repository.auditRecords)
                .extracting(UserAuditRecord::actionKey)
                .contains("IDENTITY_INVITATION_REISSUED");
    }

    @Test
    void 可以清理项目中过期的待处理邀请() {
        var repository = new RecordingProjectIdentityRepository();
        repository.ensureMember(member("demo", "user-owner", "OWNER"));
        var service = new ProjectIdentityService(repository, FIXED_CLOCK);
        var expired = service.createInvitation(
                "demo",
                "user-dev",
                "开发同学",
                "DEVELOPER",
                "user-owner",
                FIXED_CLOCK.instant().minusSeconds(1)
        );
        service.createInvitation(
                "demo",
                "user-tester",
                "测试同学",
                "TESTER",
                "user-owner",
                FIXED_CLOCK.instant().plusSeconds(3600)
        );

        var expiredInvitations = service.expirePendingInvitations("demo", "user-owner");

        assertThat(expiredInvitations)
                .singleElement()
                .satisfies(invitation -> {
                    assertThat(invitation.id()).isEqualTo(expired.invitation().id());
                    assertThat(invitation.status()).isEqualTo("EXPIRED");
                });
        assertThat(repository.invitations("demo"))
                .extracting(ProjectInvitation::status)
                .containsExactly("EXPIRED", "PENDING");
        assertThat(repository.auditRecords)
                .extracting(UserAuditRecord::actionKey)
                .contains("IDENTITY_INVITATION_EXPIRED");
    }

    @Test
    void 可以批量治理项目成员角色和状态() {
        var repository = new RecordingProjectIdentityRepository();
        repository.ensureMember(member("demo", "user-owner", "OWNER"));
        repository.ensureMember(member("demo", "user-dev", "DEVELOPER"));
        repository.ensureMember(member("demo", "user-tester", "TESTER"));
        var service = new ProjectIdentityService(repository, FIXED_CLOCK);

        var updated = service.updateMembers("demo", List.of(
                new ProjectIdentityService.ProjectMemberBatchUpdateCommand("user-dev", "TESTER", "ACTIVE"),
                new ProjectIdentityService.ProjectMemberBatchUpdateCommand("user-tester", null, "DISABLED")
        ));

        assertThat(updated)
                .extracting(ProjectMember::userId, ProjectMember::roleKey, ProjectMember::status)
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple("user-dev", "TESTER", "ACTIVE"),
                        org.assertj.core.groups.Tuple.tuple("user-tester", "TESTER", "DISABLED")
                );
        assertThat(repository.auditRecords)
                .extracting(UserAuditRecord::actionKey)
                .contains("IDENTITY_MEMBER_BATCH_UPDATED");
    }

    private static ProjectMember member(String projectId, String userId, String roleKey) {
        return new ProjectMember(
                projectId + ":" + userId + ":" + roleKey,
                projectId,
                userId,
                roleKey,
                "ACTIVE",
                FIXED_CLOCK.instant(),
                FIXED_CLOCK.instant(),
                FIXED_CLOCK.instant()
        );
    }

    private record ProjectSnapshot(String name, String ownerUserId, String currentStage) {
    }

    private static final class RecordingProjectIdentityRepository implements ProjectIdentityRepository {
        private final Map<String, MatrixUser> users = new LinkedHashMap<>();
        private final Map<String, ProjectSnapshot> projects = new LinkedHashMap<>();
        private final List<ProjectMember> members = new ArrayList<>();
        private final Map<String, ProjectIdentityRepository.StoredProjectInvitation> storedInvitations = new LinkedHashMap<>();
        private final List<UserAuditRecord> auditRecords = new ArrayList<>();

        @Override
        public void ensureUser(MatrixUser user) {
            users.put(user.id(), user);
        }

        @Override
        public void ensureProject(String projectId, String name, String ownerUserId, String currentStage) {
            projects.put(projectId, new ProjectSnapshot(name, ownerUserId, currentStage));
        }

        @Override
        public void ensureMember(ProjectMember member) {
            members.removeIf(existing -> existing.projectId().equals(member.projectId())
                    && existing.userId().equals(member.userId()));
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
            storedInvitations.put(invitation.invitation().id(), invitation);
        }

        @Override
        public void replaceInvitation(ProjectInvitation invitation) {
            var current = storedInvitations.get(invitation.id());
            storedInvitations.put(invitation.id(), new StoredProjectInvitation(invitation, current.tokenHash()));
        }

        @Override
        public void replaceInvitation(StoredProjectInvitation invitation) {
            storedInvitations.put(invitation.invitation().id(), invitation);
        }

        @Override
        public List<ProjectInvitation> invitations(String projectId) {
            return storedInvitations.values().stream()
                    .map(StoredProjectInvitation::invitation)
                    .filter(invitation -> invitation.projectId().equals(projectId))
                    .toList();
        }

        @Override
        public Optional<ProjectInvitation> invitation(String projectId, String invitationId) {
            return storedInvitations.values().stream()
                    .map(StoredProjectInvitation::invitation)
                    .filter(invitation -> invitation.projectId().equals(projectId))
                    .filter(invitation -> invitation.id().equals(invitationId))
                    .findFirst();
        }

        @Override
        public Optional<StoredProjectInvitation> invitationByTokenHash(String tokenHash) {
            return storedInvitations.values().stream()
                    .filter(invitation -> invitation.tokenHash().equals(tokenHash))
                    .findFirst();
        }

        @Override
        public List<ProjectInvitation> expiredPendingInvitations(String projectId, Instant now) {
            return storedInvitations.values().stream()
                    .map(StoredProjectInvitation::invitation)
                    .filter(invitation -> invitation.projectId().equals(projectId))
                    .filter(invitation -> "PENDING".equals(invitation.status()))
                    .filter(invitation -> invitation.expiresAt() != null && !invitation.expiresAt().isAfter(now))
                    .toList();
        }
    }
}
