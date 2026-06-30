package com.matrixcode.identity.application;

import com.matrixcode.identity.domain.MatrixUser;
import com.matrixcode.identity.domain.ProjectInvitation;
import com.matrixcode.identity.domain.ProjectMember;
import com.matrixcode.identity.domain.UserAuditRecord;

import java.util.List;
import java.util.Optional;

/**
 * 项目身份仓储接口。
 *
 * <p>作用域：用户、项目、成员、邀请和用户级审计的持久化边界；场景：Service 层不关心
 * JDBC、MyBatis-Plus 或测试内存实现差异，统一通过该接口读写身份域数据。</p>
 */
public interface ProjectIdentityRepository {

    /**
     * 确保用户基础资料存在。
     */
    void ensureUser(MatrixUser user);

    /**
     * 按用户名读取登录凭证。
     *
     * <p>该方法只供密码登录和超级管理员初始化使用，业务查询不要返回密码哈希，
     * 避免把认证字段带到普通项目成员接口。</p>
     */
    default Optional<StoredUserCredential> userCredentialByUsername(String username) {
        return Optional.empty();
    }

    /**
     * 按用户 ID 读取登录凭证。
     *
     * <p>权限守卫使用该方法判断用户是否为全局超级管理员；默认空实现用于兼容
     * 只关心成员关系的测试仓储。</p>
     */
    default Optional<StoredUserCredential> userCredentialById(String userId) {
        return Optional.empty();
    }

    /**
     * 保存用户资料及登录凭证。
     *
     * <p>调用方必须传入已派生的密码哈希，仓储层不接触明文密码。该方法用于首次
     * 初始化 admin 账号和超级管理员创建新用户。</p>
     */
    default void saveUserCredential(StoredUserCredential credential) {
        ensureUser(credential.user());
    }

    /**
     * 确保项目基础资料存在。
     */
    void ensureProject(String projectId, String name, String ownerUserId, String currentStage);

    /**
     * 确保项目成员关系存在。
     */
    void ensureMember(ProjectMember member);

    /**
     * 将同一项目、同一用户的当前成员记录替换为传入记录。
     *
     * <p>默认实现保持旧仓储兼容；正式 JDBC 仓储会保证同一用户只保留一个当前角色，
     * 旧角色会被标记为已移除并从成员列表隐藏。</p>
     */
    default void replaceMember(ProjectMember member) {
        ensureMember(member);
    }

    /**
     * 查询项目成员列表。
     */
    List<ProjectMember> members(String projectId);

    /**
     * 查询用户所属项目 ID 列表。
     */
    List<String> projectsForUser(String userId);

    /**
     * 查询用户在项目内的身份审计记录。
     */
    List<UserAuditRecord> auditRecords(String projectId, String userId);

    /**
     * 写入用户级审计记录。
     *
     * <p>默认空实现用于兼容内存测试仓储；正式 JDBC 仓储会写入
     * {@code matrixcode_audit_records}，供身份中心和 Agent 审计视图查询。</p>
     */
    default void recordAudit(UserAuditRecord record) {
    }

    /**
     * 保存带 token 哈希的项目邀请。
     */
    default void saveInvitation(StoredProjectInvitation invitation) {
    }

    /**
     * 替换项目邀请业务状态。
     */
    default void replaceInvitation(ProjectInvitation invitation) {
    }

    /**
     * 替换项目邀请及其 token 哈希。
     */
    default void replaceInvitation(StoredProjectInvitation invitation) {
    }

    /**
     * 查询项目邀请列表。
     */
    default List<ProjectInvitation> invitations(String projectId) {
        return List.of();
    }

    /**
     * 按邀请 ID 查询项目邀请。
     */
    default Optional<ProjectInvitation> invitation(String projectId, String invitationId) {
        return Optional.empty();
    }

    /**
     * 按一次性令牌哈希查询项目邀请。
     */
    default Optional<StoredProjectInvitation> invitationByTokenHash(String tokenHash) {
        return Optional.empty();
    }

    /**
     * 查询当前项目中过期且仍待处理的邀请。
     */
    default List<ProjectInvitation> expiredPendingInvitations(String projectId, java.time.Instant now) {
        return List.of();
    }

    /**
     * 项目邀请和一次性令牌哈希的仓储组合。
     */
    record StoredProjectInvitation(ProjectInvitation invitation, String tokenHash) {
    }

    /**
     * 用户资料、密码哈希和超级管理员标记的仓储组合。
     */
    record StoredUserCredential(
            MatrixUser user,
            String passwordHash,
            boolean superAdmin,
            java.time.Instant passwordUpdatedAt
    ) {
    }
}
