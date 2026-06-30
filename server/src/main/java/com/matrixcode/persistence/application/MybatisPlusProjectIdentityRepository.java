package com.matrixcode.persistence.application;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.matrixcode.identity.application.ProjectIdentityRepository;
import com.matrixcode.identity.domain.MatrixUser;
import com.matrixcode.identity.domain.ProjectInvitation;
import com.matrixcode.identity.domain.ProjectMember;
import com.matrixcode.identity.domain.UserAuditRecord;
import com.matrixcode.persistence.mybatis.entity.MatrixProjectEntity;
import com.matrixcode.persistence.mybatis.entity.MatrixUserEntity;
import com.matrixcode.persistence.mybatis.entity.ProjectInvitationEntity;
import com.matrixcode.persistence.mybatis.entity.ProjectMemberEntity;
import com.matrixcode.persistence.mybatis.entity.UserAuditRecordEntity;
import com.matrixcode.persistence.mybatis.mapper.MatrixProjectMapper;
import com.matrixcode.persistence.mybatis.mapper.MatrixUserMapper;
import com.matrixcode.persistence.mybatis.mapper.ProjectInvitationMapper;
import com.matrixcode.persistence.mybatis.mapper.ProjectMemberMapper;
import com.matrixcode.persistence.mybatis.mapper.UserAuditRecordMapper;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;

@Repository
@ConditionalOnProperty(prefix = "matrixcode.persistence", name = "mode", havingValue = "jdbc")
public class MybatisPlusProjectIdentityRepository implements ProjectIdentityRepository {

    private final MatrixUserMapper userMapper;
    private final MatrixProjectMapper projectMapper;
    private final ProjectMemberMapper memberMapper;
    private final UserAuditRecordMapper auditMapper;
    private final ProjectInvitationMapper invitationMapper;

    public MybatisPlusProjectIdentityRepository(
            MatrixUserMapper userMapper,
            MatrixProjectMapper projectMapper,
            ProjectMemberMapper memberMapper,
            UserAuditRecordMapper auditMapper,
            ProjectInvitationMapper invitationMapper
    ) {
        this.userMapper = userMapper;
        this.projectMapper = projectMapper;
        this.memberMapper = memberMapper;
        this.auditMapper = auditMapper;
        this.invitationMapper = invitationMapper;
    }

    @Override
    @Transactional
    public void ensureUser(MatrixUser user) {
        if (user == null) {
            throw new IllegalArgumentException("用户不能为空");
        }
        upsertUser(user);
    }

    @Override
    public Optional<StoredUserCredential> userCredentialByUsername(String username) {
        var entity = userMapper.selectOne(new LambdaQueryWrapper<MatrixUserEntity>()
                .eq(MatrixUserEntity::getUsername, requireText(username, "用户名不能为空")));
        return Optional.ofNullable(entity).map(this::credentialFromEntity);
    }

    @Override
    public Optional<StoredUserCredential> userCredentialById(String userId) {
        return Optional.ofNullable(userMapper.selectById(requireText(userId, "用户 ID 不能为空")))
                .map(this::credentialFromEntity);
    }

    @Override
    @Transactional
    public void saveUserCredential(StoredUserCredential credential) {
        if (credential == null || credential.user() == null) {
            throw new IllegalArgumentException("用户凭证不能为空");
        }
        upsertUserCredential(credential);
    }

    @Override
    @Transactional
    public void ensureProject(String projectId, String name, String ownerUserId, String currentStage) {
        upsertProject(projectId, name, ownerUserId, currentStage);
    }

    @Override
    @Transactional
    public void ensureMember(ProjectMember member) {
        if (member == null) {
            throw new IllegalArgumentException("项目成员不能为空");
        }
        upsertUser(defaultUser(member.userId()));
        ensureProjectExists(member.projectId(), "身份成员");
        if (updateMember(member) == 0) {
            memberMapper.insert(ProjectMemberEntity.fromDomain(member));
        }
    }

    @Override
    @Transactional
    public void replaceMember(ProjectMember member) {
        if (member == null) {
            throw new IllegalArgumentException("项目成员不能为空");
        }
        upsertUser(defaultUser(member.userId()));
        ensureProjectExists(member.projectId(), "身份成员");
        if (updateMember(member) == 0) {
            var currentRole = currentMemberRole(member.projectId(), member.userId());
            if (currentRole.isEmpty() || updateMemberRole(member, currentRole.get()) == 0) {
                memberMapper.insert(ProjectMemberEntity.fromDomain(member));
            }
        }
        markOtherMemberRolesRemoved(member);
    }

    @Override
    public List<ProjectMember> members(String projectId) {
        return memberMapper.selectList(new LambdaQueryWrapper<ProjectMemberEntity>()
                        .eq(ProjectMemberEntity::getProjectId, requireText(projectId, "项目 ID 不能为空"))
                        .ne(ProjectMemberEntity::getStatus, "REMOVED")
                        .orderByAsc(ProjectMemberEntity::getRoleKey)
                        .orderByAsc(ProjectMemberEntity::getJoinedAt)
                        .orderByAsc(ProjectMemberEntity::getId))
                .stream()
                .map(ProjectMemberEntity::toDomain)
                .toList();
    }

    @Override
    public List<String> projectsForUser(String userId) {
        var projects = new LinkedHashSet<String>();
        memberMapper.selectList(new LambdaQueryWrapper<ProjectMemberEntity>()
                        .eq(ProjectMemberEntity::getUserId, requireText(userId, "用户 ID 不能为空"))
                        .eq(ProjectMemberEntity::getStatus, "ACTIVE")
                        .orderByAsc(ProjectMemberEntity::getProjectId))
                .forEach(member -> projects.add(member.getProjectId()));
        return List.copyOf(projects);
    }

    @Override
    public List<UserAuditRecord> auditRecords(String projectId, String userId) {
        return auditMapper.selectList(new LambdaQueryWrapper<UserAuditRecordEntity>()
                        .eq(UserAuditRecordEntity::getProjectId, requireText(projectId, "项目 ID 不能为空"))
                        .eq(UserAuditRecordEntity::getActorUserId, requireText(userId, "用户 ID 不能为空")))
                .stream()
                .sorted(Comparator.comparing(UserAuditRecordEntity::timelineAt)
                        .thenComparing(UserAuditRecordEntity::sortOrderOrZero)
                        .thenComparing(UserAuditRecordEntity::getId))
                .map(UserAuditRecordEntity::toDomain)
                .toList();
    }

    @Override
    @Transactional
    public void recordAudit(UserAuditRecord record) {
        if (record == null) {
            throw new IllegalArgumentException("审计记录不能为空");
        }
        ensureUserExists(record.actorUserId());
        ensureProjectExists(record.projectId(), "身份审计");
        auditMapper.insert(UserAuditRecordEntity.fromDomain(record));
    }

    @Override
    @Transactional
    public void saveInvitation(StoredProjectInvitation storedInvitation) {
        if (storedInvitation == null || storedInvitation.invitation() == null) {
            throw new IllegalArgumentException("项目邀请不能为空");
        }
        var invitation = storedInvitation.invitation();
        upsertUser(defaultUser(invitation.createdByUserId()));
        upsertUser(new MatrixUser(
                requireText(invitation.inviteeUserId(), "被邀请用户 ID 不能为空"),
                invitation.inviteeUserId(),
                textOr(invitation.displayName(), invitation.inviteeUserId()),
                "",
                "ACTIVE",
                timestampOrEpoch(invitation.createdAt()),
                timestampOrEpoch(invitation.updatedAt())
        ));
        ensureProjectExists(invitation.projectId(), "成员邀请");
        var entity = ProjectInvitationEntity.fromStored(storedInvitation);
        if (invitationMapper.updateById(entity) == 0) {
            invitationMapper.insert(entity);
        }
    }

    @Override
    public void replaceInvitation(ProjectInvitation invitation) {
        if (invitation == null) {
            throw new IllegalArgumentException("项目邀请不能为空");
        }
        if (invitationMapper.updateById(ProjectInvitationEntity.fromInvitation(invitation)) == 0) {
            throw new IllegalStateException("项目邀请不存在");
        }
    }

    @Override
    public void replaceInvitation(StoredProjectInvitation storedInvitation) {
        if (storedInvitation == null || storedInvitation.invitation() == null) {
            throw new IllegalArgumentException("项目邀请不能为空");
        }
        if (invitationMapper.updateById(ProjectInvitationEntity.fromStored(storedInvitation)) == 0) {
            throw new IllegalStateException("项目邀请不存在");
        }
    }

    @Override
    public List<ProjectInvitation> invitations(String projectId) {
        return invitationMapper.selectList(new LambdaQueryWrapper<ProjectInvitationEntity>()
                        .eq(ProjectInvitationEntity::getProjectId, requireText(projectId, "项目 ID 不能为空"))
                        .orderByDesc(ProjectInvitationEntity::getCreatedAt)
                        .orderByAsc(ProjectInvitationEntity::getId))
                .stream()
                .map(ProjectInvitationEntity::toDomain)
                .toList();
    }

    @Override
    public Optional<ProjectInvitation> invitation(String projectId, String invitationId) {
        var entity = invitationMapper.selectOne(new LambdaQueryWrapper<ProjectInvitationEntity>()
                .eq(ProjectInvitationEntity::getProjectId, requireText(projectId, "项目 ID 不能为空"))
                .eq(ProjectInvitationEntity::getId, requireText(invitationId, "项目邀请 ID 不能为空")));
        return Optional.ofNullable(entity).map(ProjectInvitationEntity::toDomain);
    }

    @Override
    public Optional<StoredProjectInvitation> invitationByTokenHash(String tokenHash) {
        var entity = invitationMapper.selectOne(new LambdaQueryWrapper<ProjectInvitationEntity>()
                .eq(ProjectInvitationEntity::getTokenHash, requireText(tokenHash, "邀请令牌哈希不能为空")));
        if (entity == null) {
            return Optional.empty();
        }
        return Optional.of(new StoredProjectInvitation(entity.toDomain(), entity.getTokenHash()));
    }

    @Override
    public List<ProjectInvitation> expiredPendingInvitations(String projectId, Instant now) {
        return invitationMapper.selectList(new LambdaQueryWrapper<ProjectInvitationEntity>()
                        .eq(ProjectInvitationEntity::getProjectId, requireText(projectId, "项目 ID 不能为空"))
                        .eq(ProjectInvitationEntity::getStatus, "PENDING")
                        .le(ProjectInvitationEntity::getExpiresAt, timestampOrEpoch(now))
                        .orderByAsc(ProjectInvitationEntity::getExpiresAt)
                        .orderByAsc(ProjectInvitationEntity::getId))
                .stream()
                .map(ProjectInvitationEntity::toDomain)
                .toList();
    }

    private void upsertUser(MatrixUser user) {
        var userId = requireText(user.id(), "用户 ID 不能为空");
        var updated = userMapper.update(null, new LambdaUpdateWrapper<MatrixUserEntity>()
                .set(MatrixUserEntity::getUsername, textOr(user.username(), userId))
                .set(MatrixUserEntity::getDisplayName, textOr(user.displayName(), userId))
                .set(MatrixUserEntity::getEmail, textOr(user.email(), ""))
                .set(MatrixUserEntity::getStatus, textOr(user.status(), "ACTIVE"))
                .set(MatrixUserEntity::getUpdatedAt, timestampOrEpoch(user.updatedAt()))
                .eq(MatrixUserEntity::getId, userId));
        if (updated == 0) {
            var entity = new MatrixUserEntity();
            entity.setId(userId);
            entity.setUsername(textOr(user.username(), userId));
            entity.setDisplayName(textOr(user.displayName(), userId));
            entity.setEmail(textOr(user.email(), ""));
            entity.setStatus(textOr(user.status(), "ACTIVE"));
            entity.setCreatedAt(timestampOrEpoch(user.createdAt()));
            entity.setUpdatedAt(timestampOrEpoch(user.updatedAt()));
            userMapper.insert(entity);
        }
    }

    private void upsertUserCredential(StoredUserCredential credential) {
        var user = credential.user();
        var userId = requireText(user.id(), "用户 ID 不能为空");
        var passwordUpdatedAt = timestampOrEpoch(credential.passwordUpdatedAt());
        var updated = userMapper.update(null, new LambdaUpdateWrapper<MatrixUserEntity>()
                .set(MatrixUserEntity::getUsername, textOr(user.username(), userId))
                .set(MatrixUserEntity::getDisplayName, textOr(user.displayName(), userId))
                .set(MatrixUserEntity::getEmail, textOr(user.email(), ""))
                .set(MatrixUserEntity::getStatus, textOr(user.status(), "ACTIVE"))
                .set(MatrixUserEntity::getPasswordHash, requireText(credential.passwordHash(), "密码哈希不能为空"))
                .set(MatrixUserEntity::getSuperAdmin, credential.superAdmin())
                .set(MatrixUserEntity::getPasswordUpdatedAt, passwordUpdatedAt)
                .set(MatrixUserEntity::getUpdatedAt, timestampOrEpoch(user.updatedAt()))
                .eq(MatrixUserEntity::getId, userId));
        if (updated == 0) {
            var entity = new MatrixUserEntity();
            entity.setId(userId);
            entity.setUsername(textOr(user.username(), userId));
            entity.setDisplayName(textOr(user.displayName(), userId));
            entity.setEmail(textOr(user.email(), ""));
            entity.setStatus(textOr(user.status(), "ACTIVE"));
            entity.setPasswordHash(requireText(credential.passwordHash(), "密码哈希不能为空"));
            entity.setSuperAdmin(credential.superAdmin());
            entity.setPasswordUpdatedAt(passwordUpdatedAt);
            entity.setCreatedAt(timestampOrEpoch(user.createdAt()));
            entity.setUpdatedAt(timestampOrEpoch(user.updatedAt()));
            userMapper.insert(entity);
        }
    }

    private StoredUserCredential credentialFromEntity(MatrixUserEntity entity) {
        var user = new MatrixUser(
                entity.getId(),
                entity.getUsername(),
                entity.getDisplayName(),
                entity.getEmail(),
                entity.getStatus(),
                timestampOrEpoch(entity.getCreatedAt()),
                timestampOrEpoch(entity.getUpdatedAt())
        );
        return new StoredUserCredential(
                user,
                textOr(entity.getPasswordHash(), ""),
                Boolean.TRUE.equals(entity.getSuperAdmin()),
                entity.getPasswordUpdatedAt()
        );
    }

    private void ensureUserExists(String userId) {
        var normalized = requireText(userId, "用户 ID 不能为空");
        if (userMapper.selectById(normalized) == null) {
            upsertUser(defaultUser(normalized));
        }
    }

    private void upsertProject(String projectId, String name, String ownerUserId, String currentStage) {
        var normalizedProjectId = requireText(projectId, "项目 ID 不能为空");
        var normalizedOwnerId = ownerUserId == null || ownerUserId.isBlank() ? null : ownerUserId.trim();
        if (normalizedOwnerId != null) {
            upsertUser(defaultUser(normalizedOwnerId));
        }
        var now = Instant.now();
        var update = new LambdaUpdateWrapper<MatrixProjectEntity>()
                .set(MatrixProjectEntity::getName, textOr(name, normalizedProjectId))
                .set(normalizedOwnerId != null, MatrixProjectEntity::getOwnerUserId, normalizedOwnerId)
                .set(MatrixProjectEntity::getCurrentStage, textOr(currentStage, "身份成员"))
                .set(MatrixProjectEntity::getUpdatedAt, now)
                .eq(MatrixProjectEntity::getId, normalizedProjectId);
        if (projectMapper.update(null, update) == 0) {
            var entity = new MatrixProjectEntity();
            entity.setId(normalizedProjectId);
            entity.setName(textOr(name, normalizedProjectId));
            entity.setDescription("");
            entity.setOwnerUserId(normalizedOwnerId);
            entity.setStatus("ACTIVE");
            entity.setCurrentStage(textOr(currentStage, "身份成员"));
            entity.setCreatedAt(now);
            entity.setUpdatedAt(now);
            projectMapper.insert(entity);
        }
    }

    private void ensureProjectExists(String projectId, String currentStage) {
        var normalizedProjectId = requireText(projectId, "项目 ID 不能为空");
        if (projectMapper.update(null, new LambdaUpdateWrapper<MatrixProjectEntity>()
                .set(MatrixProjectEntity::getUpdatedAt, Instant.now())
                .eq(MatrixProjectEntity::getId, normalizedProjectId)) == 0) {
            upsertProject(normalizedProjectId, normalizedProjectId, "", currentStage);
        }
    }

    private int updateMember(ProjectMember member) {
        return memberMapper.update(null, new LambdaUpdateWrapper<ProjectMemberEntity>()
                .set(ProjectMemberEntity::getRoleKey, requireText(member.roleKey(), "成员角色不能为空"))
                .set(ProjectMemberEntity::getStatus, textOr(member.status(), "ACTIVE"))
                .set(ProjectMemberEntity::getJoinedAt, timestampOrEpoch(member.joinedAt()))
                .set(ProjectMemberEntity::getUpdatedAt, timestampOrEpoch(member.updatedAt()))
                .eq(ProjectMemberEntity::getProjectId, requireText(member.projectId(), "项目 ID 不能为空"))
                .eq(ProjectMemberEntity::getUserId, requireText(member.userId(), "用户 ID 不能为空"))
                .eq(ProjectMemberEntity::getRoleKey, requireText(member.roleKey(), "成员角色不能为空")));
    }

    private Optional<String> currentMemberRole(String projectId, String userId) {
        return memberMapper.selectList(new LambdaQueryWrapper<ProjectMemberEntity>()
                        .eq(ProjectMemberEntity::getProjectId, requireText(projectId, "项目 ID 不能为空"))
                        .eq(ProjectMemberEntity::getUserId, requireText(userId, "用户 ID 不能为空")))
                .stream()
                .sorted(Comparator
                        .comparing((ProjectMemberEntity member) -> !"ACTIVE".equals(member.getStatus()))
                        .thenComparing(ProjectMemberEntity::getUpdatedAt, Comparator.nullsLast(Comparator.reverseOrder()))
                        .thenComparing(ProjectMemberEntity::getRoleKey))
                .findFirst()
                .map(ProjectMemberEntity::getRoleKey);
    }

    private int updateMemberRole(ProjectMember member, String currentRole) {
        return memberMapper.update(null, new LambdaUpdateWrapper<ProjectMemberEntity>()
                .set(ProjectMemberEntity::getId, requireText(member.id(), "成员 ID 不能为空"))
                .set(ProjectMemberEntity::getRoleKey, requireText(member.roleKey(), "成员角色不能为空"))
                .set(ProjectMemberEntity::getStatus, textOr(member.status(), "ACTIVE"))
                .set(ProjectMemberEntity::getJoinedAt, timestampOrEpoch(member.joinedAt()))
                .set(ProjectMemberEntity::getUpdatedAt, timestampOrEpoch(member.updatedAt()))
                .eq(ProjectMemberEntity::getProjectId, requireText(member.projectId(), "项目 ID 不能为空"))
                .eq(ProjectMemberEntity::getUserId, requireText(member.userId(), "用户 ID 不能为空"))
                .eq(ProjectMemberEntity::getRoleKey, requireText(currentRole, "当前成员角色不能为空")));
    }

    private void markOtherMemberRolesRemoved(ProjectMember member) {
        memberMapper.update(null, new LambdaUpdateWrapper<ProjectMemberEntity>()
                .set(ProjectMemberEntity::getStatus, "REMOVED")
                .set(ProjectMemberEntity::getUpdatedAt, timestampOrEpoch(member.updatedAt()))
                .eq(ProjectMemberEntity::getProjectId, requireText(member.projectId(), "项目 ID 不能为空"))
                .eq(ProjectMemberEntity::getUserId, requireText(member.userId(), "用户 ID 不能为空"))
                .ne(ProjectMemberEntity::getRoleKey, requireText(member.roleKey(), "成员角色不能为空")));
    }

    private MatrixUser defaultUser(String userId) {
        var normalized = requireText(userId, "用户 ID 不能为空");
        var now = Instant.now();
        return new MatrixUser(normalized, normalized, normalized, "", "ACTIVE", now, now);
    }

    private Instant timestampOrEpoch(Instant instant) {
        return instant == null ? Instant.EPOCH : instant;
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
}
