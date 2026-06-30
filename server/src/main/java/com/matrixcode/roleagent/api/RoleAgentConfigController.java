package com.matrixcode.roleagent.api;

import com.matrixcode.identity.api.ProjectRequestPermissionGuard;
import com.matrixcode.modelgateway.domain.ModelRole;
import com.matrixcode.roleagent.application.RoleAgentConfigCommand;
import com.matrixcode.roleagent.application.RoleAgentConfigService;
import com.matrixcode.roleagent.domain.RoleAgentConfig;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 角色智能体配置 API。
 *
 * <p>作用域：项目成员和项目管理角色；场景：读取、编辑角色提示词、模型、样式和缓存策略。</p>
 */
@RestController
@RequestMapping("/api/projects/{projectId}/role-agent-configs")
public class RoleAgentConfigController {

    private final RoleAgentConfigService configService;
    private final ProjectRequestPermissionGuard requestPermissionGuard;

    public RoleAgentConfigController(
            RoleAgentConfigService configService,
            ProjectRequestPermissionGuard requestPermissionGuard
    ) {
        this.configService = configService;
        this.requestPermissionGuard = requestPermissionGuard;
    }

    /**
     * 读取角色智能体配置列表。
     */
    @GetMapping
    public List<RoleAgentConfig> configs(@PathVariable String projectId, HttpServletRequest request) {
        requestPermissionGuard.assertProjectMember(request, projectId);
        return configService.configs(projectId);
    }

    /**
     * 更新指定角色智能体配置。
     */
    @PutMapping("/{role}")
    public RoleAgentConfig update(
            @PathVariable String projectId,
            @PathVariable String role,
            @RequestBody RoleAgentConfigCommand command,
            HttpServletRequest request
    ) {
        requestPermissionGuard.assertCanManageProject(request, projectId);
        return configService.update(projectId, ModelRole.fromPath(role), command);
    }
}
