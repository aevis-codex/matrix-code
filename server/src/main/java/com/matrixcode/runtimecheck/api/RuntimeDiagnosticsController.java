package com.matrixcode.runtimecheck.api;

import com.matrixcode.identity.api.ProjectRequestPermissionGuard;
import com.matrixcode.runtimecheck.application.RuntimeDiagnosticsService;
import com.matrixcode.runtimecheck.domain.RuntimeDiagnosticsReport;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 运行诊断 API。
 *
 * <p>作用域：项目成员；场景：检查 MySQL、Milvus、Redis、RocketMQ 和配置门禁状态。</p>
 */
@RestController
@RequestMapping("/api/projects/{projectId}/runtime-diagnostics")
public class RuntimeDiagnosticsController {

    private final RuntimeDiagnosticsService diagnosticsService;
    private final ProjectRequestPermissionGuard requestPermissionGuard;

    public RuntimeDiagnosticsController(
            RuntimeDiagnosticsService diagnosticsService,
            ProjectRequestPermissionGuard requestPermissionGuard
    ) {
        this.diagnosticsService = diagnosticsService;
        this.requestPermissionGuard = requestPermissionGuard;
    }

    /**
     * 生成当前运行环境诊断报告。
     */
    @GetMapping
    public RuntimeDiagnosticsReport inspect(@PathVariable String projectId, HttpServletRequest request) {
        requestPermissionGuard.assertProjectMember(request, projectId);
        return diagnosticsService.inspect();
    }
}
