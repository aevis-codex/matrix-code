package com.matrixcode.agentruntime.api;

import com.matrixcode.agentruntime.application.AgentRuntimeService;
import com.matrixcode.agentruntime.application.AgentRuntimeWorkerService;
import com.matrixcode.agentruntime.domain.AgentRuntimeWorkerExecutionPlan;
import com.matrixcode.agentruntime.domain.AgentRuntimeWorkerTickResult;
import com.matrixcode.agentruntime.domain.AgentRunEventRecord;
import com.matrixcode.agentruntime.domain.AgentRunRecoveryPlan;
import com.matrixcode.agentruntime.domain.AgentRunRecord;
import com.matrixcode.identity.api.ProjectRequestPermissionGuard;
import com.matrixcode.identity.api.RequestActorResolver;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;

/**
 * Agent 运行编排 REST 接口。
 *
 * <p>作用域：项目内 Agent Runtime API。主要场景是工作台展示运行记录、查看事件时间线、失败重试、
 * Worker 认领任务和租约续期。控制器只负责 HTTP 入参、项目成员校验和状态入口转发，具体运行状态机由
 * {@link AgentRuntimeService} 与 {@link AgentRuntimeWorkerService} 负责。</p>
 */
@RestController
@RequestMapping("/api/projects/{projectId}/agent-runs")
public class AgentRuntimeController {

    private final AgentRuntimeService agentRuntimeService;
    private final AgentRuntimeWorkerService agentRuntimeWorkerService;
    private final ProjectRequestPermissionGuard requestPermissionGuard;

    public AgentRuntimeController(AgentRuntimeService agentRuntimeService) {
        this(
                agentRuntimeService,
                new AgentRuntimeWorkerService(agentRuntimeService),
                new ProjectRequestPermissionGuard(new RequestActorResolver())
        );
    }

    @Autowired
    public AgentRuntimeController(
            AgentRuntimeService agentRuntimeService,
            AgentRuntimeWorkerService agentRuntimeWorkerService,
            ProjectRequestPermissionGuard requestPermissionGuard
    ) {
        this.agentRuntimeService = agentRuntimeService;
        this.agentRuntimeWorkerService = agentRuntimeWorkerService;
        this.requestPermissionGuard = requestPermissionGuard;
    }

    /**
     * 查询项目最近的 Agent 运行记录。
     *
     * <p>该接口用于工作台运行中心和右侧关键事件区域。默认返回 20 条，limit 小于 1 时由业务服务返回空列表；
     * 控制器不做角色过滤，避免前端无法看到跨角色协作的完整运行历史。</p>
     */
    @GetMapping
    public List<AgentRunRecord> recentRuns(
            @PathVariable String projectId,
            @RequestParam(defaultValue = "20") int limit,
            HttpServletRequest request
    ) {
        requestPermissionGuard.assertProjectMember(request, projectId);
        return agentRuntimeService.recentRuns(projectId, limit);
    }

    /**
     * 查询单次 Agent 运行的事件时间线。
     *
     * <p>返回值按仓储顺序输出，调用方可以直接用于运行详情、审计复盘或失败诊断。</p>
     */
    @GetMapping("/{runId}/events")
    public List<AgentRunEventRecord> events(
            @PathVariable String projectId,
            @PathVariable String runId,
            HttpServletRequest request
    ) {
        requestPermissionGuard.assertProjectMember(request, projectId);
        return agentRuntimeService.eventsForRun(runId).stream()
                .filter(event -> event.projectId().equals(projectId))
                .toList();
    }

    /**
     * 查询单次 Agent 运行的失败恢复计划。
     *
     * <p>该接口只读，不创建新运行，也不触发本地执行。前端运行中心可据此判断是否展示重试入口；
     * 后续后台调度器也可以复用该判断，避免跨项目或不可恢复运行被错误排队。</p>
     */
    @GetMapping("/{runId}/recovery-plan")
    public AgentRunRecoveryPlan recoveryPlan(
            @PathVariable String projectId,
            @PathVariable String runId,
            HttpServletRequest request
    ) {
        requestPermissionGuard.assertProjectMember(request, projectId);
        return agentRuntimeService.recoveryPlan(projectId, runId);
    }

    /**
     * 从可重试失败运行创建新的排队运行。
     *
     * <p>接口只做恢复排队和事件审计，不消费队列、不执行命令、不调用模型。业务不可恢复时返回
     * {@code 409 CONFLICT}，调用方可以直接展示服务层给出的阻塞原因。</p>
     */
    @PostMapping("/{runId}/retry")
    public AgentRunRecord retry(
            @PathVariable String projectId,
            @PathVariable String runId,
            @RequestParam(defaultValue = "system") String actorUserId,
            HttpServletRequest request
    ) {
        requestPermissionGuard.assertActor(request, actorUserId);
        try {
            return agentRuntimeService.queueRetry(projectId, runId, actorUserId);
        } catch (IllegalStateException exception) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, exception.getMessage(), exception);
        }
    }

    /**
     * 将排队中的 Agent 运行认领为运行中。
     *
     * <p>接口只更新运行主状态并追加审计事件，不执行模型请求、本地命令、文件写入或 Patch。
     * 非排队运行返回 {@code 409 CONFLICT}，避免 Worker 或人工操作者误消费已经开始、完成或失败的运行。</p>
     */
    @PostMapping("/{runId}/claim")
    public AgentRunRecord claim(
            @PathVariable String projectId,
            @PathVariable String runId,
            @RequestParam(defaultValue = "system") String actorUserId,
            HttpServletRequest request
    ) {
        requestPermissionGuard.assertActor(request, actorUserId);
        try {
            return agentRuntimeService.claimQueuedRun(projectId, runId, actorUserId);
        } catch (IllegalStateException exception) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, exception.getMessage(), exception);
        }
    }

    /**
     * 从项目队列中认领下一条排队 Agent 运行。
     *
     * <p>该接口面向受控 Worker 和人工运行中心按钮，只负责把最早 `QUEUED` 运行推进到 `RUNNING` 并记录租约事件。
     * 队列为空时返回 {@code 204 NO_CONTENT}，调用方可按无新任务处理。</p>
     */
    @PostMapping("/claim-next")
    public ResponseEntity<AgentRunRecord> claimNext(
            @PathVariable String projectId,
            @RequestParam(defaultValue = "system") String actorUserId,
            HttpServletRequest request
    ) {
        requestPermissionGuard.assertActor(request, actorUserId);
        return agentRuntimeService.claimNextQueuedRun(projectId, actorUserId)
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.noContent().build());
    }

    /**
     * 续期运行中的 Agent 运行租约。
     *
     * <p>该接口面向真实 Worker 心跳。只有当前认领人可以刷新租约；运行不存在、已结束或认领人不匹配时返回
     * {@code 204 NO_CONTENT}。接口不执行任何模型、命令、文件或 Patch 操作。</p>
     */
    @PostMapping("/{runId}/renew-lease")
    public ResponseEntity<AgentRunRecord> renewLease(
            @PathVariable String projectId,
            @PathVariable String runId,
            @RequestParam(defaultValue = "system") String actorUserId,
            HttpServletRequest request
    ) {
        requestPermissionGuard.assertActor(request, actorUserId);
        return agentRuntimeService.renewClaimLease(projectId, runId, actorUserId)
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.noContent().build());
    }

    /**
     * 执行一次受控 Worker tick。
     *
     * <p>tick 先回收过期 `RUNNING` 租约，再认领一条 `QUEUED` 运行。该接口是后续真实 Worker 调度器的
     * 可验证入口，不启动后台线程，也不做实际模型或工具执行。</p>
     */
    @PostMapping("/worker-tick")
    public AgentRuntimeWorkerTickResult workerTick(
            @PathVariable String projectId,
            @RequestParam(defaultValue = "system") String workerId,
            HttpServletRequest request
    ) {
        requestPermissionGuard.assertActor(request, workerId);
        return agentRuntimeWorkerService.tick(projectId, workerId);
    }

    /**
     * 为已认领运行生成受控 Worker 执行计划。
     *
     * <p>该接口只返回执行状态机和阻塞原因，并在可执行时写入低敏审计事件；
     * 不调用模型、不执行命令、不读写文件、不应用 Patch。</p>
     */
    @PostMapping("/{runId}/worker-execution-plan")
    public AgentRuntimeWorkerExecutionPlan workerExecutionPlan(
            @PathVariable String projectId,
            @PathVariable String runId,
            @RequestParam(defaultValue = "system") String workerId,
            HttpServletRequest request
    ) {
        requestPermissionGuard.assertActor(request, workerId);
        return agentRuntimeWorkerService.prepareExecution(projectId, runId, workerId);
    }
}
