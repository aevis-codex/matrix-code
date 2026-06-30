package com.matrixcode.persistence;

import com.matrixcode.MatrixCodeServerApplication;
import com.matrixcode.identity.application.PasswordHashingService;
import com.matrixcode.identity.application.ProjectIdentityService;
import com.matrixcode.identity.application.ProjectIdentityRepository;
import com.matrixcode.identity.application.ProjectIdentityRepository.StoredProjectInvitation;
import com.matrixcode.identity.application.ProjectIdentityRepository.StoredUserCredential;
import com.matrixcode.identity.domain.MatrixUser;
import com.matrixcode.identity.domain.ProjectInvitation;
import com.matrixcode.identity.domain.ProjectMember;
import com.matrixcode.identity.domain.UserAuditRecord;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;

import javax.sql.DataSource;
import java.nio.file.Path;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class MybatisPlusProjectIdentityRepositoryTest {

    private static final Instant FIXED = Instant.parse("2026-06-29T18:30:00Z");

    @TempDir
    Path tempDir;

    @Test
    void Jdbc模式下项目身份仓储使用MybatisPlus并保持成员审计和邀请行为() throws Exception {
        var jdbcUrl = jdbcUrl("identity_mp_");

        try (var context = startJdbcContext(jdbcUrl)) {
            var repository = context.getBean(ProjectIdentityRepository.class);
            assertThat(repository.getClass().getName())
                    .contains("MybatisPlusProjectIdentityRepository");

            repository.ensureUser(new MatrixUser(
                    "user-owner",
                    "owner",
                    "Owner 同学",
                    "owner@example.com",
                    "ACTIVE",
                    FIXED,
                    FIXED
            ));
            repository.ensureProject("demo", "MatrixCode Demo", "user-owner", "身份成员");
            repository.ensureMember(member("member-owner", "user-owner", "OWNER", "ACTIVE", FIXED));
            repository.ensureMember(member("member-dev", "user-dev", "DEVELOPER", "ACTIVE", FIXED));

            repository.replaceMember(member("member-dev-tester", "user-dev", "TESTER", "ACTIVE", FIXED.plusSeconds(60)));
            repository.replaceMember(member("member-ops-disabled", "user-ops", "OPERATIONS", "DISABLED", FIXED.plusSeconds(90)));

            assertThat(repository.members("demo"))
                    .extracting(ProjectMember::userId)
                    .containsExactly("user-ops", "user-owner", "user-dev");
            assertThat(repository.members("demo").stream()
                    .filter(member -> member.userId().equals("user-dev"))
                    .map(ProjectMember::roleKey))
                    .containsExactly("TESTER");
            assertThat(repository.projectsForUser("user-dev")).containsExactly("demo");
            assertThat(repository.projectsForUser("user-ops")).isEmpty();

            repository.recordAudit(new UserAuditRecord(
                    "audit-login-1",
                    "demo",
                    "user-dev",
                    "TESTER",
                    "IDENTITY_LOGIN",
                    "IDENTITY_SESSION",
                    "user-dev",
                    "ALLOW",
                    "签发登录会话",
                    FIXED.plusSeconds(120)
            ));

            assertThat(repository.auditRecords("demo", "user-dev"))
                    .singleElement()
                    .satisfies(record -> {
                        assertThat(record.id()).isEqualTo("audit-login-1");
                        assertThat(record.actorRole()).isEqualTo("TESTER");
                        assertThat(record.occurredAt()).isEqualTo(FIXED.plusSeconds(120));
                    });

            var expiredInvitation = invitation(
                    "invitation-expired",
                    "user-new-dev",
                    "开发新人",
                    "DEVELOPER",
                    "PENDING",
                    FIXED.minusSeconds(1),
                    null,
                    FIXED
            );
            var activeInvitation = invitation(
                    "invitation-active",
                    "user-new-tester",
                    "测试新人",
                    "TESTER",
                    "PENDING",
                    FIXED.plusSeconds(3600),
                    null,
                    FIXED
            );
            repository.saveInvitation(new StoredProjectInvitation(expiredInvitation, "old-expired-hash"));
            repository.saveInvitation(new StoredProjectInvitation(activeInvitation, "active-hash"));
            repository.replaceInvitation(new StoredProjectInvitation(expiredInvitation, "new-expired-hash"));

            assertThat(repository.invitation("demo", "invitation-expired"))
                    .isPresent()
                    .get()
                    .satisfies(invitation -> assertThat(invitation.inviteeUserId()).isEqualTo("user-new-dev"));
            assertThat(repository.invitationByTokenHash("old-expired-hash")).isEmpty();
            assertThat(repository.invitationByTokenHash("new-expired-hash"))
                    .isPresent()
                    .get()
                    .satisfies(saved -> assertThat(saved.invitation().id()).isEqualTo("invitation-expired"));
            assertThat(repository.expiredPendingInvitations("demo", FIXED))
                    .singleElement()
                    .satisfies(invitation -> assertThat(invitation.id()).isEqualTo("invitation-expired"));

            assertThat(ownerUserId(context.getBean(DataSource.class), "demo")).isEqualTo("user-owner");
            assertThat(tableCount(context.getBean(DataSource.class), "matrixcode_users")).isEqualTo(5);
            assertThat(tableCount(context.getBean(DataSource.class), "matrixcode_project_members")).isEqualTo(3);
            assertThat(memberRoleCount(context.getBean(DataSource.class), "demo", "user-dev", "DEVELOPER")).isZero();
            assertThat(tableCount(context.getBean(DataSource.class), "matrixcode_project_invitations")).isEqualTo(2);
        }
    }

    @Test
    void Jdbc模式下MybatisPlus身份仓储支持用户密码凭证() {
        var jdbcUrl = jdbcUrl("identity_credential_mp_");

        try (var context = startJdbcContext(jdbcUrl)) {
            var repository = context.getBean(ProjectIdentityRepository.class);
            repository.saveUserCredential(new StoredUserCredential(
                    new MatrixUser("admin", "admin", "超级管理员", "", "ACTIVE", FIXED, FIXED),
                    "pbkdf2-hash",
                    true,
                    FIXED
            ));
            repository.ensureUser(new MatrixUser(
                    "admin",
                    "admin",
                    "系统管理员",
                    "",
                    "ACTIVE",
                    FIXED,
                    FIXED.plusSeconds(60)
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
    }

    @Test
    void Jdbc模式下身份服务修改密码后只把密码哈希保存到数据库() throws Exception {
        var jdbcUrl = jdbcUrl("identity_password_change_mp_");

        try (var context = startJdbcContext(jdbcUrl)) {
            var repository = context.getBean(ProjectIdentityRepository.class);
            var passwordHashing = context.getBean(PasswordHashingService.class);
            var identityService = context.getBean(ProjectIdentityService.class);
            repository.saveUserCredential(new StoredUserCredential(
                    new MatrixUser("user-dev", "user-dev", "开发同学", "", "ACTIVE", FIXED, FIXED),
                    passwordHashing.hash("old-secret"),
                    false,
                    FIXED
            ));

            assertThat(identityService.changePassword("user-dev", "old-secret", "new-secret")).isTrue();

            assertThat(identityService.authenticate("user-dev", "old-secret")).isEmpty();
            assertThat(identityService.authenticate("user-dev", "new-secret")).isPresent();
            assertThat(passwordHash(context.getBean(DataSource.class), "user-dev"))
                    .startsWith("pbkdf2_sha256$")
                    .doesNotContain("old-secret")
                    .doesNotContain("new-secret");
        }
    }

    @Test
    void 默认文件模式不创建数据源和项目身份仓储Bean() {
        try (var context = new SpringApplicationBuilder(MatrixCodeServerApplication.class)
                .web(WebApplicationType.NONE)
                .properties(commonProperties())
                .run("--matrixcode.persistence.mode=file")) {
            assertThat(context.getBeanNamesForType(DataSource.class)).isEmpty();
            assertThat(context.getBeanNamesForType(ProjectIdentityRepository.class)).isEmpty();
        }
    }

    private org.springframework.context.ConfigurableApplicationContext startJdbcContext(String jdbcUrl) {
        return new SpringApplicationBuilder(MatrixCodeServerApplication.class)
                .web(WebApplicationType.NONE)
                .properties(commonProperties())
                .run(
                        "--matrixcode.persistence.mode=jdbc",
                        "--matrixcode.persistence.jdbc.url=" + jdbcUrl,
                        "--matrixcode.persistence.jdbc.username=sa",
                        "--matrixcode.persistence.jdbc.password=",
                        "--matrixcode.persistence.jdbc.migrate-on-startup=true"
                );
    }

    private Map<String, Object> commonProperties() {
        return Map.of(
                "matrixcode.workbench-state.storage-path", tempDir.resolve("workbench-state.json").toString(),
                "matrixcode.local-execution.storage-path", tempDir.resolve("local-execution.json").toString(),
                "matrixcode.runtime-notifications.storage-path", tempDir.resolve("runtime-notifications.json").toString(),
                "spring.main.banner-mode", "off"
        );
    }

    private String jdbcUrl(String prefix) {
        return "jdbc:h2:mem:" + prefix + UUID.randomUUID().toString().replace("-", "")
                + ";MODE=MySQL;DATABASE_TO_LOWER=TRUE;CASE_INSENSITIVE_IDENTIFIERS=TRUE;DB_CLOSE_DELAY=-1";
    }

    private ProjectMember member(String id, String userId, String roleKey, String status, Instant updatedAt) {
        return new ProjectMember(
                id,
                "demo",
                userId,
                roleKey,
                status,
                FIXED,
                FIXED,
                updatedAt
        );
    }

    private ProjectInvitation invitation(
            String id,
            String inviteeUserId,
            String displayName,
            String roleKey,
            String status,
            Instant expiresAt,
            Instant acceptedAt,
            Instant updatedAt
    ) {
        return new ProjectInvitation(
                id,
                "demo",
                inviteeUserId,
                displayName,
                roleKey,
                status,
                "user-owner",
                expiresAt,
                acceptedAt,
                FIXED,
                updatedAt
        );
    }

    private String ownerUserId(DataSource dataSource, String projectId) throws SQLException {
        try (var connection = dataSource.getConnection();
             var statement = connection.prepareStatement("select owner_user_id from matrixcode_projects where id = ?")) {
            statement.setString(1, projectId);
            try (var resultSet = statement.executeQuery()) {
                resultSet.next();
                return resultSet.getString("owner_user_id");
            }
        }
    }

    private int tableCount(DataSource dataSource, String tableName) throws SQLException {
        try (var connection = dataSource.getConnection();
             var statement = connection.prepareStatement("select count(*) from " + tableName);
             var resultSet = statement.executeQuery()) {
            resultSet.next();
            return resultSet.getInt(1);
        }
    }

    private int memberRoleCount(DataSource dataSource, String projectId, String userId, String roleKey) throws SQLException {
        try (var connection = dataSource.getConnection();
             var statement = connection.prepareStatement("""
                     select count(*)
                     from matrixcode_project_members
                     where project_id = ? and user_id = ? and role_key = ?
                     """)) {
            statement.setString(1, projectId);
            statement.setString(2, userId);
            statement.setString(3, roleKey);
            try (var resultSet = statement.executeQuery()) {
                resultSet.next();
                return resultSet.getInt(1);
            }
        }
    }

    private String passwordHash(DataSource dataSource, String userId) throws SQLException {
        try (var connection = dataSource.getConnection();
             var statement = connection.prepareStatement("select password_hash from matrixcode_users where id = ?")) {
            statement.setString(1, userId);
            try (var resultSet = statement.executeQuery()) {
                resultSet.next();
                return resultSet.getString("password_hash");
            }
        }
    }
}
