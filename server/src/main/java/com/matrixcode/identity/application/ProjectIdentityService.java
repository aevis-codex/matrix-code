package com.matrixcode.identity.application;

import com.matrixcode.identity.domain.MatrixUser;
import com.matrixcode.identity.domain.ProjectInvitation;
import com.matrixcode.identity.domain.ProjectMember;
import com.matrixcode.identity.domain.UserAuditRecord;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * 项目身份业务服务。
 *
 * <p>作用域：用户、项目成员、邀请、超级管理员和身份审计；场景：登录权限控制、
 * 配置中心用户治理、项目成员流转和审计记录写入。</p>
 */
@Service
public class ProjectIdentityService {

    private static final Set<String> MANAGEMENT_ROLE_KEYS = Set.of("OWNER", "ADMIN", "MAINTAINER");
    private static final Set<String> MEMBER_STATUS_KEYS = Set.of("ACTIVE", "DISABLED", "REMOVED");
    private static final String SUPER_ADMIN_USER_ID = "admin";
    private final ProjectIdentityRepository repository;
    private final Clock clock;
    private final PasswordHashingService passwordHashingService;
    private final SecureRandom secureRandom = new SecureRandom();

    @Autowired
    public ProjectIdentityService(
            ObjectProvider<ProjectIdentityRepository> repository,
            PasswordHashingService passwordHashingService
    ) {
        this(repository.getIfAvailable(), Clock.systemUTC(), passwordHashingService);
    }

    public ProjectIdentityService(ProjectIdentityRepository repository, Clock clock) {
        this(repository, clock, new PasswordHashingService());
    }

    public ProjectIdentityService(ProjectIdentityRepository repository, Clock clock, PasswordHashingService passwordHashingService) {
        this.repository = repository;
        this.clock = clock;
        this.passwordHashingService = passwordHashingService;
    }

    /**
     * 确保用户基础资料存在。
     *
     * <p>作用域：身份域内部；场景：添加成员、创建邀请或初始化项目负责人时补齐用户主记录。</p>
     */
    public MatrixUser ensureUser(String userId, String displayName) {
        var now = clock.instant();
        var user = new MatrixUser(
                requireText(userId, "用户 ID 不能为空"),
                requireText(userId, "用户名不能为空"),
                textOr(displayName, userId),
                "",
                "ACTIVE",
                now,
                now
        );
        if (repository != null) {
            repository.ensureUser(user);
        }
        return user;
    }

    /**
     * 初始化全局超级管理员 admin。
     *
     * <p>只有配置了初始密码时才会写入；如果 admin 已经存在可用密码且已是超级管理员，
     * 启动时不会覆盖现有密码，避免生产重启重置账号。</p>
     */
    public boolean ensureInitialSuperAdmin(String initialPassword) {
        if (repository == null || initialPassword == null || initialPassword.isBlank()) {
            return false;
        }
        var existing = repository.userCredentialByUsername(SUPER_ADMIN_USER_ID);
        if (existing.isPresent()
                && existing.get().superAdmin()
                && activeStatus(existing.get().user().status())
                && existing.get().passwordHash() != null
                && !existing.get().passwordHash().isBlank()) {
            return false;
        }
        var now = clock.instant();
        var admin = new MatrixUser(
                SUPER_ADMIN_USER_ID,
                SUPER_ADMIN_USER_ID,
                "超级管理员",
                "",
                "ACTIVE",
                existing.map(credential -> credential.user().createdAt()).orElse(now),
                now
        );
        repository.saveUserCredential(new ProjectIdentityRepository.StoredUserCredential(
                admin,
                passwordHashingService.hash(initialPassword),
                true,
                now
        ));
        return true;
    }

    /**
     * 使用用户名和密码认证用户。
     *
     * <p>认证只返回普通用户资料，不返回密码哈希；用户名不存在、账号禁用、密码错误
     * 都统一返回空结果，由 API 层转换为 401。</p>
     */
    public Optional<MatrixUser> authenticate(String username, String password) {
        if (repository == null) {
            return Optional.empty();
        }
        var normalizedUsername = requireText(username, "用户名不能为空");
        requireText(password, "密码不能为空");
        return repository.userCredentialByUsername(normalizedUsername)
                .filter(credential -> activeStatus(credential.user().status()))
                .filter(credential -> passwordHashingService.matches(password, credential.passwordHash()))
                .map(ProjectIdentityRepository.StoredUserCredential::user);
    }

    /**
     * 判断用户是否为全局超级管理员。
     *
     * <p>超级管理员用于系统级用户和权限治理，可以跨项目通过项目成员与管理权限守卫。</p>
     */
    public boolean isSuperAdmin(String userId) {
        if (repository == null || userId == null || userId.isBlank()) {
            return false;
        }
        return repository.userCredentialById(userId.trim())
                .filter(ProjectIdentityRepository.StoredUserCredential::superAdmin)
                .map(ProjectIdentityRepository.StoredUserCredential::user)
                .filter(user -> activeStatus(user.status()))
                .isPresent();
    }

    /**
     * 创建可密码登录的项目用户并授予项目角色。
     *
     * <p>该方法只保存密码派生哈希，并同步创建或替换当前项目成员角色；调用方负责
     * 先校验操作者是否为超级管理员。</p>
     */
    public ProjectMember createUserWithPassword(
            String projectId,
            String userId,
            String username,
            String displayName,
            String email,
            String password,
            String roleKey,
            String actorUserId
    ) {
        var normalizedProjectId = requireText(projectId, "项目 ID 不能为空");
        var normalizedUserId = requireText(userId, "用户 ID 不能为空");
        var normalizedRoleKey = normalizeRoleKey(roleKey);
        var normalizedUsername = username == null || username.isBlank() ? normalizedUserId : username.trim();
        requireText(password, "密码不能为空");
        var now = clock.instant();
        var user = new MatrixUser(
                normalizedUserId,
                normalizedUsername,
                textOr(displayName, normalizedUserId),
                textOr(email, ""),
                "ACTIVE",
                now,
                now
        );
        var member = new ProjectMember(
                memberId(normalizedProjectId, normalizedUserId, normalizedRoleKey),
                normalizedProjectId,
                normalizedUserId,
                normalizedRoleKey,
                "ACTIVE",
                now,
                now,
                now
        );
        if (repository != null) {
            repository.saveUserCredential(new ProjectIdentityRepository.StoredUserCredential(
                    user,
                    passwordHashingService.hash(password),
                    false,
                    now
            ));
            repository.ensureProject(normalizedProjectId, normalizedProjectId, "", "身份成员");
            repository.replaceMember(member);
            recordAudit(
                    normalizedProjectId,
                    requireText(actorUserId, "操作者用户 ID 不能为空"),
                    "IDENTITY_USER_CREATED",
                    "MATRIX_USER",
                    normalizedUserId,
                    "ALLOW",
                    "创建项目用户：" + normalizedUserId + " / " + normalizedRoleKey
            );
        }
        return member;
    }

    /**
     * 确保项目负责人和 OWNER 成员关系存在。
     *
     * <p>作用域：项目初始化；场景：测试、演示或 bootstrap 流程需要快速创建项目首个管理成员。</p>
     */
    public void ensureProjectOwner(String projectId, String ownerUserId, String displayName) {
        var owner = ensureUser(ownerUserId, displayName);
        if (repository != null) {
            repository.ensureProject(projectId, projectId, owner.id(), "身份成员");
            ensureMember(projectId, owner.id(), "OWNER", owner.displayName());
        }
    }

    /**
     * 为空项目创建首个 OWNER 成员。
     *
     * <p>该方法只用于真实库首次启动或新项目初始化。只要项目已经存在任意当前成员，
     * 方法就直接返回 {@code false}，不会覆盖角色、状态或项目负责人，避免生产环境
     * 重启时把用户治理结果重置。</p>
     */
    public boolean ensureProjectOwnerWhenNoMembers(
            String projectId,
            String projectName,
            String ownerUserId,
            String displayName,
            String currentStage
    ) {
        var normalizedProjectId = requireText(projectId, "项目 ID 不能为空");
        var normalizedOwnerUserId = requireText(ownerUserId, "首个负责人用户 ID 不能为空");
        if (repository == null || !repository.members(normalizedProjectId).isEmpty()) {
            return false;
        }
        var now = clock.instant();
        var owner = new MatrixUser(
                normalizedOwnerUserId,
                normalizedOwnerUserId,
                textOr(displayName, normalizedOwnerUserId),
                "",
                "ACTIVE",
                now,
                now
        );
        repository.ensureUser(owner);
        repository.ensureProject(
                normalizedProjectId,
                textOr(projectName, normalizedProjectId),
                owner.id(),
                textOr(currentStage, "真实库初始化")
        );
        repository.ensureMember(new ProjectMember(
                memberId(normalizedProjectId, owner.id(), "OWNER"),
                normalizedProjectId,
                owner.id(),
                "OWNER",
                "ACTIVE",
                now,
                now,
                now
        ));
        return true;
    }

    /**
     * 确保用户是当前项目 ACTIVE 成员。
     *
     * <p>作用域：项目成员治理；场景：项目管理角色添加已有用户或接受邀请后落地成员角色。</p>
     */
    public ProjectMember ensureMember(String projectId, String userId, String roleKey, String displayName) {
        projectId = requireText(projectId, "项目 ID 不能为空");
        userId = requireText(userId, "用户 ID 不能为空");
        roleKey = normalizeRoleKey(roleKey);
        ensureUser(userId, displayName);
        var now = clock.instant();
        var member = new ProjectMember(
                memberId(projectId, userId, roleKey),
                projectId,
                userId,
                roleKey,
                "ACTIVE",
                now,
                now,
                now
        );
        if (repository != null) {
            repository.ensureProject(projectId, projectId, "", "身份成员");
            repository.replaceMember(member);
        }
        return member;
    }

    /**
     * 变更项目成员的角色或状态。
     *
     * <p>该方法用于配置中心的成员治理入口。它只允许修改已存在成员，并保证项目始终至少
     * 保留一个 ACTIVE 管理成员，防止上线后误操作导致项目无人可管理。</p>
     */
    public ProjectMember updateMember(String projectId, String userId, String roleKey, String status) {
        var normalizedProjectId = requireText(projectId, "项目 ID 不能为空");
        var normalizedUserId = requireText(userId, "用户 ID 不能为空");
        var existingMembers = members(normalizedProjectId);
        var currentMember = existingMembers.stream()
                .filter(member -> member.userId().equals(normalizedUserId))
                .filter(this::activeMember)
                .findFirst()
                .or(() -> existingMembers.stream().filter(member -> member.userId().equals(normalizedUserId)).findFirst())
                .orElseThrow(() -> new IllegalArgumentException("项目成员不存在"));
        var nextRoleKey = roleKey == null || roleKey.isBlank() ? currentMember.roleKey() : normalizeRoleKey(roleKey);
        var nextStatus = status == null || status.isBlank() ? textOr(currentMember.status(), "ACTIVE") : normalizeStatus(status);
        assertRetainsManagementMember(existingMembers, normalizedUserId, nextRoleKey, nextStatus);
        var now = clock.instant();
        var updatedMember = new ProjectMember(
                memberId(normalizedProjectId, normalizedUserId, nextRoleKey),
                normalizedProjectId,
                normalizedUserId,
                nextRoleKey,
                nextStatus,
                currentMember.joinedAt() == null ? now : currentMember.joinedAt(),
                currentMember.createdAt() == null ? now : currentMember.createdAt(),
                now
        );
        if (repository != null) {
            repository.replaceMember(updatedMember);
        }
        return updatedMember;
    }

    /**
     * 批量调整项目成员治理状态。
     *
     * <p>该方法按输入顺序逐条调用单成员更新逻辑，因此仍复用“至少保留一个 ACTIVE 管理成员”的保护。
     * 批量完成后只写一条低敏审计摘要，避免审计表被批量操作刷屏。</p>
     */
    public List<ProjectMember> updateMembers(String projectId, List<ProjectMemberBatchUpdateCommand> updates) {
        return updateMembers(projectId, "SYSTEM", updates);
    }

    /**
     * 以指定操作者身份批量调整项目成员治理状态。
     */
    public List<ProjectMember> updateMembers(
            String projectId,
            String actorUserId,
            List<ProjectMemberBatchUpdateCommand> updates
    ) {
        var normalizedProjectId = requireText(projectId, "项目 ID 不能为空");
        var normalizedActorUserId = requireText(actorUserId, "操作者用户 ID 不能为空");
        if (updates == null || updates.isEmpty()) {
            throw new IllegalArgumentException("成员批量更新不能为空");
        }
        var existingUserIds = members(normalizedProjectId).stream()
                .map(ProjectMember::userId)
                .collect(java.util.stream.Collectors.toSet());
        for (var update : updates) {
            if (update == null) {
                throw new IllegalArgumentException("成员批量更新项不能为空");
            }
            if (!existingUserIds.contains(requireText(update.userId(), "成员用户 ID 不能为空"))) {
                throw new IllegalArgumentException("项目成员不存在");
            }
        }
        var updatedMembers = new ArrayList<ProjectMember>();
        for (var update : updates) {
            updatedMembers.add(updateMember(normalizedProjectId, update.userId(), update.roleKey(), update.status()));
        }
        recordAudit(
                normalizedProjectId,
                normalizedActorUserId,
                "IDENTITY_MEMBER_BATCH_UPDATED",
                "PROJECT_MEMBER",
                normalizedProjectId,
                "ALLOW",
                "批量更新项目成员：" + updatedMembers.size()
        );
        return List.copyOf(updatedMembers);
    }

    /**
     * 创建项目成员邀请并返回一次性明文令牌。
     *
     * <p>数据库只保存令牌的 SHA-256 哈希；明文令牌只随本次响应返回，由前端展示或发送给被邀请用户。</p>
     */
    public IssuedProjectInvitation createInvitation(
            String projectId,
            String inviteeUserId,
            String displayName,
            String roleKey,
            String createdByUserId,
            Instant expiresAt
    ) {
        var normalizedProjectId = requireText(projectId, "项目 ID 不能为空");
        var normalizedInviteeUserId = requireText(inviteeUserId, "被邀请用户 ID 不能为空");
        var normalizedCreatedByUserId = requireText(createdByUserId, "邀请创建人不能为空");
        var normalizedRoleKey = normalizeRoleKey(roleKey);
        var now = clock.instant();
        var token = newInvitationToken();
        var invitation = new ProjectInvitation(
                UUID.randomUUID().toString(),
                normalizedProjectId,
                normalizedInviteeUserId,
                textOr(displayName, normalizedInviteeUserId),
                normalizedRoleKey,
                "PENDING",
                normalizedCreatedByUserId,
                expiresAt == null ? now.plusSeconds(7 * 24 * 3600L) : expiresAt,
                null,
                now,
                now
        );
        ensureUser(normalizedInviteeUserId, invitation.displayName());
        if (repository != null) {
            repository.ensureProject(normalizedProjectId, normalizedProjectId, "", "成员邀请");
            repository.saveInvitation(new ProjectIdentityRepository.StoredProjectInvitation(invitation, tokenHash(token)));
        }
        recordAudit(
                normalizedProjectId,
                normalizedCreatedByUserId,
                "IDENTITY_INVITATION_CREATED",
                "PROJECT_INVITATION",
                invitation.id(),
                "ALLOW",
                "创建项目邀请：" + normalizedInviteeUserId + " / " + normalizedRoleKey
        );
        return new IssuedProjectInvitation(invitation, token);
    }

    /**
     * 接受项目邀请并创建 ACTIVE 项目成员。
     *
     * <p>接受邀请不要求用户已经是项目成员，但必须使用与邀请匹配的当前登录用户。
     * 已接受、撤销、过期或跨项目令牌都会被拒绝。</p>
     */
    public ProjectMember acceptInvitation(String projectId, String token, String currentUserId) {
        var normalizedProjectId = requireText(projectId, "项目 ID 不能为空");
        var normalizedCurrentUserId = requireText(currentUserId, "当前用户 ID 不能为空");
        if (repository == null) {
            throw new IllegalArgumentException("项目邀请不存在");
        }
        var stored = repository.invitationByTokenHash(tokenHash(requireText(token, "邀请令牌不能为空")))
                .orElseThrow(() -> new IllegalArgumentException("项目邀请不存在"));
        var invitation = stored.invitation();
        if (!normalizedProjectId.equals(invitation.projectId())) {
            throw new IllegalStateException("项目邀请不属于当前项目");
        }
        if (!"PENDING".equalsIgnoreCase(invitation.status())) {
            throw new IllegalStateException("项目邀请不可用");
        }
        if (invitation.expiresAt() != null && !invitation.expiresAt().isAfter(clock.instant())) {
            throw new IllegalStateException("项目邀请已过期");
        }
        if (!normalizedCurrentUserId.equals(invitation.inviteeUserId())) {
            throw new IllegalStateException("项目邀请用户不匹配");
        }
        var member = ensureMember(
                normalizedProjectId,
                normalizedCurrentUserId,
                invitation.roleKey(),
                invitation.displayName()
        );
        var now = clock.instant();
        repository.replaceInvitation(new ProjectInvitation(
                invitation.id(),
                invitation.projectId(),
                invitation.inviteeUserId(),
                invitation.displayName(),
                invitation.roleKey(),
                "ACCEPTED",
                invitation.createdByUserId(),
                invitation.expiresAt(),
                now,
                invitation.createdAt(),
                now
        ));
        recordAudit(
                normalizedProjectId,
                normalizedCurrentUserId,
                "IDENTITY_INVITATION_ACCEPTED",
                "PROJECT_INVITATION",
                invitation.id(),
                "ALLOW",
                "接受项目邀请：" + invitation.roleKey()
        );
        return member;
    }

    /**
     * 撤销待处理项目邀请。
     *
     * <p>撤销不会清空 token_hash，旧令牌再次提交时会得到“项目邀请不可用”，便于前端明确区分
     * “令牌不存在”和“邀请被治理下线”。</p>
     */
    public ProjectInvitation revokeInvitation(String projectId, String invitationId, String actorUserId) {
        var normalizedProjectId = requireText(projectId, "项目 ID 不能为空");
        var normalizedActorUserId = requireText(actorUserId, "操作者用户 ID 不能为空");
        var invitation = invitation(normalizedProjectId, invitationId);
        if (!"PENDING".equalsIgnoreCase(invitation.status())) {
            throw new IllegalStateException("项目邀请不可用");
        }
        var revoked = replaceInvitationStatus(invitation, "REVOKED");
        recordAudit(
                normalizedProjectId,
                normalizedActorUserId,
                "IDENTITY_INVITATION_REVOKED",
                "PROJECT_INVITATION",
                revoked.id(),
                "ALLOW",
                "撤销项目邀请：" + revoked.inviteeUserId()
        );
        return revoked;
    }

    /**
     * 重发项目邀请并轮换一次性令牌。
     *
     * <p>重发会写入新的 SHA-256 token_hash，旧明文令牌立即失效；已接受邀请不能重发，避免
     * 重复创建成员或复用已完成邀请。</p>
     */
    public IssuedProjectInvitation reissueInvitation(
            String projectId,
            String invitationId,
            String actorUserId,
            Instant expiresAt
    ) {
        var normalizedProjectId = requireText(projectId, "项目 ID 不能为空");
        var normalizedActorUserId = requireText(actorUserId, "操作者用户 ID 不能为空");
        var invitation = invitation(normalizedProjectId, invitationId);
        if ("ACCEPTED".equalsIgnoreCase(invitation.status())) {
            throw new IllegalStateException("项目邀请不可用");
        }
        var now = clock.instant();
        var nextExpiresAt = expiresAt == null ? now.plusSeconds(7 * 24 * 3600L) : expiresAt;
        if (!nextExpiresAt.isAfter(now)) {
            throw new IllegalArgumentException("邀请过期时间必须晚于当前时间");
        }
        var token = newInvitationToken();
        var reissued = new ProjectInvitation(
                invitation.id(),
                invitation.projectId(),
                invitation.inviteeUserId(),
                invitation.displayName(),
                invitation.roleKey(),
                "PENDING",
                invitation.createdByUserId(),
                nextExpiresAt,
                null,
                invitation.createdAt(),
                now
        );
        repository.replaceInvitation(new ProjectIdentityRepository.StoredProjectInvitation(reissued, tokenHash(token)));
        recordAudit(
                normalizedProjectId,
                normalizedActorUserId,
                "IDENTITY_INVITATION_REISSUED",
                "PROJECT_INVITATION",
                reissued.id(),
                "ALLOW",
                "重发项目邀请：" + reissued.inviteeUserId()
        );
        return new IssuedProjectInvitation(reissued, token);
    }

    /**
     * 将当前项目中过期的待处理邀请标记为 EXPIRED。
     */
    public List<ProjectInvitation> expirePendingInvitations(String projectId, String actorUserId) {
        var normalizedProjectId = requireText(projectId, "项目 ID 不能为空");
        var normalizedActorUserId = requireText(actorUserId, "操作者用户 ID 不能为空");
        if (repository == null) {
            return List.of();
        }
        var expired = new ArrayList<ProjectInvitation>();
        for (var invitation : repository.expiredPendingInvitations(normalizedProjectId, clock.instant())) {
            var updated = replaceInvitationStatus(invitation, "EXPIRED");
            expired.add(updated);
            recordAudit(
                    normalizedProjectId,
                    normalizedActorUserId,
                    "IDENTITY_INVITATION_EXPIRED",
                    "PROJECT_INVITATION",
                    updated.id(),
                    "ALLOW",
                    "清理过期项目邀请：" + updated.inviteeUserId()
            );
        }
        return List.copyOf(expired);
    }

    /**
     * 查询项目邀请列表。
     *
     * <p>作用域：项目管理视图；场景：配置中心展示待处理、已接受、已撤销和已过期邀请。</p>
     */
    public List<ProjectInvitation> invitations(String projectId) {
        if (repository == null) {
            return List.of();
        }
        return repository.invitations(requireText(projectId, "项目 ID 不能为空"));
    }

    /**
     * 查询项目成员列表。
     *
     * <p>作用域：项目成员和权限守卫；场景：工作台加载、操作者选择和权限校验。</p>
     */
    public List<ProjectMember> members(String projectId) {
        if (repository == null) {
            return List.of();
        }
        return repository.members(requireText(projectId, "项目 ID 不能为空"));
    }

    /**
     * 查询用户所属项目 ID 列表。
     *
     * <p>作用域：用户身份视图；场景：身份页展示用户可访问项目和权限排查。</p>
     */
    public List<String> projectsForUser(String userId) {
        if (repository == null) {
            return List.of();
        }
        return repository.projectsForUser(requireText(userId, "用户 ID 不能为空"));
    }

    /**
     * 查询用户在指定项目下的身份审计记录。
     *
     * <p>作用域：用户本人或项目管理角色；场景：登录、会话、邀请和成员治理事件追溯。</p>
     */
    public List<UserAuditRecord> auditRecords(String projectId, String userId) {
        if (repository == null) {
            return List.of();
        }
        return repository.auditRecords(
                requireText(projectId, "项目 ID 不能为空"),
                requireText(userId, "用户 ID 不能为空")
        );
    }

    /**
     * 写入身份域用户审计记录。
     *
     * <p>该方法用于登录、续期、退出和踢下线等权限事件。记录只保存动作、目标和摘要，
     * 不保存 Sa-Token 明文、bootstrap token、模型密钥等凭据。</p>
     */
    public UserAuditRecord recordAudit(
            String projectId,
            String actorUserId,
            String actionKey,
            String targetType,
            String targetId,
            String decision,
            String summary
    ) {
        var normalizedProjectId = requireText(projectId, "项目 ID 不能为空");
        var normalizedActorUserId = requireText(actorUserId, "用户 ID 不能为空");
        var record = new UserAuditRecord(
                UUID.randomUUID().toString(),
                normalizedProjectId,
                normalizedActorUserId,
                roleFor(normalizedProjectId, normalizedActorUserId),
                requireText(actionKey, "审计动作不能为空"),
                textOr(targetType, "IDENTITY_SESSION"),
                textOr(targetId, normalizedActorUserId),
                textOr(decision, "ALLOW"),
                textOr(summary, ""),
                clock.instant()
        );
        if (repository != null) {
            repository.recordAudit(record);
        }
        return record;
    }

    /**
     * 读取用户在项目中的当前有效角色。
     */
    public String roleFor(String projectId, String userId) {
        var normalizedUserId = requireText(userId, "用户 ID 不能为空");
        if (isSuperAdmin(normalizedUserId)) {
            return "SUPER_ADMIN";
        }
        return members(projectId).stream()
                .filter(member -> normalizedUserId.equals(member.userId()))
                .filter(this::activeMember)
                .map(ProjectMember::roleKey)
                .map(role -> textOr(role, ""))
                .findFirst()
                .orElse("");
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

    private String normalizeRoleKey(String roleKey) {
        return requireText(roleKey, "成员角色不能为空").toUpperCase(Locale.ROOT);
    }

    private String normalizeStatus(String status) {
        var normalized = requireText(status, "成员状态不能为空").toUpperCase(Locale.ROOT);
        if (!MEMBER_STATUS_KEYS.contains(normalized)) {
            throw new IllegalArgumentException("成员状态不支持：" + normalized);
        }
        return normalized;
    }

    private String memberId(String projectId, String userId, String roleKey) {
        return UUID.nameUUIDFromBytes((projectId + ":" + userId + ":" + roleKey).getBytes(StandardCharsets.UTF_8)).toString();
    }

    private ProjectInvitation invitation(String projectId, String invitationId) {
        if (repository == null) {
            throw new IllegalArgumentException("项目邀请不存在");
        }
        return repository.invitation(
                        requireText(projectId, "项目 ID 不能为空"),
                        requireText(invitationId, "项目邀请 ID 不能为空")
                )
                .orElseThrow(() -> new IllegalArgumentException("项目邀请不存在"));
    }

    private ProjectInvitation replaceInvitationStatus(ProjectInvitation invitation, String status) {
        var updated = new ProjectInvitation(
                invitation.id(),
                invitation.projectId(),
                invitation.inviteeUserId(),
                invitation.displayName(),
                invitation.roleKey(),
                status,
                invitation.createdByUserId(),
                invitation.expiresAt(),
                invitation.acceptedAt(),
                invitation.createdAt(),
                clock.instant()
        );
        repository.replaceInvitation(updated);
        return updated;
    }

    private String newInvitationToken() {
        var bytes = new byte[32];
        secureRandom.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private String tokenHash(String token) {
        try {
            var digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(requireText(token, "邀请令牌不能为空")
                    .getBytes(StandardCharsets.UTF_8)));
        } catch (Exception exception) {
            throw new IllegalStateException("邀请令牌哈希生成失败", exception);
        }
    }

    private void assertRetainsManagementMember(
            List<ProjectMember> members,
            String targetUserId,
            String nextRoleKey,
            String nextStatus
    ) {
        var activeManagers = 0;
        var countedTarget = false;
        for (var member : members) {
            if (member.userId().equals(targetUserId)) {
                if (!countedTarget && activeStatus(nextStatus) && MANAGEMENT_ROLE_KEYS.contains(nextRoleKey)) {
                    activeManagers++;
                }
                countedTarget = true;
                continue;
            }
            if (activeMember(member) && MANAGEMENT_ROLE_KEYS.contains(textOr(member.roleKey(), "").toUpperCase(Locale.ROOT))) {
                activeManagers++;
            }
        }
        if (activeManagers == 0) {
            throw new IllegalStateException("项目至少需要保留一个管理成员");
        }
    }

    private boolean activeMember(ProjectMember member) {
        return activeStatus(member.status());
    }

    private boolean activeStatus(String status) {
        return status == null || status.isBlank() || "ACTIVE".equalsIgnoreCase(status);
    }

    public record IssuedProjectInvitation(ProjectInvitation invitation, String token) {
    }

    public record ProjectMemberBatchUpdateCommand(String userId, String roleKey, String status) {
    }
}
