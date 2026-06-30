package com.matrixcode.agentruntime.api;

import com.matrixcode.agentruntime.application.AgentRuntimeWorkerModelExecutionService;
import com.matrixcode.agentruntime.domain.AgentRuntimeWorkerModelExecutionResult;
import com.matrixcode.identity.api.ProjectRequestPermissionGuard;
import com.matrixcode.identity.api.RequestActorResolver;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 受控 Worker 模型执行 REST 接口。
 *
 * <p>作用域：项目内 Agent Runtime Worker API。主要场景是后台 Worker 在持有运行租约后，
 * 通过模型网关生成下一步执行内容并记录低敏审计事件。该控制器不是开放式模型调用入口，
 * 运行身份、租约和状态边界由服务层统一校验。</p>
 */
@RestController
@RequestMapping("/api/projects/{projectId}/agent-runs")
public class AgentRuntimeWorkerModelExecutionController {

    private final AgentRuntimeWorkerModelExecutionService executionService;
    private final ProjectRequestPermissionGuard requestPermissionGuard;

    public AgentRuntimeWorkerModelExecutionController(AgentRuntimeWorkerModelExecutionService executionService) {
        this(executionService, new ProjectRequestPermissionGuard(new RequestActorResolver()));
    }

    @Autowired
    public AgentRuntimeWorkerModelExecutionController(
            AgentRuntimeWorkerModelExecutionService executionService,
            ProjectRequestPermissionGuard requestPermissionGuard
    ) {
        this.executionService = executionService;
        this.requestPermissionGuard = requestPermissionGuard;
    }

    /**
     * 在受控 Worker 租约下触发一次模型网关请求。
     *
     * <p>接口只调用模型网关并写入低敏运行事件，不执行命令、不读写文件、不应用 Patch。
     * 运行状态、认领人和租约边界由服务层统一校验。</p>
     */
    @PostMapping("/{runId}/worker-model-request")
    public AgentRuntimeWorkerModelExecutionResult workerModelRequest(
            @PathVariable String projectId,
            @PathVariable String runId,
            @RequestParam(defaultValue = "system") String workerId,
            HttpServletRequest request
    ) {
        requestPermissionGuard.assertActor(request, workerId);
        return executionService.executeModelRequest(projectId, runId, workerId);
    }
}
