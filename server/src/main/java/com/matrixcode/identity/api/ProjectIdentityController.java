package com.matrixcode.identity.api;

import com.matrixcode.identity.application.ProjectMemberPermissionGuard;
import com.matrixcode.identity.application.ProjectIdentityService;
import com.matrixcode.identity.domain.ProjectInvitation;
import com.matrixcode.identity.domain.ProjectMember;
import com.matrixcode.identity.domain.UserAuditRecord;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.List;

/**
 * 项目身份和成员治理 API。
 *
 * <p>作用域：项目成员、项目管理角色和全局 admin；场景：配置中心读取成员、创建用户、
 * 发放邀请、批量调整权限和查看用户审计。</p>
 */
@RestController
@RequestMapping("/api/projects/{projectId}/identity")
public class ProjectIdentityController {

    private final ProjectIdentityService identityService;
    private final RequestActorResolver actorResolver;
    private final ProjectMemberPermissionGuard permissionGuard;

    public ProjectIdentityController(
            ProjectIdentityService identityService,
            RequestActorResolver actorResolver,
            ProjectMemberPermissionGuard permissionGuard
    ) {
        this.identityService = identityService;
        this.actorResolver = actorResolver;
        this.permissionGuard = permissionGuard;
    }

    /**
     * 读取项目成员列表。
     *
     * <p>作用域：项目 ACTIVE 成员；场景：工作台操作者选择、配置中心成员页和权限展示。</p>
     */
    @GetMapping("/members")
    public List<ProjectMember> members(@PathVariable String projectId, HttpServletRequest request) {
        var currentUserId = actorResolver.resolve(request);
        permissionGuard.assertProjectMember(projectId, currentUserId);
        return identityService.members(projectId);
    }

    /**
     * 添加已有用户为项目成员。
     *
     * <p>作用域：项目管理角色；场景：不创建登录凭证，只把已有用户授予当前项目角色。</p>
     */
    @PostMapping("/members")
    public ProjectMember addMember(
            @PathVariable String projectId,
            @RequestBody AddProjectMemberRequest request,
            HttpServletRequest httpRequest
    ) {
        var currentUserId = actorResolver.resolve(httpRequest);
        permissionGuard.assertCanManageProject(projectId, currentUserId);
        return identityService.ensureMember(projectId, request.userId(), request.roleKey(), request.displayName());
    }

    /**
     * 超级管理员创建可登录用户并授权项目角色。
     *
     * <p>该接口只允许全局 admin 调用。密码由服务层派生哈希后入库，响应只返回项目成员信息，
     * 不返回密码哈希或明文密码。</p>
     */
    @PostMapping("/users")
    public ProjectMember createUser(
            @PathVariable String projectId,
            @RequestBody CreateProjectUserRequest request,
            HttpServletRequest httpRequest
    ) {
        var currentUserId = actorResolver.resolve(httpRequest);
        assertSuperAdmin(currentUserId);
        return identityService.createUserWithPassword(
                projectId,
                request.userId(),
                request.username(),
                request.displayName(),
                request.email(),
                request.password(),
                request.roleKey(),
                currentUserId
        );
    }

    /**
     * 创建项目成员邀请。
     *
     * <p>作用域：项目管理角色；场景：为尚未加入项目的用户生成一次性邀请令牌。</p>
     */
    @PostMapping("/invitations")
    public ProjectIdentityService.IssuedProjectInvitation createInvitation(
            @PathVariable String projectId,
            @RequestBody CreateProjectInvitationRequest request,
            HttpServletRequest httpRequest
    ) {
        var currentUserId = actorResolver.resolve(httpRequest);
        permissionGuard.assertCanManageProject(projectId, currentUserId);
        return identityService.createInvitation(
                projectId,
                request.userId(),
                request.displayName(),
                request.roleKey(),
                currentUserId,
                request.expiresAt()
        );
    }

    /**
     * 查询项目邀请列表。
     *
     * <p>作用域：项目管理角色；场景：成员配置页查看邀请状态和过期时间。</p>
     */
    @GetMapping("/invitations")
    public List<ProjectInvitation> invitations(@PathVariable String projectId, HttpServletRequest request) {
        var currentUserId = actorResolver.resolve(request);
        permissionGuard.assertCanManageProject(projectId, currentUserId);
        return identityService.invitations(projectId);
    }

    /**
     * 撤销待处理项目邀请。
     *
     * <p>作用域：项目管理角色；场景：邀请误发、用户不再加入或令牌需要回收。</p>
     */
    @PostMapping("/invitations/{invitationId}/revoke")
    public ProjectInvitation revokeInvitation(
            @PathVariable String projectId,
            @PathVariable String invitationId,
            HttpServletRequest request
    ) {
        var currentUserId = actorResolver.resolve(request);
        permissionGuard.assertCanManageProject(projectId, currentUserId);
        return identityService.revokeInvitation(projectId, invitationId, currentUserId);
    }

    /**
     * 重发项目邀请并轮换令牌。
     *
     * <p>作用域：项目管理角色；场景：邀请过期、令牌泄露或需要重新通知被邀请人。</p>
     */
    @PostMapping("/invitations/{invitationId}/reissue")
    public ProjectIdentityService.IssuedProjectInvitation reissueInvitation(
            @PathVariable String projectId,
            @PathVariable String invitationId,
            @RequestBody ReissueProjectInvitationRequest request,
            HttpServletRequest httpRequest
    ) {
        var currentUserId = actorResolver.resolve(httpRequest);
        permissionGuard.assertCanManageProject(projectId, currentUserId);
        return identityService.reissueInvitation(projectId, invitationId, currentUserId, request.expiresAt());
    }

    /**
     * 清理当前项目中过期的待处理邀请。
     *
     * <p>作用域：项目管理角色；场景：配置中心手动把过期邀请标记为 EXPIRED。</p>
     */
    @PostMapping("/invitations:expire")
    public List<ProjectInvitation> expirePendingInvitations(
            @PathVariable String projectId,
            HttpServletRequest request
    ) {
        var currentUserId = actorResolver.resolve(request);
        permissionGuard.assertCanManageProject(projectId, currentUserId);
        return identityService.expirePendingInvitations(projectId, currentUserId);
    }

    /**
     * 接受项目邀请。
     *
     * <p>作用域：当前登录用户；场景：被邀请用户使用一次性令牌加入项目。</p>
     */
    @PostMapping("/invitations/{token}/accept")
    public ProjectMember acceptInvitation(
            @PathVariable String projectId,
            @PathVariable String token,
            HttpServletRequest request
    ) {
        var currentUserId = actorResolver.resolve(request);
        return identityService.acceptInvitation(projectId, token, currentUserId);
    }

    /**
     * 调整项目成员治理状态。
     *
     * <p>该接口只允许项目管理角色调用，用于在配置中心完成成员角色变更、禁用、恢复和移除。
     * 服务层会保证项目至少保留一名 ACTIVE 管理成员，避免误操作导致项目无人可管理。</p>
     */
    @PatchMapping("/members/{userId}")
    public ProjectMember updateMember(
            @PathVariable String projectId,
            @PathVariable String userId,
            @RequestBody UpdateProjectMemberRequest request,
            HttpServletRequest httpRequest
    ) {
        var currentUserId = actorResolver.resolve(httpRequest);
        permissionGuard.assertCanManageProject(projectId, currentUserId);
        return identityService.updateMember(projectId, userId, request.roleKey(), request.status());
    }

    /**
     * 批量调整项目成员角色或状态。
     *
     * <p>作用域：项目管理角色；场景：配置中心一次性禁用、恢复、移除或改派多个成员。</p>
     */
    @PatchMapping("/members:batch")
    public List<ProjectMember> updateMembers(
            @PathVariable String projectId,
            @RequestBody BatchUpdateProjectMembersRequest request,
            HttpServletRequest httpRequest
    ) {
        var currentUserId = actorResolver.resolve(httpRequest);
        permissionGuard.assertCanManageProject(projectId, currentUserId);
        var updates = request.updates() == null ? List.<ProjectIdentityService.ProjectMemberBatchUpdateCommand>of()
                : request.updates().stream()
                .map(update -> new ProjectIdentityService.ProjectMemberBatchUpdateCommand(
                        update.userId(),
                        update.roleKey(),
                        update.status()
                ))
                .toList();
        return identityService.updateMembers(projectId, currentUserId, updates);
    }

    /**
     * 查询用户所属项目。
     *
     * <p>作用域：用户本人或项目管理角色；场景：身份页展示当前用户可访问的项目范围。</p>
     */
    @GetMapping("/users/{userId}/projects")
    public List<String> projectsForUser(
            @PathVariable String projectId,
            @PathVariable String userId,
            HttpServletRequest request
    ) {
        var targetUserId = requireText(userId, "用户 ID 不能为空");
        assertCanReadUserScopedIdentity(projectId, targetUserId, request);
        return identityService.projectsForUser(targetUserId);
    }

    /**
     * 查询用户身份审计记录。
     *
     * <p>作用域：用户本人或项目管理角色；场景：身份页查看登录、续期、踢下线和用户治理记录。</p>
     */
    @GetMapping("/users/{userId}/audit-records")
    public List<UserAuditRecord> auditRecords(
            @PathVariable String projectId,
            @PathVariable String userId,
            HttpServletRequest request
    ) {
        var targetUserId = requireText(userId, "用户 ID 不能为空");
        assertCanReadUserScopedIdentity(projectId, targetUserId, request);
        return identityService.auditRecords(projectId, targetUserId);
    }

    /**
     * 添加已有用户为项目成员的请求体。
     *
     * <p>作用域：项目管理 API；场景：超级管理员或项目管理角色把已有用户加入当前项目并指定角色。</p>
     */
    public record AddProjectMemberRequest(String userId, String displayName, String roleKey) {
    }

    /**
     * 创建项目用户并加入项目的请求体。
     *
     * <p>作用域：项目管理 API；场景：admin 为项目创建新用户、设置初始密码并授予项目角色。</p>
     */
    public record CreateProjectUserRequest(
            String userId,
            String username,
            String displayName,
            String email,
            String password,
            String roleKey
    ) {
    }

    /**
     * 更新项目成员角色或状态的请求体。
     *
     * <p>作用域：项目管理 API；场景：调整成员角色、禁用成员或恢复成员访问权限。</p>
     */
    public record UpdateProjectMemberRequest(String roleKey, String status) {
    }

    /**
     * 创建项目邀请的请求体。
     *
     * <p>作用域：项目管理 API；场景：为用户生成项目邀请并配置过期时间。</p>
     */
    public record CreateProjectInvitationRequest(String userId, String displayName, String roleKey, Instant expiresAt) {
    }

    /**
     * 重新签发项目邀请的请求体。
     *
     * <p>作用域：项目管理 API；场景：旧邀请失效后重置邀请 token 和过期时间。</p>
     */
    public record ReissueProjectInvitationRequest(Instant expiresAt) {
    }

    /**
     * 批量更新项目成员的请求体。
     *
     * <p>作用域：项目管理 API；场景：管理员在成员表格中一次性提交多个角色或状态调整。</p>
     */
    public record BatchUpdateProjectMembersRequest(List<BatchUpdateProjectMemberRequest> updates) {
    }

    /**
     * 批量更新中的单个成员变更项。
     *
     * <p>作用域：项目管理 API；场景：描述一个用户的目标角色和目标状态。</p>
     */
    public record BatchUpdateProjectMemberRequest(String userId, String roleKey, String status) {
    }

    /**
     * 校验当前请求用户是否可以读取用户维度身份数据。
     *
     * <p>用户本人可以读取自己的项目和审计信息；读取其他用户数据需要当前用户具备项目管理权限。
     * 两种路径都必须先是当前项目 ACTIVE 成员，避免借项目路径枚举成员和审计数据。</p>
     */
    private void assertCanReadUserScopedIdentity(String projectId, String targetUserId, HttpServletRequest request) {
        var currentUserId = actorResolver.resolve(request);
        permissionGuard.assertProjectMember(projectId, currentUserId);
        if (currentUserId.equals(targetUserId)) {
            return;
        }
        permissionGuard.assertCanManageProject(projectId, currentUserId);
    }

    private String requireText(String value, String message) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(message);
        }
        return value.trim();
    }

    private void assertSuperAdmin(String currentUserId) {
        if (!identityService.isSuperAdmin(currentUserId)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "需要超级管理员权限");
        }
    }
}
