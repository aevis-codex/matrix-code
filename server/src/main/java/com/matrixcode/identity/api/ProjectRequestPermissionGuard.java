package com.matrixcode.identity.api;

import com.matrixcode.identity.application.ProjectMemberPermissionGuard;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

@Component
public class ProjectRequestPermissionGuard {

    private final RequestActorResolver actorResolver;
    private final ProjectMemberPermissionGuard permissionGuard;

    /**
     * 创建仅支持操作者一致性校验的请求守卫。
     *
     * <p>该构造器用于独立 Controller 单元测试和只需要 {@link #assertActor(HttpServletRequest, String)}
     * 的轻量装配场景。生产 Spring Bean 使用完整构造器注入项目成员权限守卫。</p>
     *
     * @param actorResolver 当前请求操作者解析器。
     */
    public ProjectRequestPermissionGuard(RequestActorResolver actorResolver) {
        this(actorResolver, null);
    }

    @Autowired
    public ProjectRequestPermissionGuard(
            RequestActorResolver actorResolver,
            ProjectMemberPermissionGuard permissionGuard
    ) {
        this.actorResolver = actorResolver;
        this.permissionGuard = permissionGuard;
    }

    /**
     * 解析当前请求用户并校验其为项目有效成员。
     *
     * @param request 当前 HTTP 请求。
     * @param projectId 当前项目 ID。
     * @return 归一化后的当前用户 ID，便于调用方继续写审计或运行事件。
     */
    public String assertProjectMember(HttpServletRequest request, String projectId) {
        var requestActor = actorResolver.resolve(request);
        if (permissionGuard == null) {
            throw new IllegalStateException("项目成员权限守卫未配置");
        }
        permissionGuard.assertProjectMember(projectId, requestActor);
        return requestActor;
    }

    /**
     * 解析当前请求用户并校验其具备项目管理权限。
     *
     * <p>项目管理权限用于保护项目成员治理、角色智能体配置、模型供应商配置和角色模型绑定等
     * 会影响项目全局行为的写接口。</p>
     *
     * @param request 当前 HTTP 请求。
     * @param projectId 当前项目 ID。
     * @return 归一化后的当前用户 ID。
     */
    public String assertCanManageProject(HttpServletRequest request, String projectId) {
        var requestActor = actorResolver.resolve(request);
        if (permissionGuard == null) {
            throw new IllegalStateException("项目成员权限守卫未配置");
        }
        permissionGuard.assertCanManageProject(projectId, requestActor);
        return requestActor;
    }

    /**
     * 解析当前请求用户并校验其与业务请求体中的操作者一致。
     *
     * <p>该方法只校验请求身份和操作者一致性，不附加项目成员校验，用于保持本地命令、
     * 文件写入和审批等已有接口的权限语义。</p>
     *
     * @param request 当前 HTTP 请求。
     * @param expectedActorId 请求体或路径中声明的操作者 ID。
     * @return 归一化后的当前用户 ID。
     */
    public String assertActor(HttpServletRequest request, String expectedActorId) {
        var requestActor = actorResolver.resolve(request);
        if (expectedActorId == null || expectedActorId.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "操作人不能为空");
        }
        if (!requestActor.equals(expectedActorId.trim())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "请求身份与操作人不一致");
        }
        return requestActor;
    }

    /**
     * 解析当前请求用户，先确认其为项目有效成员，再校验其与业务操作者一致。
     *
     * <p>该组合守卫用于部署、Compose 和工作流等项目成员可操作的写入口。空操作者保持历史
     * 403 语义，避免替换控制器私有方法时改变既有接口错误码。</p>
     *
     * @param request 当前 HTTP 请求。
     * @param projectId 当前项目 ID。
     * @param expectedActorId 请求体中声明的操作者 ID。
     * @return 归一化后的当前用户 ID。
     */
    public String assertProjectMemberActor(HttpServletRequest request, String projectId, String expectedActorId) {
        var requestActor = assertProjectMember(request, projectId);
        if (expectedActorId == null || expectedActorId.isBlank() || !requestActor.equals(expectedActorId.trim())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "请求身份和操作者不一致");
        }
        return requestActor;
    }
}
