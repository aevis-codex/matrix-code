package com.matrixcode.identity.application;

import com.matrixcode.identity.domain.ProjectMember;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

import java.util.Locale;
import java.util.Optional;
import java.util.Set;

@Component
public class ProjectMemberPermissionGuard {

    private static final Set<String> MANAGEMENT_ROLE_KEYS = Set.of("OWNER", "ADMIN", "MAINTAINER");

    private final ProjectIdentityService identityService;

    public ProjectMemberPermissionGuard(ProjectIdentityService identityService) {
        this.identityService = identityService;
    }

    /**
     * 校验当前用户是否具备项目管理权限。
     *
     * <p>项目管理权限用于保护会改变项目成员、角色智能体配置等全局行为的写接口。
     * 只有 ACTIVE 状态的 OWNER、ADMIN、MAINTAINER 可以通过校验。</p>
     *
     * @param projectId 当前项目 ID。
     * @param userId 当前请求用户 ID，通常由 {@code RequestActorResolver} 从 Sa-Token 登录态解析得到。
     */
    public void assertCanManageProject(String projectId, String userId) {
        if (!canManageProject(projectId, userId)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "需要项目管理权限");
        }
    }

    /**
     * 校验当前用户是否为项目有效成员。
     *
     * <p>项目成员权限用于保护目录列表、本地文件读取和 Git diff 等会暴露项目资产的读接口。
     * 只要用户是当前项目 ACTIVE 成员即可通过，不要求具备管理角色。</p>
     *
     * @param projectId 当前项目 ID。
     * @param userId 当前请求用户 ID。
     */
    public void assertProjectMember(String projectId, String userId) {
        if (!isProjectMember(projectId, userId)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "需要项目成员权限");
        }
    }

    /**
     * 判断用户是否为当前项目的管理成员。
     *
     * @param projectId 当前项目 ID。
     * @param userId 当前请求用户 ID。
     * @return 具备 OWNER、ADMIN、MAINTAINER 任一有效角色时返回 true。
     */
    public boolean canManageProject(String projectId, String userId) {
        if (identityService.isSuperAdmin(userId)) {
            return true;
        }
        return activeProjectMember(projectId, userId)
                .map(this::normalizedRoleKey)
                .filter(MANAGEMENT_ROLE_KEYS::contains)
                .isPresent();
    }

    /**
     * 判断用户是否为当前项目有效成员。
     *
     * @param projectId 当前项目 ID。
     * @param userId 当前请求用户 ID。
     * @return 存在 ACTIVE 项目成员记录时返回 true。
     */
    public boolean isProjectMember(String projectId, String userId) {
        if (identityService.isSuperAdmin(userId)) {
            return true;
        }
        return activeProjectMember(projectId, userId).isPresent();
    }

    /**
     * 查找当前用户在项目中的有效成员记录。
     */
    private Optional<ProjectMember> activeProjectMember(String projectId, String userId) {
        if (projectId == null || projectId.isBlank() || userId == null || userId.isBlank()) {
            return Optional.empty();
        }
        return identityService.members(projectId).stream()
                .filter(member -> userId.trim().equals(member.userId()))
                .filter(this::activeMember)
                .findFirst();
    }

    /**
     * 判断项目成员记录是否处于可用状态。
     *
     * <p>历史数据可能没有 status 字段值，空值按兼容策略视为可用；显式非 ACTIVE 状态一律拒绝。</p>
     */
    private boolean activeMember(ProjectMember member) {
        return member.status() == null || member.status().isBlank() || "ACTIVE".equalsIgnoreCase(member.status());
    }

    /**
     * 归一化角色标识，避免数据库或请求侧大小写差异影响权限判断。
     */
    private String normalizedRoleKey(ProjectMember member) {
        return member.roleKey() == null ? "" : member.roleKey().trim().toUpperCase(Locale.ROOT);
    }
}
