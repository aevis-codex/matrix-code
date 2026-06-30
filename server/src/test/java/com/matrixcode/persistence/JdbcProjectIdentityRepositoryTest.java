package com.matrixcode.persistence;

import com.matrixcode.identity.domain.MatrixUser;
import com.matrixcode.identity.domain.ProjectInvitation;
import com.matrixcode.identity.domain.ProjectMember;
import com.matrixcode.identity.domain.UserAuditRecord;
import com.matrixcode.identity.application.ProjectIdentityRepository.StoredProjectInvitation;
import com.matrixcode.identity.application.ProjectIdentityRepository.StoredUserCredential;
import com.matrixcode.persistence.application.JdbcProjectIdentityRepository;
import com.matrixcode.persistence.application.PersistenceModeProperties;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;

import java.sql.DriverManager;
import java.sql.Timestamp;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

class JdbcProjectIdentityRepositoryTest {

    @Test
    void 保存用户成员并按项目和用户查询() throws Exception {
        var jdbcUrl = jdbcUrl("identity_membership_");
        migrate(jdbcUrl);
        var repository = new JdbcProjectIdentityRepository(properties(jdbcUrl));
        var fixed = Instant.parse("2026-06-25T13:00:00Z");

        repository.ensureUser(new MatrixUser(
                "user-dev",
                "dev",
                "开发同学",
                "dev@example.com",
                "ACTIVE",
                fixed,
                fixed
        ));
        repository.ensureProject("demo", "MatrixCode Demo", "user-dev", "身份成员");
        repository.ensureMember(new ProjectMember(
                "member-1",
                "demo",
                "user-dev",
                "DEVELOPER",
                "ACTIVE",
                fixed,
                fixed,
                fixed
        ));

        assertThat(repository.members("demo"))
                .extracting(ProjectMember::userId)
                .containsExactly("user-dev");
        assertThat(repository.projectsForUser("user-dev")).containsExactly("demo");
        assertThat(ownerUserId(jdbcUrl, "demo")).isEqualTo("user-dev");
        assertThat(projectName(jdbcUrl, "demo")).isEqualTo("MatrixCode Demo");
    }

    @Test
    void 可以保存并读取用户登录凭证且普通资料更新不清空密码() {
        var jdbcUrl = jdbcUrl("identity_user_credential_");
        migrate(jdbcUrl);
        var repository = new JdbcProjectIdentityRepository(properties(jdbcUrl));
        var fixed = Instant.parse("2026-06-25T13:00:00Z");

        repository.saveUserCredential(new StoredUserCredential(
                new MatrixUser("admin", "admin", "超级管理员", "", "ACTIVE", fixed, fixed),
                "pbkdf2-hash",
                true,
                fixed
        ));
        repository.ensureUser(new MatrixUser(
                "admin",
                "admin",
                "系统管理员",
                "",
                "ACTIVE",
                fixed,
                fixed.plusSeconds(60)
        ));

        assertThat(repository.userCredentialByUsername("admin"))
                .isPresent()
                .get()
                .satisfies(credential -> {
                    assertThat(credential.user().displayName()).isEqualTo("系统管理员");
                    assertThat(credential.passwordHash()).isEqualTo("pbkdf2-hash");
                    assertThat(credential.superAdmin()).isTrue();
                });
        assertThat(repository.userCredentialById("admin"))
                .isPresent()
                .get()
                .extracting(StoredUserCredential::passwordHash)
                .isEqualTo("pbkdf2-hash");
    }

    @Test
    void 替换成员角色时隐藏旧角色并按状态控制项目权限() {
        var jdbcUrl = jdbcUrl("identity_replace_member_");
        migrate(jdbcUrl);
        var repository = new JdbcProjectIdentityRepository(properties(jdbcUrl));
        var fixed = Instant.parse("2026-06-25T13:00:00Z");

        repository.ensureProject("demo", "MatrixCode Demo", "user-owner", "身份成员");
        repository.ensureMember(new ProjectMember("member-owner", "demo", "user-owner", "OWNER", "ACTIVE", fixed, fixed, fixed));
        repository.ensureMember(new ProjectMember("member-dev", "demo", "user-dev", "DEVELOPER", "ACTIVE", fixed, fixed, fixed));

        repository.replaceMember(new ProjectMember(
                "member-dev-tester",
                "demo",
                "user-dev",
                "TESTER",
                "ACTIVE",
                fixed,
                fixed,
                fixed.plusSeconds(60)
        ));

        assertThat(repository.members("demo").stream()
                .filter(member -> member.userId().equals("user-dev"))
                .map(ProjectMember::roleKey))
                .containsExactly("TESTER");
        assertThat(repository.projectsForUser("user-dev")).containsExactly("demo");

        repository.replaceMember(new ProjectMember(
                "member-dev-tester",
                "demo",
                "user-dev",
                "TESTER",
                "DISABLED",
                fixed,
                fixed,
                fixed.plusSeconds(120)
        ));

        assertThat(repository.members("demo").stream()
                .filter(member -> member.userId().equals("user-dev"))
                .map(ProjectMember::status))
                .containsExactly("DISABLED");
        assertThat(repository.projectsForUser("user-dev")).isEmpty();

        repository.replaceMember(new ProjectMember(
                "member-dev-tester",
                "demo",
                "user-dev",
                "TESTER",
                "REMOVED",
                fixed,
                fixed,
                fixed.plusSeconds(180)
        ));

        assertThat(repository.members("demo").stream()
                .noneMatch(member -> member.userId().equals("user-dev")))
                .isTrue();
    }

    @Test
    void 按项目和用户读取用户级审计记录() throws Exception {
        var jdbcUrl = jdbcUrl("identity_audit_");
        migrate(jdbcUrl);
        var repository = new JdbcProjectIdentityRepository(properties(jdbcUrl));
        var fixed = Instant.parse("2026-06-25T13:10:00Z");

        repository.ensureUser(new MatrixUser(
                "user-reviewer",
                "reviewer",
                "评审同学",
                "reviewer@example.com",
                "ACTIVE",
                fixed,
                fixed
        ));
        repository.ensureProject("demo", "MatrixCode Demo", "user-reviewer", "用户级审计");
        insertAudit(jdbcUrl, fixed);

        assertThat(repository.auditRecords("demo", "user-reviewer"))
                .singleElement()
                .satisfies(record -> {
                    assertThat(record).extracting(UserAuditRecord::id).isEqualTo("audit-1");
                    assertThat(record.actorUserId()).isEqualTo("user-reviewer");
                    assertThat(record.actionKey()).isEqualTo("SHELL");
                    assertThat(record.targetId()).isEqualTo("task-1");
                    assertThat(record.decision()).isEqualTo("ALLOW");
                    assertThat(record.occurredAt()).isEqualTo(fixed);
                });
    }

    @Test
    void 可以写入身份域用户级审计记录() throws Exception {
        var jdbcUrl = jdbcUrl("identity_record_audit_");
        migrate(jdbcUrl);
        var repository = new JdbcProjectIdentityRepository(properties(jdbcUrl));
        var fixed = Instant.parse("2026-06-25T13:20:00Z");

        repository.ensureUser(new MatrixUser(
                "user-dev",
                "dev",
                "开发同学",
                "dev@example.com",
                "ACTIVE",
                fixed,
                fixed
        ));
        repository.recordAudit(new UserAuditRecord(
                "audit-login-1",
                "demo",
                "user-dev",
                "DEVELOPER",
                "IDENTITY_LOGIN",
                "IDENTITY_SESSION",
                "user-dev",
                "ALLOW",
                "签发登录会话",
                fixed
        ));

        assertThat(repository.auditRecords("demo", "user-dev"))
                .singleElement()
                .satisfies(record -> {
                    assertThat(record.id()).isEqualTo("audit-login-1");
                    assertThat(record.actionKey()).isEqualTo("IDENTITY_LOGIN");
                    assertThat(record.targetType()).isEqualTo("IDENTITY_SESSION");
                    assertThat(record.occurredAt()).isEqualTo(fixed);
                });
        assertThat(userDisplayName(jdbcUrl, "user-dev")).isEqualTo("开发同学");
    }

    @Test
    void 可以持久化项目邀请并按Token哈希读取() {
        var jdbcUrl = jdbcUrl("identity_invitation_");
        migrate(jdbcUrl);
        var repository = new JdbcProjectIdentityRepository(properties(jdbcUrl));
        var fixed = Instant.parse("2026-06-29T08:00:00Z");

        repository.ensureProject("demo", "MatrixCode Demo", "user-owner", "成员邀请");
        var invitation = new ProjectInvitation(
                "invitation-1",
                "demo",
                "user-dev",
                "开发同学",
                "DEVELOPER",
                "PENDING",
                "user-owner",
                fixed.plusSeconds(3600),
                null,
                fixed,
                fixed
        );

        repository.saveInvitation(new StoredProjectInvitation(invitation, "sha256-token"));

        assertThat(repository.invitations("demo"))
                .singleElement()
                .satisfies(saved -> {
                    assertThat(saved.inviteeUserId()).isEqualTo("user-dev");
                    assertThat(saved.roleKey()).isEqualTo("DEVELOPER");
                    assertThat(saved.status()).isEqualTo("PENDING");
                });
        assertThat(repository.invitationByTokenHash("sha256-token"))
                .isPresent()
                .get()
                .satisfies(saved -> assertThat(saved.invitation().id()).isEqualTo("invitation-1"));

        repository.replaceInvitation(new ProjectInvitation(
                "invitation-1",
                "demo",
                "user-dev",
                "开发同学",
                "DEVELOPER",
                "ACCEPTED",
                "user-owner",
                fixed.plusSeconds(3600),
                fixed.plusSeconds(300),
                fixed,
                fixed.plusSeconds(300)
        ));

        assertThat(repository.invitationByTokenHash("sha256-token"))
                .isPresent()
                .get()
                .satisfies(saved -> {
                    assertThat(saved.invitation().status()).isEqualTo("ACCEPTED");
                    assertThat(saved.invitation().acceptedAt()).isEqualTo(fixed.plusSeconds(300));
                    assertThat(saved.tokenHash()).isEqualTo("sha256-token");
                });
    }

    @Test
    void 可以按ID读取邀请替换Token哈希并查询过期待处理邀请() {
        var jdbcUrl = jdbcUrl("identity_invitation_lifecycle_");
        migrate(jdbcUrl);
        var repository = new JdbcProjectIdentityRepository(properties(jdbcUrl));
        var fixed = Instant.parse("2026-06-29T08:00:00Z");

        repository.ensureProject("demo", "MatrixCode Demo", "user-owner", "成员邀请");
        var expiredInvitation = new ProjectInvitation(
                "invitation-expired",
                "demo",
                "user-dev",
                "开发同学",
                "DEVELOPER",
                "PENDING",
                "user-owner",
                fixed.minusSeconds(1),
                null,
                fixed,
                fixed
        );
        var activeInvitation = new ProjectInvitation(
                "invitation-active",
                "demo",
                "user-tester",
                "测试同学",
                "TESTER",
                "PENDING",
                "user-owner",
                fixed.plusSeconds(3600),
                null,
                fixed,
                fixed
        );

        repository.saveInvitation(new StoredProjectInvitation(expiredInvitation, "old-expired-hash"));
        repository.saveInvitation(new StoredProjectInvitation(activeInvitation, "active-hash"));
        repository.replaceInvitation(new StoredProjectInvitation(expiredInvitation, "new-expired-hash"));

        assertThat(repository.invitation("demo", "invitation-expired"))
                .isPresent()
                .get()
                .satisfies(invitation -> assertThat(invitation.inviteeUserId()).isEqualTo("user-dev"));
        assertThat(repository.invitationByTokenHash("old-expired-hash")).isEmpty();
        assertThat(repository.invitationByTokenHash("new-expired-hash")).isPresent();
        assertThat(repository.expiredPendingInvitations("demo", fixed))
                .singleElement()
                .satisfies(invitation -> assertThat(invitation.id()).isEqualTo("invitation-expired"));
    }

    private String jdbcUrl(String prefix) {
        return "jdbc:h2:mem:" + prefix + System.nanoTime()
                + ";MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1";
    }

    private void migrate(String jdbcUrl) {
        Flyway.configure()
                .dataSource(jdbcUrl, "sa", "")
                .locations("classpath:db/migration")
                .load()
                .migrate();
    }

    private PersistenceModeProperties properties(String jdbcUrl) {
        var properties = new PersistenceModeProperties();
        properties.setMode("jdbc");
        properties.getJdbc().setUrl(jdbcUrl);
        properties.getJdbc().setUsername("sa");
        properties.getJdbc().setPassword("");
        return properties;
    }

    private String ownerUserId(String jdbcUrl, String projectId) throws Exception {
        try (var connection = DriverManager.getConnection(jdbcUrl, "sa", "");
             var statement = connection.prepareStatement("select owner_user_id from matrixcode_projects where id = ?")) {
            statement.setString(1, projectId);
            try (var resultSet = statement.executeQuery()) {
                resultSet.next();
                return resultSet.getString("owner_user_id");
            }
        }
    }

    private String projectName(String jdbcUrl, String projectId) throws Exception {
        try (var connection = DriverManager.getConnection(jdbcUrl, "sa", "");
             var statement = connection.prepareStatement("select name from matrixcode_projects where id = ?")) {
            statement.setString(1, projectId);
            try (var resultSet = statement.executeQuery()) {
                resultSet.next();
                return resultSet.getString("name");
            }
        }
    }

    private String userDisplayName(String jdbcUrl, String userId) throws Exception {
        try (var connection = DriverManager.getConnection(jdbcUrl, "sa", "");
             var statement = connection.prepareStatement("select display_name from matrixcode_users where id = ?")) {
            statement.setString(1, userId);
            try (var resultSet = statement.executeQuery()) {
                resultSet.next();
                return resultSet.getString("display_name");
            }
        }
    }

    private void insertAudit(String jdbcUrl, Instant occurredAt) throws Exception {
        try (var connection = DriverManager.getConnection(jdbcUrl, "sa", "");
             var statement = connection.prepareStatement("""
                     insert into matrixcode_audit_records
                         (id, project_id, actor_user_id, actor_role, action_key, target_type, target_id,
                          decision, summary, created_at, updated_at, task_id, tool_type, workspace_path,
                          occurred_at, sort_order)
                     values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                     """)) {
            statement.setString(1, "audit-1");
            statement.setString(2, "demo");
            statement.setString(3, "user-reviewer");
            statement.setString(4, "REVIEWER");
            statement.setString(5, "SHELL");
            statement.setString(6, "LOCAL_EXECUTION_TASK");
            statement.setString(7, "task-1");
            statement.setString(8, "ALLOW");
            statement.setString(9, "允许执行 git status");
            statement.setTimestamp(10, Timestamp.from(occurredAt));
            statement.setTimestamp(11, Timestamp.from(occurredAt));
            statement.setString(12, "task-1");
            statement.setString(13, "SHELL");
            statement.setString(14, "/tmp/matrixcode");
            statement.setTimestamp(15, Timestamp.from(occurredAt));
            statement.setInt(16, 0);
            statement.executeUpdate();
        }
    }
}
