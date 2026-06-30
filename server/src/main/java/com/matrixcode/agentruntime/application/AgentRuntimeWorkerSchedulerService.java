package com.matrixcode.agentruntime.application;

import com.matrixcode.agentruntime.domain.AgentRuntimeWorkerSchedulerResult;
import org.springframework.stereotype.Service;

import java.util.Objects;

/**
 * Agent Runtime Worker 单次调度服务。
 *
 * <p>该服务把已有的租约回收、队列认领和受控模型步骤串成一次可重复调用的调度循环。
 * 它不会执行本地命令、写文件、应用 Patch 或自动批准审批项；即使开启模型步骤，也只通过
 * `AgentRuntimeWorkerModelExecutionService` 走现有租约守卫和模型网关审计链路。</p>
 */
@Service
public class AgentRuntimeWorkerSchedulerService {

    private final AgentRuntimeWorkerSchedulerProperties properties;
    private final AgentRuntimeWorkerService workerService;
    private final AgentRuntimeWorkerModelExecutionService modelExecutionService;

    public AgentRuntimeWorkerSchedulerService(
            AgentRuntimeWorkerSchedulerProperties properties,
            AgentRuntimeWorkerService workerService,
            AgentRuntimeWorkerModelExecutionService modelExecutionService
    ) {
        this.properties = Objects.requireNonNull(properties, "properties 不能为空");
        this.workerService = Objects.requireNonNull(workerService, "workerService 不能为空");
        this.modelExecutionService = Objects.requireNonNull(modelExecutionService, "modelExecutionService 不能为空");
    }

    /**
     * 执行一次 Worker 调度。
     *
     * <p>关闭时直接返回 `enabled=false`，不会触碰运行队列。开启时先执行一次 `tick`，即回收过期租约并认领
     * 下一条排队运行；若配置允许模型步骤且本次确实认领到运行，再触发受控模型请求。</p>
     *
     * @return 单次调度的低敏结果。
     */
    public AgentRuntimeWorkerSchedulerResult runOnce() {
        var projectId = properties.getProjectId();
        var workerId = properties.getWorkerId();
        if (!properties.isEnabled()) {
            return AgentRuntimeWorkerSchedulerResult.disabled(projectId, workerId);
        }
        var tickResult = workerService.tick(projectId, workerId);
        var claimedRun = tickResult.claimedRun();
        if (claimedRun == null) {
            return new AgentRuntimeWorkerSchedulerResult(
                    projectId,
                    workerId,
                    true,
                    true,
                    tickResult.expiredRunCount(),
                    "",
                    false,
                    "",
                    ""
            );
        }
        if (!properties.isExecuteModelRequest()) {
            return new AgentRuntimeWorkerSchedulerResult(
                    projectId,
                    workerId,
                    true,
                    true,
                    tickResult.expiredRunCount(),
                    claimedRun.id(),
                    false,
                    "",
                    ""
            );
        }
        var modelResult = modelExecutionService.executeModelRequest(projectId, claimedRun.id(), workerId);
        return new AgentRuntimeWorkerSchedulerResult(
                projectId,
                workerId,
                true,
                true,
                tickResult.expiredRunCount(),
                claimedRun.id(),
                modelResult.executed(),
                modelResult.requestId(),
                modelResult.blockedReason()
        );
    }
}
