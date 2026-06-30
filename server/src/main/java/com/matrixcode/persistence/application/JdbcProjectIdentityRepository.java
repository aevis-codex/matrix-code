package com.matrixcode.persistence.application;

import com.matrixcode.identity.application.ProjectIdentityRepository;
import com.matrixcode.identity.domain.MatrixUser;
import com.matrixcode.identity.domain.ProjectInvitation;
import com.matrixcode.identity.domain.ProjectMember;
import com.matrixcode.identity.domain.UserAuditRecord;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

public class JdbcProjectIdentityRepository implements ProjectIdentityRepository {

    private final PersistenceModeProperties properties;
    private final DatabaseMigrationService migrationService;
    private boolean migrated;

    public JdbcProjectIdentityRepository(
            PersistenceModeProperties properties,
            DatabaseMigrationService migrationService
    ) {
        this.properties = properties;
        this.migrationService = migrationService;
    }

    public JdbcProjectIdentityRepository(PersistenceModeProperties properties) {
        this.properties = properties;
        this.migrationService = null;
    }

    @Override
    public void ensureUser(MatrixUser user) {
        if (user == null) {
            throw new IllegalArgumentException("用户不能为空");
        }
        ensureSchema();
        try (var connection = connection()) {
            ensureUser(connection, user);
        } catch (SQLException exception) {
            throw new IllegalStateException("用户表写入失败：" + exception.getMessage(), exception);
        }
    }

    @Override
    public Optional<StoredUserCredential> userCredentialByUsername(String username) {
        ensureSchema();
        try (var connection = connection();
             var statement = connection.prepareStatement("""
                     select id, username, display_name, email, status, password_hash, super_admin,
                            password_updated_at, created_at, updated_at
                     from matrixcode_users
                     where username = ?
                     """)) {
            statement.setString(1, requireText(username, "用户名不能为空"));
            try (var resultSet = statement.executeQuery()) {
                if (!resultSet.next()) {
                    return Optional.empty();
                }
                return Optional.of(readUserCredential(resultSet));
            }
        } catch (SQLException exception) {
            throw new IllegalStateException("用户凭证按用户名读取失败：" + exception.getMessage(), exception);
        }
    }

    @Override
    public Optional<StoredUserCredential> userCredentialById(String userId) {
        ensureSchema();
        try (var connection = connection();
             var statement = connection.prepareStatement("""
                     select id, username, display_name, email, status, password_hash, super_admin,
                            password_updated_at, created_at, updated_at
                     from matrixcode_users
                     where id = ?
                     """)) {
            statement.setString(1, requireText(userId, "用户 ID 不能为空"));
            try (var resultSet = statement.executeQuery()) {
                if (!resultSet.next()) {
                    return Optional.empty();
                }
                return Optional.of(readUserCredential(resultSet));
            }
        } catch (SQLException exception) {
            throw new IllegalStateException("用户凭证按 ID 读取失败：" + exception.getMessage(), exception);
        }
    }

    @Override
    public void saveUserCredential(StoredUserCredential credential) {
        if (credential == null || credential.user() == null) {
            throw new IllegalArgumentException("用户凭证不能为空");
        }
        ensureSchema();
        try (var connection = connection()) {
            if (updateUserCredential(connection, credential) == 0) {
                insertUserCredential(connection, credential);
            }
        } catch (SQLException exception) {
            throw new IllegalStateException("用户凭证写入失败：" + exception.getMessage(), exception);
        }
    }

    @Override
    public void ensureProject(String projectId, String name, String ownerUserId, String currentStage) {
        ensureSchema();
        try (var connection = connection()) {
            ensureProject(connection, projectId, name, ownerUserId, currentStage);
        } catch (SQLException exception) {
            throw new IllegalStateException("项目表写入失败：" + exception.getMessage(), exception);
        }
    }

    @Override
    public void ensureMember(ProjectMember member) {
        if (member == null) {
            throw new IllegalArgumentException("项目成员不能为空");
        }
        ensureSchema();
        try (var connection = connection()) {
            connection.setAutoCommit(false);
            try {
                ensureUser(connection, defaultUser(member.userId()));
                ensureProjectExists(connection, member.projectId(), "身份成员");
                if (updateMember(connection, member) == 0) {
                    insertMember(connection, member);
                }
                connection.commit();
            } catch (SQLException | RuntimeException exception) {
                connection.rollback();
                throw exception;
            } finally {
                connection.setAutoCommit(true);
            }
        } catch (SQLException exception) {
            throw new IllegalStateException("项目成员表写入失败：" + exception.getMessage(), exception);
        }
    }

    /**
     * 保存同一项目、同一用户的当前成员状态。
     *
     * <p>成员角色变更时优先复用已有目标角色记录；没有目标角色时复用该用户当前记录。
     * 其它历史角色会标记为 REMOVED，并由成员列表查询隐藏。</p>
     */
    @Override
    public void replaceMember(ProjectMember member) {
        if (member == null) {
            throw new IllegalArgumentException("项目成员不能为空");
        }
        ensureSchema();
        try (var connection = connection()) {
            connection.setAutoCommit(false);
            try {
                ensureUser(connection, defaultUser(member.userId()));
                ensureProjectExists(connection, member.projectId(), "身份成员");
                if (updateMember(connection, member) == 0) {
                    var currentRole = currentMemberRole(connection, member.projectId(), member.userId());
                    if (currentRole == null || updateMemberRole(connection, member, currentRole) == 0) {
                        insertMember(connection, member);
                    }
                }
                markOtherMemberRolesRemoved(connection, member);
                connection.commit();
            } catch (SQLException | RuntimeException exception) {
                connection.rollback();
                throw exception;
            } finally {
                connection.setAutoCommit(true);
            }
        } catch (SQLException exception) {
            throw new IllegalStateException("项目成员表替换失败：" + exception.getMessage(), exception);
        }
    }

    @Override
    public List<ProjectMember> members(String projectId) {
        ensureSchema();
        var members = new ArrayList<ProjectMember>();
        try (var connection = connection();
             var statement = connection.prepareStatement("""
                     select id, project_id, user_id, role_key, status, joined_at, created_at, updated_at
                     from matrixcode_project_members
                     where project_id = ? and status <> 'REMOVED'
                     order by role_key, joined_at, id
                     """)) {
            statement.setString(1, requireText(projectId, "项目 ID 不能为空"));
            try (var resultSet = statement.executeQuery()) {
                while (resultSet.next()) {
                    members.add(readMember(resultSet));
                }
            }
            return List.copyOf(members);
        } catch (SQLException exception) {
            throw new IllegalStateException("项目成员表读取失败：" + exception.getMessage(), exception);
        }
    }

    @Override
    public List<String> projectsForUser(String userId) {
        ensureSchema();
        var projects = new ArrayList<String>();
        try (var connection = connection();
             var statement = connection.prepareStatement("""
                     select distinct project_id
                     from matrixcode_project_members
                     where user_id = ? and status = 'ACTIVE'
                     order by project_id
                     """)) {
            statement.setString(1, requireText(userId, "用户 ID 不能为空"));
            try (var resultSet = statement.executeQuery()) {
                while (resultSet.next()) {
                    projects.add(resultSet.getString("project_id"));
                }
            }
            return List.copyOf(projects);
        } catch (SQLException exception) {
            throw new IllegalStateException("用户项目关系读取失败：" + exception.getMessage(), exception);
        }
    }

    @Override
    public List<UserAuditRecord> auditRecords(String projectId, String userId) {
        ensureSchema();
        var records = new ArrayList<UserAuditRecord>();
        try (var connection = connection();
             var statement = connection.prepareStatement("""
                     select id, project_id, actor_user_id, actor_role, action_key, target_type,
                            target_id, decision, summary, occurred_at, created_at
                     from matrixcode_audit_records
                     where project_id = ? and actor_user_id = ?
                     order by coalesce(occurred_at, created_at), sort_order, id
                     """)) {
            statement.setString(1, requireText(projectId, "项目 ID 不能为空"));
            statement.setString(2, requireText(userId, "用户 ID 不能为空"));
            try (var resultSet = statement.executeQuery()) {
                while (resultSet.next()) {
                    records.add(readAuditRecord(resultSet));
                }
            }
            return List.copyOf(records);
        } catch (SQLException exception) {
            throw new IllegalStateException("用户级审计读取失败：" + exception.getMessage(), exception);
        }
    }

    /**
     * 将身份域审计事件写入统一审计表。
     *
     * <p>该方法只写入低敏摘要，不保存 token、bootstrap 凭证、模型密钥或完整请求体。</p>
     */
    @Override
    public void recordAudit(UserAuditRecord record) {
        if (record == null) {
            throw new IllegalArgumentException("审计记录不能为空");
        }
        ensureSchema();
        try (var connection = connection()) {
            connection.setAutoCommit(false);
            try {
                ensureUserExists(connection, record.actorUserId());
                ensureProjectExists(connection, record.projectId(), "身份审计");
                insertAuditRecord(connection, record);
                connection.commit();
            } catch (SQLException | RuntimeException exception) {
                connection.rollback();
                throw exception;
            } finally {
                connection.setAutoCommit(true);
            }
        } catch (SQLException exception) {
            throw new IllegalStateException("用户级审计写入失败：" + exception.getMessage(), exception);
        }
    }

    /**
     * 保存项目邀请和令牌哈希。
     *
     * <p>该方法不接收明文 token，调用方必须先完成哈希，避免数据库泄漏后可直接接受邀请。</p>
     */
    @Override
    public void saveInvitation(StoredProjectInvitation storedInvitation) {
        if (storedInvitation == null || storedInvitation.invitation() == null) {
            throw new IllegalArgumentException("项目邀请不能为空");
        }
        ensureSchema();
        try (var connection = connection()) {
            connection.setAutoCommit(false);
            try {
                var invitation = storedInvitation.invitation();
                ensureUser(connection, defaultUser(invitation.createdByUserId()));
                ensureUser(connection, new MatrixUser(
                        requireText(invitation.inviteeUserId(), "被邀请用户 ID 不能为空"),
                        invitation.inviteeUserId(),
                        textOr(invitation.displayName(), invitation.inviteeUserId()),
                        "",
                        "ACTIVE",
                        invitation.createdAt(),
                        invitation.updatedAt()
                ));
                ensureProjectExists(connection, invitation.projectId(), "成员邀请");
                if (updateInvitation(connection, storedInvitation) == 0) {
                    insertInvitation(connection, storedInvitation);
                }
                connection.commit();
            } catch (SQLException | RuntimeException exception) {
                connection.rollback();
                throw exception;
            } finally {
                connection.setAutoCommit(true);
            }
        } catch (SQLException exception) {
            throw new IllegalStateException("项目邀请写入失败：" + exception.getMessage(), exception);
        }
    }

    @Override
    public void replaceInvitation(ProjectInvitation invitation) {
        if (invitation == null) {
            throw new IllegalArgumentException("项目邀请不能为空");
        }
        ensureSchema();
        try (var connection = connection()) {
            if (updateInvitation(connection, invitation) == 0) {
                throw new IllegalStateException("项目邀请不存在");
            }
        } catch (SQLException exception) {
            throw new IllegalStateException("项目邀请更新失败：" + exception.getMessage(), exception);
        }
    }

    /**
     * 替换邀请主体并同步轮换 token_hash。
     *
     * <p>该方法用于邀请重发场景，只接收服务层生成的哈希值，不接收明文邀请令牌。</p>
     */
    @Override
    public void replaceInvitation(StoredProjectInvitation storedInvitation) {
        if (storedInvitation == null || storedInvitation.invitation() == null) {
            throw new IllegalArgumentException("项目邀请不能为空");
        }
        ensureSchema();
        try (var connection = connection()) {
            if (updateInvitation(connection, storedInvitation) == 0) {
                throw new IllegalStateException("项目邀请不存在");
            }
        } catch (SQLException exception) {
            throw new IllegalStateException("项目邀请令牌更新失败：" + exception.getMessage(), exception);
        }
    }

    @Override
    public List<ProjectInvitation> invitations(String projectId) {
        ensureSchema();
        var invitations = new ArrayList<ProjectInvitation>();
        try (var connection = connection();
             var statement = connection.prepareStatement("""
                     select id, project_id, invitee_user_id, display_name, role_key, status,
                            created_by_user_id, expires_at, accepted_at, created_at, updated_at
                     from matrixcode_project_invitations
                     where project_id = ?
                     order by created_at desc, id
                     """)) {
            statement.setString(1, requireText(projectId, "项目 ID 不能为空"));
            try (var resultSet = statement.executeQuery()) {
                while (resultSet.next()) {
                    invitations.add(readInvitation(resultSet));
                }
            }
            return List.copyOf(invitations);
        } catch (SQLException exception) {
            throw new IllegalStateException("项目邀请读取失败：" + exception.getMessage(), exception);
        }
    }

    @Override
    public Optional<ProjectInvitation> invitation(String projectId, String invitationId) {
        ensureSchema();
        try (var connection = connection();
             var statement = connection.prepareStatement("""
                     select id, project_id, invitee_user_id, display_name, role_key, status,
                            created_by_user_id, expires_at, accepted_at, created_at, updated_at
                     from matrixcode_project_invitations
                     where project_id = ? and id = ?
                     """)) {
            statement.setString(1, requireText(projectId, "项目 ID 不能为空"));
            statement.setString(2, requireText(invitationId, "项目邀请 ID 不能为空"));
            try (var resultSet = statement.executeQuery()) {
                if (!resultSet.next()) {
                    return Optional.empty();
                }
                return Optional.of(readInvitation(resultSet));
            }
        } catch (SQLException exception) {
            throw new IllegalStateException("项目邀请按 ID 读取失败：" + exception.getMessage(), exception);
        }
    }

    @Override
    public Optional<StoredProjectInvitation> invitationByTokenHash(String tokenHash) {
        ensureSchema();
        try (var connection = connection();
             var statement = connection.prepareStatement("""
                     select id, project_id, invitee_user_id, display_name, role_key, status,
                            created_by_user_id, expires_at, accepted_at, created_at, updated_at, token_hash
                     from matrixcode_project_invitations
                     where token_hash = ?
                     """)) {
            statement.setString(1, requireText(tokenHash, "邀请令牌哈希不能为空"));
            try (var resultSet = statement.executeQuery()) {
                if (!resultSet.next()) {
                    return Optional.empty();
                }
                return Optional.of(new StoredProjectInvitation(
                        readInvitation(resultSet),
                        resultSet.getString("token_hash")
                ));
            }
        } catch (SQLException exception) {
            throw new IllegalStateException("项目邀请按令牌读取失败：" + exception.getMessage(), exception);
        }
    }

    @Override
    public List<ProjectInvitation> expiredPendingInvitations(String projectId, Instant now) {
        ensureSchema();
        var invitations = new ArrayList<ProjectInvitation>();
        try (var connection = connection();
             var statement = connection.prepareStatement("""
                     select id, project_id, invitee_user_id, display_name, role_key, status,
                            created_by_user_id, expires_at, accepted_at, created_at, updated_at
                     from matrixcode_project_invitations
                     where project_id = ? and status = 'PENDING' and expires_at <= ?
                     order by expires_at, id
                     """)) {
            statement.setString(1, requireText(projectId, "项目 ID 不能为空"));
            statement.setTimestamp(2, timestamp(now));
            try (var resultSet = statement.executeQuery()) {
                while (resultSet.next()) {
                    invitations.add(readInvitation(resultSet));
                }
            }
            return List.copyOf(invitations);
        } catch (SQLException exception) {
            throw new IllegalStateException("过期项目邀请读取失败：" + exception.getMessage(), exception);
        }
    }

    private void ensureUser(Connection connection, MatrixUser user) throws SQLException {
        if (updateUser(connection, user) > 0) {
            return;
        }
        try (var statement = connection.prepareStatement("""
                insert into matrixcode_users
                    (id, username, display_name, email, status, created_at, updated_at)
                values (?, ?, ?, ?, ?, ?, ?)
                """)) {
            statement.setString(1, requireText(user.id(), "用户 ID 不能为空"));
            statement.setString(2, textOr(user.username(), user.id()));
            statement.setString(3, textOr(user.displayName(), user.id()));
            statement.setString(4, textOr(user.email(), ""));
            statement.setString(5, textOr(user.status(), "ACTIVE"));
            statement.setTimestamp(6, timestamp(user.createdAt()));
            statement.setTimestamp(7, timestamp(user.updatedAt()));
            statement.executeUpdate();
        }
    }

    private void ensureUserExists(Connection connection, String userId) throws SQLException {
        var normalizedUserId = requireText(userId, "用户 ID 不能为空");
        try (var statement = connection.prepareStatement("""
                select 1
                from matrixcode_users
                where id = ?
                """)) {
            statement.setString(1, normalizedUserId);
            try (var resultSet = statement.executeQuery()) {
                if (resultSet.next()) {
                    return;
                }
            }
        }
        ensureUser(connection, defaultUser(normalizedUserId));
    }

    private int updateUser(Connection connection, MatrixUser user) throws SQLException {
        try (var statement = connection.prepareStatement("""
                update matrixcode_users
                set username = ?, display_name = ?, email = ?, status = ?, updated_at = ?
                where id = ?
                """)) {
            statement.setString(1, textOr(user.username(), user.id()));
            statement.setString(2, textOr(user.displayName(), user.id()));
            statement.setString(3, textOr(user.email(), ""));
            statement.setString(4, textOr(user.status(), "ACTIVE"));
            statement.setTimestamp(5, timestamp(user.updatedAt()));
            statement.setString(6, requireText(user.id(), "用户 ID 不能为空"));
            return statement.executeUpdate();
        }
    }

    private int updateUserCredential(Connection connection, StoredUserCredential credential) throws SQLException {
        var user = credential.user();
        try (var statement = connection.prepareStatement("""
                update matrixcode_users
                set username = ?, display_name = ?, email = ?, status = ?, password_hash = ?,
                    super_admin = ?, password_updated_at = ?, updated_at = ?
                where id = ?
                """)) {
            statement.setString(1, textOr(user.username(), user.id()));
            statement.setString(2, textOr(user.displayName(), user.id()));
            statement.setString(3, textOr(user.email(), ""));
            statement.setString(4, textOr(user.status(), "ACTIVE"));
            statement.setString(5, requireText(credential.passwordHash(), "密码哈希不能为空"));
            statement.setBoolean(6, credential.superAdmin());
            statement.setTimestamp(7, timestamp(credential.passwordUpdatedAt()));
            statement.setTimestamp(8, timestamp(user.updatedAt()));
            statement.setString(9, requireText(user.id(), "用户 ID 不能为空"));
            return statement.executeUpdate();
        }
    }

    private void insertUserCredential(Connection connection, StoredUserCredential credential) throws SQLException {
        var user = credential.user();
        try (var statement = connection.prepareStatement("""
                insert into matrixcode_users
                    (id, username, display_name, email, status, password_hash, super_admin,
                     password_updated_at, created_at, updated_at)
                values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """)) {
            statement.setString(1, requireText(user.id(), "用户 ID 不能为空"));
            statement.setString(2, textOr(user.username(), user.id()));
            statement.setString(3, textOr(user.displayName(), user.id()));
            statement.setString(4, textOr(user.email(), ""));
            statement.setString(5, textOr(user.status(), "ACTIVE"));
            statement.setString(6, requireText(credential.passwordHash(), "密码哈希不能为空"));
            statement.setBoolean(7, credential.superAdmin());
            statement.setTimestamp(8, timestamp(credential.passwordUpdatedAt()));
            statement.setTimestamp(9, timestamp(user.createdAt()));
            statement.setTimestamp(10, timestamp(user.updatedAt()));
            statement.executeUpdate();
        }
    }

    private void ensureProject(
            Connection connection,
            String projectId,
            String name,
            String ownerUserId,
            String currentStage
    ) throws SQLException {
        projectId = requireText(projectId, "项目 ID 不能为空");
        ownerUserId = ownerUserId == null || ownerUserId.isBlank() ? null : ownerUserId.trim();
        if (ownerUserId != null) {
            ensureUser(connection, defaultUser(ownerUserId));
        }
        try (var statement = connection.prepareStatement("""
                update matrixcode_projects
                set name = ?, owner_user_id = coalesce(?, owner_user_id),
                    current_stage = ?, updated_at = CURRENT_TIMESTAMP
                where id = ?
                """)) {
            statement.setString(1, textOr(name, projectId));
            statement.setString(2, ownerUserId);
            statement.setString(3, textOr(currentStage, "身份成员"));
            statement.setString(4, projectId);
            if (statement.executeUpdate() > 0) {
                return;
            }
        }
        try (var statement = connection.prepareStatement("""
                insert into matrixcode_projects
                    (id, name, description, owner_user_id, status, current_stage, created_at, updated_at)
                values (?, ?, ?, ?, ?, ?, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
                """)) {
            statement.setString(1, projectId);
            statement.setString(2, textOr(name, projectId));
            statement.setString(3, "");
            statement.setString(4, ownerUserId);
            statement.setString(5, "ACTIVE");
            statement.setString(6, textOr(currentStage, "身份成员"));
            statement.executeUpdate();
        }
    }

    private void ensureProjectExists(Connection connection, String projectId, String currentStage) throws SQLException {
        projectId = requireText(projectId, "项目 ID 不能为空");
        try (var statement = connection.prepareStatement("""
                update matrixcode_projects
                set updated_at = CURRENT_TIMESTAMP
                where id = ?
                """)) {
            statement.setString(1, projectId);
            if (statement.executeUpdate() > 0) {
                return;
            }
        }
        ensureProject(connection, projectId, projectId, "", currentStage);
    }

    private int updateMember(Connection connection, ProjectMember member) throws SQLException {
        try (var statement = connection.prepareStatement("""
                update matrixcode_project_members
                set role_key = ?, status = ?, joined_at = ?, updated_at = ?
                where project_id = ? and user_id = ? and role_key = ?
                """)) {
            statement.setString(1, requireText(member.roleKey(), "成员角色不能为空"));
            statement.setString(2, textOr(member.status(), "ACTIVE"));
            statement.setTimestamp(3, timestamp(member.joinedAt()));
            statement.setTimestamp(4, timestamp(member.updatedAt()));
            statement.setString(5, requireText(member.projectId(), "项目 ID 不能为空"));
            statement.setString(6, requireText(member.userId(), "用户 ID 不能为空"));
            statement.setString(7, requireText(member.roleKey(), "成员角色不能为空"));
            return statement.executeUpdate();
        }
    }

    private void insertMember(Connection connection, ProjectMember member) throws SQLException {
        try (var statement = connection.prepareStatement("""
                insert into matrixcode_project_members
                    (id, project_id, user_id, role_key, status, joined_at, created_at, updated_at)
                values (?, ?, ?, ?, ?, ?, ?, ?)
                """)) {
            statement.setString(1, requireText(member.id(), "成员 ID 不能为空"));
            statement.setString(2, requireText(member.projectId(), "项目 ID 不能为空"));
            statement.setString(3, requireText(member.userId(), "用户 ID 不能为空"));
            statement.setString(4, requireText(member.roleKey(), "成员角色不能为空"));
            statement.setString(5, textOr(member.status(), "ACTIVE"));
            statement.setTimestamp(6, timestamp(member.joinedAt()));
            statement.setTimestamp(7, timestamp(member.createdAt()));
            statement.setTimestamp(8, timestamp(member.updatedAt()));
            statement.executeUpdate();
        }
    }

    private String currentMemberRole(Connection connection, String projectId, String userId) throws SQLException {
        try (var statement = connection.prepareStatement("""
                select role_key
                from matrixcode_project_members
                where project_id = ? and user_id = ?
                order by case when status = 'ACTIVE' then 0 else 1 end, updated_at desc, role_key
                limit 1
                """)) {
            statement.setString(1, requireText(projectId, "项目 ID 不能为空"));
            statement.setString(2, requireText(userId, "用户 ID 不能为空"));
            try (var resultSet = statement.executeQuery()) {
                return resultSet.next() ? resultSet.getString("role_key") : null;
            }
        }
    }

    private int updateMemberRole(Connection connection, ProjectMember member, String currentRole) throws SQLException {
        try (var statement = connection.prepareStatement("""
                update matrixcode_project_members
                set id = ?, role_key = ?, status = ?, joined_at = ?, updated_at = ?
                where project_id = ? and user_id = ? and role_key = ?
                """)) {
            statement.setString(1, requireText(member.id(), "成员 ID 不能为空"));
            statement.setString(2, requireText(member.roleKey(), "成员角色不能为空"));
            statement.setString(3, textOr(member.status(), "ACTIVE"));
            statement.setTimestamp(4, timestamp(member.joinedAt()));
            statement.setTimestamp(5, timestamp(member.updatedAt()));
            statement.setString(6, requireText(member.projectId(), "项目 ID 不能为空"));
            statement.setString(7, requireText(member.userId(), "用户 ID 不能为空"));
            statement.setString(8, requireText(currentRole, "当前成员角色不能为空"));
            return statement.executeUpdate();
        }
    }

    private void markOtherMemberRolesRemoved(Connection connection, ProjectMember member) throws SQLException {
        try (var statement = connection.prepareStatement("""
                update matrixcode_project_members
                set status = 'REMOVED', updated_at = ?
                where project_id = ? and user_id = ? and role_key <> ?
                """)) {
            statement.setTimestamp(1, timestamp(member.updatedAt()));
            statement.setString(2, requireText(member.projectId(), "项目 ID 不能为空"));
            statement.setString(3, requireText(member.userId(), "用户 ID 不能为空"));
            statement.setString(4, requireText(member.roleKey(), "成员角色不能为空"));
            statement.executeUpdate();
        }
    }

    private MatrixUser defaultUser(String userId) {
        var now = Instant.now();
        return new MatrixUser(
                requireText(userId, "用户 ID 不能为空"),
                userId.trim(),
                userId.trim(),
                "",
                "ACTIVE",
                now,
                now
        );
    }

    private ProjectMember readMember(ResultSet resultSet) throws SQLException {
        return new ProjectMember(
                resultSet.getString("id"),
                resultSet.getString("project_id"),
                resultSet.getString("user_id"),
                resultSet.getString("role_key"),
                resultSet.getString("status"),
                instant(resultSet.getTimestamp("joined_at")),
                instant(resultSet.getTimestamp("created_at")),
                instant(resultSet.getTimestamp("updated_at"))
        );
    }

    private StoredUserCredential readUserCredential(ResultSet resultSet) throws SQLException {
        var user = new MatrixUser(
                resultSet.getString("id"),
                resultSet.getString("username"),
                resultSet.getString("display_name"),
                resultSet.getString("email"),
                resultSet.getString("status"),
                instant(resultSet.getTimestamp("created_at")),
                instant(resultSet.getTimestamp("updated_at"))
        );
        return new StoredUserCredential(
                user,
                textOr(resultSet.getString("password_hash"), ""),
                resultSet.getBoolean("super_admin"),
                nullableInstant(resultSet.getTimestamp("password_updated_at"))
        );
    }

    private UserAuditRecord readAuditRecord(ResultSet resultSet) throws SQLException {
        var occurredAt = resultSet.getTimestamp("occurred_at");
        if (occurredAt == null) {
            occurredAt = resultSet.getTimestamp("created_at");
        }
        return new UserAuditRecord(
                resultSet.getString("id"),
                resultSet.getString("project_id"),
                resultSet.getString("actor_user_id"),
                resultSet.getString("actor_role"),
                resultSet.getString("action_key"),
                resultSet.getString("target_type"),
                resultSet.getString("target_id"),
                resultSet.getString("decision"),
                resultSet.getString("summary"),
                instant(occurredAt)
        );
    }

    private void insertAuditRecord(Connection connection, UserAuditRecord record) throws SQLException {
        try (var statement = connection.prepareStatement("""
                insert into matrixcode_audit_records
                    (id, project_id, actor_user_id, actor_role, action_key, target_type,
                     target_id, decision, summary, created_at, updated_at, occurred_at, sort_order)
                values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """)) {
            var occurredAt = timestamp(record.occurredAt());
            statement.setString(1, requireText(record.id(), "审计记录 ID 不能为空"));
            statement.setString(2, requireText(record.projectId(), "项目 ID 不能为空"));
            statement.setString(3, requireText(record.actorUserId(), "用户 ID 不能为空"));
            statement.setString(4, textOr(record.actorRole(), ""));
            statement.setString(5, requireText(record.actionKey(), "审计动作不能为空"));
            statement.setString(6, textOr(record.targetType(), "IDENTITY_SESSION"));
            statement.setString(7, textOr(record.targetId(), record.actorUserId()));
            statement.setString(8, textOr(record.decision(), "ALLOW"));
            statement.setString(9, textOr(record.summary(), ""));
            statement.setTimestamp(10, occurredAt);
            statement.setTimestamp(11, occurredAt);
            statement.setTimestamp(12, occurredAt);
            statement.setInt(13, 0);
            statement.executeUpdate();
        }
    }

    private int updateInvitation(Connection connection, StoredProjectInvitation storedInvitation) throws SQLException {
        var invitation = storedInvitation.invitation();
        try (var statement = connection.prepareStatement("""
                update matrixcode_project_invitations
                set invitee_user_id = ?, display_name = ?, role_key = ?, status = ?, token_hash = ?,
                    created_by_user_id = ?, expires_at = ?, accepted_at = ?, updated_at = ?
                where id = ?
                """)) {
            statement.setString(1, requireText(invitation.inviteeUserId(), "被邀请用户 ID 不能为空"));
            statement.setString(2, textOr(invitation.displayName(), invitation.inviteeUserId()));
            statement.setString(3, requireText(invitation.roleKey(), "邀请角色不能为空"));
            statement.setString(4, textOr(invitation.status(), "PENDING"));
            statement.setString(5, requireText(storedInvitation.tokenHash(), "邀请令牌哈希不能为空"));
            statement.setString(6, requireText(invitation.createdByUserId(), "邀请创建人不能为空"));
            statement.setTimestamp(7, timestamp(invitation.expiresAt()));
            statement.setTimestamp(8, nullableTimestamp(invitation.acceptedAt()));
            statement.setTimestamp(9, timestamp(invitation.updatedAt()));
            statement.setString(10, requireText(invitation.id(), "项目邀请 ID 不能为空"));
            return statement.executeUpdate();
        }
    }

    private int updateInvitation(Connection connection, ProjectInvitation invitation) throws SQLException {
        try (var statement = connection.prepareStatement("""
                update matrixcode_project_invitations
                set invitee_user_id = ?, display_name = ?, role_key = ?, status = ?,
                    created_by_user_id = ?, expires_at = ?, accepted_at = ?, updated_at = ?
                where id = ?
                """)) {
            statement.setString(1, requireText(invitation.inviteeUserId(), "被邀请用户 ID 不能为空"));
            statement.setString(2, textOr(invitation.displayName(), invitation.inviteeUserId()));
            statement.setString(3, requireText(invitation.roleKey(), "邀请角色不能为空"));
            statement.setString(4, textOr(invitation.status(), "PENDING"));
            statement.setString(5, requireText(invitation.createdByUserId(), "邀请创建人不能为空"));
            statement.setTimestamp(6, timestamp(invitation.expiresAt()));
            statement.setTimestamp(7, nullableTimestamp(invitation.acceptedAt()));
            statement.setTimestamp(8, timestamp(invitation.updatedAt()));
            statement.setString(9, requireText(invitation.id(), "项目邀请 ID 不能为空"));
            return statement.executeUpdate();
        }
    }

    private void insertInvitation(Connection connection, StoredProjectInvitation storedInvitation) throws SQLException {
        var invitation = storedInvitation.invitation();
        try (var statement = connection.prepareStatement("""
                insert into matrixcode_project_invitations
                    (id, project_id, invitee_user_id, display_name, role_key, status, token_hash,
                     created_by_user_id, expires_at, accepted_at, created_at, updated_at)
                values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """)) {
            statement.setString(1, requireText(invitation.id(), "项目邀请 ID 不能为空"));
            statement.setString(2, requireText(invitation.projectId(), "项目 ID 不能为空"));
            statement.setString(3, requireText(invitation.inviteeUserId(), "被邀请用户 ID 不能为空"));
            statement.setString(4, textOr(invitation.displayName(), invitation.inviteeUserId()));
            statement.setString(5, requireText(invitation.roleKey(), "邀请角色不能为空"));
            statement.setString(6, textOr(invitation.status(), "PENDING"));
            statement.setString(7, requireText(storedInvitation.tokenHash(), "邀请令牌哈希不能为空"));
            statement.setString(8, requireText(invitation.createdByUserId(), "邀请创建人不能为空"));
            statement.setTimestamp(9, timestamp(invitation.expiresAt()));
            statement.setTimestamp(10, nullableTimestamp(invitation.acceptedAt()));
            statement.setTimestamp(11, timestamp(invitation.createdAt()));
            statement.setTimestamp(12, timestamp(invitation.updatedAt()));
            statement.executeUpdate();
        }
    }

    private ProjectInvitation readInvitation(ResultSet resultSet) throws SQLException {
        return new ProjectInvitation(
                resultSet.getString("id"),
                resultSet.getString("project_id"),
                resultSet.getString("invitee_user_id"),
                resultSet.getString("display_name"),
                resultSet.getString("role_key"),
                resultSet.getString("status"),
                resultSet.getString("created_by_user_id"),
                instant(resultSet.getTimestamp("expires_at")),
                nullableInstant(resultSet.getTimestamp("accepted_at")),
                instant(resultSet.getTimestamp("created_at")),
                instant(resultSet.getTimestamp("updated_at"))
        );
    }

    private Connection connection() throws SQLException {
        var jdbc = properties.getJdbc();
        if (jdbc.getUrl().isBlank()) {
            throw new IllegalStateException("JDBC URL 不能为空");
        }
        return JdbcConnectionFactory.open(jdbc);
    }

    private synchronized void ensureSchema() {
        if (migrated || migrationService == null || !properties.getJdbc().isMigrateOnStartup()) {
            return;
        }
        migrationService.migrate();
        migrated = true;
    }

    private String requireText(String value, String message) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(message);
        }
        return value.trim();
    }

    private String textOr(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value.trim();
    }

    private Timestamp timestamp(Instant instant) {
        return Timestamp.from(instant == null ? Instant.EPOCH : instant);
    }

    private Timestamp nullableTimestamp(Instant instant) {
        return instant == null ? null : Timestamp.from(instant);
    }

    private Instant instant(Timestamp timestamp) {
        return timestamp == null ? Instant.EPOCH : timestamp.toInstant();
    }

    private Instant nullableInstant(Timestamp timestamp) {
        return timestamp == null ? null : timestamp.toInstant();
    }
}
