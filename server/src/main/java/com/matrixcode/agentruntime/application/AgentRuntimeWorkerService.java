package com.matrixcode.agentruntime.application;

import com.matrixcode.agentruntime.domain.AgentRuntimeWorkerTickResult;
import com.matrixcode.agentruntime.domain.AgentRuntimeWorkerExecutionPlan;
import com.matrixcode.agentruntime.domain.AgentRuntimeWorkerExecutionStep;
import com.matrixcode.agentruntime.domain.AgentRunRecord;
import com.matrixcode.agentruntime.domain.AgentRunStatus;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.util.List;
import java.util.Objects;

@Service
public class AgentRuntimeWorkerService {

    private static final String SYSTEM_WORKER = "system";
    private static final int DEFAULT_EXPIRE_LIMIT = 20;

    private final AgentRuntimeService agentRuntimeService;
    private final Clock clock;

    @Autowired
    public AgentRuntimeWorkerService(AgentRuntimeService agentRuntimeService) {
        this(agentRuntimeService, Clock.systemUTC());
    }

    AgentRuntimeWorkerService(AgentRuntimeService agentRuntimeService, Clock clock) {
        this.agentRuntimeService = Objects.requireNonNull(agentRuntimeService, "agentRuntimeService 不能为空");
        this.clock = clock == null ? Clock.systemUTC() : clock;
    }

    /**
     * 执行一次受控 Worker tick。
     *
     * <p>tick 的职责故意保持很窄：先回收当前项目中过期的 `RUNNING` 租约，再尝试认领一条新的 `QUEUED`
     * 运行。该方法不启动后台线程、不调用模型、不执行命令、不写文件、不应用 Patch；真实执行 handler
     * 在后续阶段接入时可以复用这里的租约治理边界。</p>
     *
     * @param projectId 项目 ID。
     * @param workerId Worker ID；为空时归一为 system。
     * @return 本次 tick 的低敏摘要。
     */
    public AgentRuntimeWorkerTickResult tick(String projectId, String workerId) {
        var normalizedProjectId = requireText(projectId, "项目编号不能为空");
        var normalizedWorkerId = workerOrSystem(workerId);
        var expiredRuns = agentRuntimeService.expireRunningLeases(normalizedProjectId, DEFAULT_EXPIRE_LIMIT);
        var claimedRun = agentRuntimeService.claimNextQueuedRun(normalizedProjectId, normalizedWorkerId).orElse(null);
        return new AgentRuntimeWorkerTickResult(
                normalizedProjectId,
                normalizedWorkerId,
                clock.instant(),
                expiredRuns.size(),
                claimedRun
        );
    }

    /**
     * 为已认领的运行生成受控 Worker 执行计划。
     *
     * <p>该方法只做状态机准备和审计：校验运行属于当前项目、处于 `RUNNING`、由当前 Worker 认领且租约未过期，
     * 然后按 Agent 类型生成低敏执行步骤并追加 `WORKER_EXECUTION_PREPARED`。它不调用模型、不执行命令、
     * 不读写文件、不应用 Patch；后续真实执行器必须继续使用这些步骤上的审批状态和工具边界。</p>
     *
     * @param projectId 项目 ID。
     * @param runId 运行 ID。
     * @param workerId Worker ID；为空时归一为 system。
     * @return 可执行计划或带阻塞原因的计划。
     */
    public AgentRuntimeWorkerExecutionPlan prepareExecution(String projectId, String runId, String workerId) {
        var normalizedProjectId = requireText(projectId, "项目编号不能为空");
        var normalizedRunId = requireText(runId, "运行编号不能为空");
        var normalizedWorkerId = workerOrSystem(workerId);
        var plannedAt = clock.instant();
        var run = agentRuntimeService.findRun(normalizedRunId)
                .filter(candidate -> candidate.projectId().equals(normalizedProjectId));
        if (run.isEmpty()) {
            return blocked(normalizedProjectId, normalizedRunId, normalizedWorkerId, plannedAt, "运行不存在或不属于当前项目");
        }
        var currentRun = run.get();
        var blockedReason = blockedReason(currentRun, normalizedWorkerId, plannedAt);
        if (!blockedReason.isBlank()) {
            return blocked(normalizedProjectId, normalizedRunId, normalizedWorkerId, plannedAt, blockedReason);
        }
        var steps = stepsFor(currentRun);
        agentRuntimeService.appendEvent(
                currentRun.id(),
                currentRun.projectId(),
                "WORKER_EXECUTION_PREPARED",
                "Worker 执行计划已生成",
                java.util.Map.of(
                        "workerId", normalizedWorkerId,
                        "agentKind", currentRun.agentKind(),
                        "role", currentRun.roleKey(),
                        "stepCount", steps.size()
                )
        );
        return AgentRuntimeWorkerExecutionPlan.executable(
                normalizedProjectId,
                normalizedRunId,
                normalizedWorkerId,
                plannedAt,
                steps
        );
    }

    private AgentRuntimeWorkerExecutionPlan blocked(
            String projectId,
            String runId,
            String workerId,
            java.time.Instant plannedAt,
            String reason
    ) {
        return AgentRuntimeWorkerExecutionPlan.blocked(projectId, runId, workerId, plannedAt, reason);
    }

    private String blockedReason(AgentRunRecord run, String workerId, java.time.Instant plannedAt) {
        if (run.status() != AgentRunStatus.RUNNING) {
            return "只有运行中任务可以生成执行计划";
        }
        if (run.claimedByUserId() == null) {
            return "运行未建立 Worker 租约";
        }
        if (!run.claimedByUserId().equals(workerId)) {
            return "当前 Worker 不是运行认领人";
        }
        if (run.claimExpiresAt() == null) {
            return "运行租约缺失";
        }
        if (!run.claimExpiresAt().isAfter(plannedAt)) {
            return "运行租约已过期";
        }
        return "";
    }

    private List<AgentRuntimeWorkerExecutionStep> stepsFor(AgentRunRecord run) {
        if ("coding".equalsIgnoreCase(run.agentKind())) {
            return codingSteps();
        }
        return genericSteps();
    }

    private List<AgentRuntimeWorkerExecutionStep> codingSteps() {
        return List.of(
                step(1, "CONTEXT_RECALL", "召回项目上下文", "model-gateway.vector-context", "READY", false,
                        "召回项目图谱、需求、相关代码和最近验证证据。"),
                step(2, "PLAN_REVIEW", "生成并审查实现计划", "model-gateway.model-requests", "REVIEW_REQUIRED", false,
                        "生成小步计划，等待人工或后续策略审查。"),
                step(3, "FILE_REVIEW", "读取相关文件", "local-execution.files.read", "READY", false,
                        "只读取授权工作区内与任务相关的源码、测试和文档。"),
                step(4, "CODE_EDIT", "执行最小代码修改", "local-execution.files.write", "APPROVAL_REQUIRED", true,
                        "代码写入必须继续通过文件写入审批边界。"),
                step(5, "TEST_COMMAND", "运行聚焦测试", "local-execution.commands", "APPROVAL_REQUIRED", true,
                        "测试命令必须继续通过本地执行审批。"),
                step(6, "DIFF_REVIEW", "审查 Git diff", "local-execution.git-diff", "READY", false,
                        "检查是否包含无关改动、密钥或风险文件。"),
                step(7, "HANDOFF", "交付与回溯", "obsidian.project-graph", "REVIEW_REQUIRED", false,
                        "更新阶段计划、项目图谱和验证记录。")
        );
    }

    private List<AgentRuntimeWorkerExecutionStep> genericSteps() {
        return List.of(
                step(1, "CONTEXT_RECALL", "召回项目上下文", "model-gateway.vector-context", "READY", false,
                        "召回角色相关上下文。"),
                step(2, "MODEL_REQUEST", "生成角色响应", "model-gateway.model-requests", "READY", false,
                        "通过模型网关生成低敏响应摘要。"),
                step(3, "TOOL_APPROVAL", "确认工具边界", "local-execution.approvals", "APPROVAL_REQUIRED", true,
                        "涉及命令、文件或部署动作时必须等待审批。"),
                step(4, "HANDOFF", "交付与回溯", "obsidian.project-graph", "REVIEW_REQUIRED", false,
                        "沉淀运行结果、验证证据和后续动作。")
        );
    }

    private AgentRuntimeWorkerExecutionStep step(
            int order,
            String stepKey,
            String title,
            String toolName,
            String status,
            boolean requiresApproval,
            String summary
    ) {
        return new AgentRuntimeWorkerExecutionStep(order, stepKey, title, toolName, status, requiresApproval, summary);
    }

    private String workerOrSystem(String value) {
        if (value == null || value.isBlank()) {
            return SYSTEM_WORKER;
        }
        return value.trim();
    }

    private String requireText(String value, String message) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(message);
        }
        return value.trim();
    }
}
