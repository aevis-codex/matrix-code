package com.matrixcode.agentruntime.application;

import com.matrixcode.agentruntime.domain.AgentRunRecord;
import com.matrixcode.agentruntime.domain.AgentRuntimeWorkerModelExecutionResult;
import com.matrixcode.modelgateway.application.ModelGatewayService;
import com.matrixcode.modelgateway.domain.ModelRequestCommand;
import com.matrixcode.modelgateway.domain.ModelResponse;
import com.matrixcode.modelgateway.domain.ModelRole;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.Objects;

@Service
public class AgentRuntimeWorkerModelExecutionService {

    private static final int ANSWER_SUMMARY_LIMIT = 160;

    private final AgentRuntimeService agentRuntimeService;
    private final AgentRuntimeWorkerService workerService;
    private final ModelGatewayService modelGatewayService;

    public AgentRuntimeWorkerModelExecutionService(
            AgentRuntimeService agentRuntimeService,
            AgentRuntimeWorkerService workerService,
            ModelGatewayService modelGatewayService
    ) {
        this.agentRuntimeService = Objects.requireNonNull(agentRuntimeService, "agentRuntimeService 不能为空");
        this.workerService = Objects.requireNonNull(workerService, "workerService 不能为空");
        this.modelGatewayService = Objects.requireNonNull(modelGatewayService, "modelGatewayService 不能为空");
    }

    /**
     * 在 Worker 租约守卫下触发一次模型网关请求。
     *
     * <p>方法先复用执行计划守卫，确保运行属于当前项目、处于运行中、由当前 Worker 认领且租约未过期。
     * 通过后只调用模型网关，并通过 `agentRunId` 复用已有模型请求强关联和 `TOOL_TRACE` 写入链路。
     * 方法不会执行命令、读写文件或应用 Patch，也不会把模型请求成功直接标记为整个运行成功。</p>
     *
     * @param projectId 项目 ID。
     * @param runId 运行 ID。
     * @param workerId Worker ID；为空时由 Worker 服务归一为 system。
     * @return 模型请求执行结果或阻塞原因。
     */
    public AgentRuntimeWorkerModelExecutionResult executeModelRequest(
            String projectId,
            String runId,
            String workerId
    ) {
        var plan = workerService.prepareExecution(projectId, runId, workerId);
        if (!plan.executable()) {
            return AgentRuntimeWorkerModelExecutionResult.blocked(
                    plan.projectId(),
                    plan.runId(),
                    plan.workerId(),
                    plan.blockedReason()
            );
        }
        var run = agentRuntimeService.findRun(plan.runId())
                .filter(candidate -> candidate.projectId().equals(plan.projectId()))
                .orElseThrow(() -> new IllegalStateException("运行不存在或不属于当前项目"));
        var response = modelGatewayService.request(new ModelRequestCommand(
                plan.projectId(),
                modelRole(run),
                plan.workerId(),
                plan.runId(),
                instructionFor(run),
                List.of()
        ));
        var answerSummary = summarize(response.answer());
        appendCompletedEvent(run, plan.workerId(), response, answerSummary);
        return AgentRuntimeWorkerModelExecutionResult.executed(
                plan.projectId(),
                plan.runId(),
                plan.workerId(),
                response.requestId(),
                response.binding().providerId(),
                response.binding().model(),
                answerSummary
        );
    }

    private ModelRole modelRole(AgentRunRecord run) {
        try {
            return ModelRole.valueOf(run.roleKey());
        } catch (IllegalArgumentException exception) {
            throw new IllegalStateException("运行角色不支持模型请求：" + run.roleKey(), exception);
        }
    }

    private String instructionFor(AgentRunRecord run) {
        return """
                请在 MatrixCode Agent Runtime 中执行一次受控模型步骤。

                运行目标：%s
                当前摘要：%s
                Agent 类型：%s

                输出要求：
                - 只给出本次模型步骤的阶段性结果。
                - 不假设已经执行命令、读取文件、写入文件或应用 Patch。
                - 如果需要后续工具动作，请明确标记为需要审批。
                """.formatted(run.goal(), run.summary(), run.agentKind());
    }

    private void appendCompletedEvent(
            AgentRunRecord run,
            String workerId,
            ModelResponse response,
            String answerSummary
    ) {
        agentRuntimeService.appendEvent(
                run.id(),
                run.projectId(),
                "WORKER_MODEL_REQUEST_COMPLETED",
                "Worker 模型请求已完成",
                Map.of(
                        "workerId", workerId,
                        "requestId", response.requestId(),
                        "providerId", response.binding().providerId(),
                        "modelName", response.binding().model(),
                        "cacheSource", response.usage().cacheSource(),
                        "answerSummary", answerSummary
                )
        );
    }

    private String summarize(String answer) {
        var normalized = answer == null ? "" : answer.replace('\n', ' ').strip();
        return normalized.length() <= ANSWER_SUMMARY_LIMIT
                ? normalized
                : normalized.substring(0, ANSWER_SUMMARY_LIMIT);
    }
}
