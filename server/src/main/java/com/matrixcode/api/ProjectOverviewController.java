package com.matrixcode.api;

import com.matrixcode.identity.api.ProjectRequestPermissionGuard;
import com.matrixcode.workbench.application.WorkbenchService;
import com.matrixcode.workbench.domain.RoleSummary;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 项目概览 API。
 *
 * <p>作用域：项目成员；场景：工作台顶部展示项目、角色、阶段和模型指标摘要。</p>
 */
@RestController
@RequestMapping("/api/projects")
public class ProjectOverviewController {

    private static final List<String> STAGES = List.of(
            "需求录入",
            "需求草稿",
            "开发中",
            "缺陷处理中",
            "测试中",
            "待产品验收",
            "待运维配置",
            "上线准备"
    );

    private final WorkbenchService service;
    private final ProjectRequestPermissionGuard requestPermissionGuard;

    public ProjectOverviewController(
            WorkbenchService service,
            ProjectRequestPermissionGuard requestPermissionGuard
    ) {
        this.service = service;
        this.requestPermissionGuard = requestPermissionGuard;
    }

    /**
     * 读取项目概览。
     */
    @GetMapping("/{projectId}/overview")
    public ProjectOverview overview(@PathVariable String projectId, HttpServletRequest request) {
        requestPermissionGuard.assertProjectMember(request, projectId);
        var workbench = service.get(projectId);
        var metrics = workbench.metrics();
        return new ProjectOverview(
                workbench.projectId(),
                workbench.projectName(),
                workbench.roles().stream().map(RoleSummary::name).toList(),
                STAGES,
                metrics.cacheHitRate(),
                metrics.sessionTokens(),
                workbench.currentStage()
        );
    }
}
