package com.matrixcode.identity.api;

import com.matrixcode.identity.application.ActorSessionTerminator;
import com.matrixcode.identity.application.ActorTokenIssuer;
import com.matrixcode.identity.application.ActorSessionInfo;
import com.matrixcode.identity.application.MatrixCodeAuthProperties;
import com.matrixcode.identity.application.ProjectMemberPermissionGuard;
import com.matrixcode.identity.application.ProjectIdentityService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.List;
import java.util.Objects;

/**
 * 身份认证和 Sa-Token 会话治理 API。
 *
 * <p>作用域：项目登录、退出、续期、会话列表和踢下线；场景：浏览器登录页、配置中心身份页
 * 以及受控自动化 bootstrap 签发短期 actor token。</p>
 */
@RestController
@RequestMapping("/api/projects/{projectId}/identity/auth")
public class IdentityAuthController {

    private static final String BOOTSTRAP_TOKEN_HEADER = "X-MatrixCode-Bootstrap-Token";
    private static final long MAX_TOKEN_TTL_SECONDS = 7 * 24 * 60 * 60;

    private final MatrixCodeAuthProperties authProperties;
    private final ProjectIdentityService identityService;
    private final ActorTokenIssuer tokenIssuer;
    private final RequestActorResolver actorResolver;
    private final ActorSessionTerminator sessionTerminator;
    private final ProjectMemberPermissionGuard permissionGuard;

    public IdentityAuthController(
            MatrixCodeAuthProperties authProperties,
            ProjectIdentityService identityService,
            ActorTokenIssuer tokenIssuer,
            RequestActorResolver actorResolver,
            ActorSessionTerminator sessionTerminator,
            ProjectMemberPermissionGuard permissionGuard
    ) {
        this.authProperties = Objects.requireNonNull(authProperties, "authProperties 不能为空");
        this.identityService = Objects.requireNonNull(identityService, "identityService 不能为空");
        this.tokenIssuer = Objects.requireNonNull(tokenIssuer, "tokenIssuer 不能为空");
        this.actorResolver = Objects.requireNonNull(actorResolver, "actorResolver 不能为空");
        this.sessionTerminator = Objects.requireNonNull(sessionTerminator, "sessionTerminator 不能为空");
        this.permissionGuard = Objects.requireNonNull(permissionGuard, "permissionGuard 不能为空");
    }

    /**
     * 为项目成员签发短期 actor token。
     *
     * <p>该接口只能用部署环境中的 bootstrap token 调用，用于把桌面端当前操作者升级为
     * 服务端可验证身份。接口不保存 token 明文，只写低敏审计记录，不返回任何业务敏感正文。</p>
     */
    @PostMapping("/actor-token")
    public ActorTokenIssueResponse issueActorToken(
            @PathVariable String projectId,
            @RequestBody ActorTokenIssueRequest command,
            HttpServletRequest request
    ) {
        return issueToken(projectId, command, request, "IDENTITY_ACTOR_TOKEN_ISSUED");
    }

    /**
     * 使用用户名和密码建立当前用户登录态。
     *
     * <p>登录成功后仍会校验当前用户能访问目标项目：普通用户必须是项目 ACTIVE 成员，
     * 全局超级管理员 admin 可以跨项目进入工作台并治理用户权限。</p>
     */
    @PostMapping("/login")
    public ActorTokenIssueResponse login(
            @PathVariable String projectId,
            @RequestBody ActorPasswordLoginRequest command
    ) {
        if (command == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "请求内容格式不正确");
        }
        var username = requireText(command.username(), "用户名不能为空");
        var password = requireText(command.password(), "密码不能为空");
        var user = identityService.authenticate(username, password)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED, "用户名或密码不正确"));
        permissionGuard.assertProjectMember(projectId, user.id());
        var token = tokenIssuer.issue(user.id(), authProperties.defaultTokenTtl());
        recordAudit(projectId, user.id(), "IDENTITY_LOGIN", user.id(), "用户密码登录");
        return new ActorTokenIssueResponse(token.userId(), token.token(), token.expiresAt().toString());
    }

    /**
     * 修改当前登录用户的密码。
     *
     * <p>接口只允许当前 Sa-Token 解析出的用户修改自己的密码；服务端校验项目成员状态和旧密码，
     * 然后把新密码派生哈希写回数据库，成功后顺带续期当前会话。</p>
     */
    @PostMapping("/password")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void changePassword(
            @PathVariable String projectId,
            @RequestBody ActorPasswordChangeRequest command,
            HttpServletRequest request
    ) {
        if (command == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "请求内容格式不正确");
        }
        var currentUserId = actorResolver.resolve(request);
        permissionGuard.assertProjectMember(projectId, currentUserId);
        var oldPassword = requireText(command.oldPassword(), "当前密码不能为空");
        var newPassword = requireText(command.newPassword(), "新密码不能为空");
        if (!identityService.changePassword(currentUserId, oldPassword, newPassword)) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "当前密码不正确");
        }
        sessionTerminator.renewCurrent(authProperties.defaultTokenTtl());
        recordAudit(projectId, currentUserId, "IDENTITY_PASSWORD_CHANGED", currentUserId, "修改当前用户密码");
    }

    /**
     * 退出当前 Sa-Token 会话。
     */
    @PostMapping("/logout")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void logout(@PathVariable String projectId, HttpServletRequest request) {
        var currentUserId = actorResolver.resolve(request);
        permissionGuard.assertProjectMember(projectId, currentUserId);
        sessionTerminator.logout();
        recordAudit(projectId, currentUserId, "IDENTITY_LOGOUT", currentUserId, "退出当前登录会话");
    }

    /**
     * 查询当前请求解析出的登录用户。
     */
    @GetMapping("/session")
    public ActorSessionResponse session(@PathVariable String projectId, HttpServletRequest request) {
        var currentUserId = actorResolver.resolve(request);
        permissionGuard.assertProjectMember(projectId, currentUserId);
        return new ActorSessionResponse(true, currentUserId);
    }

    /**
     * 续期当前 Sa-Token 会话。
     *
     * <p>续期前会确认当前用户仍是项目有效成员；成员被禁用或移除后，即使 token
     * 尚未过期，也不能继续延长会话。</p>
     */
    @PostMapping("/session/renew")
    public ActorSessionInfo renewSession(
            @PathVariable String projectId,
            HttpServletRequest request
    ) {
        var currentUserId = actorResolver.resolve(request);
        permissionGuard.assertProjectMember(projectId, currentUserId);
        var session = sessionTerminator.renewCurrent(authProperties.defaultTokenTtl());
        recordAudit(projectId, currentUserId, "IDENTITY_SESSION_RENEW", currentUserId, "续期当前登录会话");
        return session;
    }

    /**
     * 查询指定用户的登录会话列表。
     *
     * <p>用户本人可以查看自己的会话；查看其他用户会话需要项目管理权限。返回值只包含
     * token 指纹和设备信息，不暴露 token 明文。</p>
     */
    @GetMapping("/users/{userId}/sessions")
    public List<ActorSessionInfo> sessions(
            @PathVariable String projectId,
            @PathVariable String userId,
            HttpServletRequest request
    ) {
        var targetUserId = requireText(userId, "用户 ID 不能为空");
        assertCanManageTargetSession(projectId, targetUserId, request);
        return sessionTerminator.sessions(targetUserId);
    }

    /**
     * 踢下线指定用户的所有登录会话。
     */
    @PostMapping("/users/{userId}/sessions/kickout")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void kickoutSessions(
            @PathVariable String projectId,
            @PathVariable String userId,
            HttpServletRequest request
    ) {
        var targetUserId = requireText(userId, "用户 ID 不能为空");
        var currentUserId = assertCanManageTargetSession(projectId, targetUserId, request);
        sessionTerminator.kickout(targetUserId);
        recordAudit(projectId, currentUserId, "IDENTITY_SESSION_KICKOUT", targetUserId, "踢下线用户登录会话");
    }

    private ActorTokenIssueResponse issueToken(
            String projectId,
            ActorTokenIssueRequest command,
            HttpServletRequest request,
            String actionKey
    ) {
        assertBootstrapToken(request.getHeader(BOOTSTRAP_TOKEN_HEADER));
        var userId = requireText(command.userId(), "用户编号不能为空");
        assertProjectMember(projectId, userId);
        var token = tokenIssuer.issue(userId, ttlOf(command.ttlSeconds()));
        recordAudit(projectId, userId, actionKey, userId, "签发登录会话");
        return new ActorTokenIssueResponse(token.userId(), token.token(), token.expiresAt().toString());
    }

    private void assertBootstrapToken(String providedToken) {
        var configuredToken = authProperties.getBootstrapToken();
        if (configuredToken == null || configuredToken.isBlank()) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "身份令牌签发入口未配置");
        }
        if (providedToken == null || providedToken.isBlank()) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "缺少身份令牌签发凭证");
        }
        if (!sameToken(configuredToken, providedToken.trim())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "身份令牌签发凭证不正确");
        }
    }

    private boolean sameToken(String expected, String actual) {
        return MessageDigest.isEqual(
                expected.getBytes(StandardCharsets.UTF_8),
                actual.getBytes(StandardCharsets.UTF_8)
        );
    }

    private void assertProjectMember(String projectId, String userId) {
        var isProjectMember = identityService.members(projectId).stream()
                .anyMatch(member -> userId.equals(member.userId())
                        && (member.status() == null || member.status().isBlank() || "ACTIVE".equalsIgnoreCase(member.status())));
        if (!isProjectMember) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "只能为项目成员签发身份令牌");
        }
    }

    private String assertCanManageTargetSession(String projectId, String targetUserId, HttpServletRequest request) {
        var currentUserId = actorResolver.resolve(request);
        permissionGuard.assertProjectMember(projectId, currentUserId);
        permissionGuard.assertProjectMember(projectId, targetUserId);
        if (!currentUserId.equals(targetUserId)) {
            permissionGuard.assertCanManageProject(projectId, currentUserId);
        }
        return currentUserId;
    }

    private void recordAudit(String projectId, String actorUserId, String actionKey, String targetUserId, String summary) {
        identityService.recordAudit(
                projectId,
                actorUserId,
                actionKey,
                "IDENTITY_SESSION",
                targetUserId,
                "ALLOW",
                summary
        );
    }

    private Duration ttlOf(Long requestedTtlSeconds) {
        if (requestedTtlSeconds == null || requestedTtlSeconds < 1) {
            return authProperties.defaultTokenTtl();
        }
        return Duration.ofSeconds(Math.min(requestedTtlSeconds, MAX_TOKEN_TTL_SECONDS));
    }

    private String requireText(String value, String message) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(message);
        }
        return value.trim();
    }

    /**
     * bootstrap 签发 actor token 的请求体。
     */
    public record ActorTokenIssueRequest(String userId, Long ttlSeconds) {
    }

    /**
     * 用户名密码登录请求体。
     */
    public record ActorPasswordLoginRequest(String username, String password) {
    }

    /**
     * 当前用户修改密码请求体。
     */
    public record ActorPasswordChangeRequest(String oldPassword, String newPassword) {
    }

    /**
     * 登录或签发成功后的 token 响应。
     */
    public record ActorTokenIssueResponse(String userId, String token, String expiresAt) {
    }

    /**
     * 当前会话查询响应。
     */
    public record ActorSessionResponse(boolean authenticated, String userId) {
    }
}
