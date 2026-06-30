package com.matrixcode.agentruntime.api;

import com.matrixcode.agentruntime.application.AgentRuntimeUserAuditService;
import com.matrixcode.agentruntime.domain.AgentRuntimeUserAuditReport;
import com.matrixcode.identity.api.RequestActorResolver;
import com.matrixcode.identity.application.ProjectIdentityService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.Locale;
import java.util.Set;

/**
 * Agent 运行责任审计 REST 接口。
 *
 * <p>作用域：项目内 Agent Runtime 审计 API。主要场景是管理员查看成员在编码 Agent、测试 Agent、
 * 部署 Agent 等运行中的责任分布，也允许普通成员查看自己的执行责任报告。控制器只返回低敏统计与运行摘要，
 * 不暴露模型提示词、密钥、文件内容或命令输出正文。</p>
 */
@RestController
@RequestMapping("/api/projects/{projectId}/agent-runs")
public class AgentRuntimeUserAuditController {

    private static final Set<String> ADMIN_ROLE_KEYS = Set.of("OWNER", "ADMIN", "MAINTAINER");

    private final AgentRuntimeUserAuditService auditService;
    private final RequestActorResolver actorResolver;
    private final ProjectIdentityService identityService;

    public AgentRuntimeUserAuditController(
            AgentRuntimeUserAuditService auditService,
            RequestActorResolver actorResolver,
            ProjectIdentityService identityService
    ) {
        this.auditService = auditService;
        this.actorResolver = actorResolver;
        this.identityService = identityService;
    }

    /**
     * 查询某个用户在当前项目内的 Agent Runtime 责任审计报告。
     *
     * <p>接口只返回低敏运行摘要、责任人和计数信息；不触发模型、不执行命令、不读写文件、不应用 Patch。</p>
     */
    @GetMapping("/user-audit")
    public AgentRuntimeUserAuditReport userAudit(
            @PathVariable String projectId,
            @RequestParam String userId,
            @RequestParam(defaultValue = "50") int limit,
            HttpServletRequest request
    ) {
        var currentUserId = actorResolver.resolve(request);
        var targetUserId = requireText(userId, "用户编号不能为空");
        assertCanReadUserAudit(projectId, currentUserId, targetUserId);
        return auditService.audit(projectId, targetUserId, limit);
    }

    /**
     * 校验当前请求用户是否允许读取目标用户的 Agent Runtime 责任审计。
     *
     * <p>本人可以读取自己的报告；项目 OWNER、ADMIN、MAINTAINER 可以读取同项目其他用户报告；
     * 其他用户一律拒绝，避免前端选择器绕过服务端责任边界。</p>
     */
    private void assertCanReadUserAudit(String projectId, String currentUserId, String targetUserId) {
        if (currentUserId.equals(targetUserId)) {
            return;
        }
        var canManageProject = identityService.members(projectId).stream()
                .filter(member -> currentUserId.equals(member.userId()))
                .filter(member -> member.status() == null || member.status().isBlank() || "ACTIVE".equalsIgnoreCase(member.status()))
                .map(member -> member.roleKey() == null ? "" : member.roleKey().trim().toUpperCase(Locale.ROOT))
                .anyMatch(ADMIN_ROLE_KEYS::contains);
        if (!canManageProject) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "无权查询其他用户责任审计");
        }
    }

    /**
     * 校验并规范化必填文本参数。
     *
     * <p>作用域：当前控制器内部入参防御。场景：读取查询参数后先裁剪空白，避免空字符串进入审计查询和权限判断。</p>
     */
    private String requireText(String value, String message) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(message);
        }
        return value.trim();
    }
}
