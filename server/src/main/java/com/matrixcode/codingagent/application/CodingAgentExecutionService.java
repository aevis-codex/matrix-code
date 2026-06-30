package com.matrixcode.codingagent.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.matrixcode.agentruntime.application.AgentRuntimeService;
import com.matrixcode.codingagent.domain.CodingAgentExecutionPlan;
import com.matrixcode.codingagent.domain.CodingAgentExecutionStatus;
import com.matrixcode.codingagent.domain.CodingAgentExecutionStep;
import com.matrixcode.codingagent.domain.CodingAgentStep;
import com.matrixcode.codingagent.domain.CodingAgentStepType;
import com.matrixcode.localexecution.application.LocalCommandService;
import com.matrixcode.localexecution.application.LocalGitDiffService;
import com.matrixcode.localexecution.application.WorkspaceRegistry;
import com.matrixcode.localexecution.domain.ExecutionTask;
import com.matrixcode.localexecution.domain.ExecutionTaskStatus;
import com.matrixcode.localexecution.domain.GitDiffSummary;
import com.matrixcode.modelgateway.domain.ModelRole;
import com.matrixcode.roleagent.application.RoleAgentConfigService;
import com.matrixcode.workbench.application.InMemoryWorkbenchStateStore;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

@Service
public class CodingAgentExecutionService {

    private static final String DEFAULT_TEST_COMMAND =
            "/Users/Masons/Ai/Maven/bin/mvn -Dmaven.repo.local=/Users/Masons/Ai/Maven_Ai_Store -q -pl server test";

    private final CodingAgentTaskService taskService;
    private final RoleAgentConfigService roleAgentConfigService;
    private final AgentRuntimeService agentRuntimeService;
    private final WorkspaceRegistry workspaces;
    private final LocalCommandService commandService;
    private final LocalGitDiffService gitDiffService;

    public CodingAgentExecutionService(
            CodingAgentTaskService taskService,
            WorkspaceRegistry workspaces,
            LocalCommandService commandService,
            LocalGitDiffService gitDiffService
    ) {
        this(
                taskService,
                new RoleAgentConfigService(new InMemoryWorkbenchStateStore()),
                new AgentRuntimeService(Optional.empty(), new ObjectMapper(), Clock.systemUTC()),
                workspaces,
                commandService,
                gitDiffService
        );
    }

    @Autowired
    public CodingAgentExecutionService(
            CodingAgentTaskService taskService,
            RoleAgentConfigService roleAgentConfigService,
            AgentRuntimeService agentRuntimeService,
            WorkspaceRegistry workspaces,
            LocalCommandService commandService,
            LocalGitDiffService gitDiffService
    ) {
        this.taskService = Objects.requireNonNull(taskService, "taskService 不能为空");
        this.roleAgentConfigService = Objects.requireNonNull(roleAgentConfigService, "roleAgentConfigService 不能为空");
        this.agentRuntimeService = Objects.requireNonNull(agentRuntimeService, "agentRuntimeService 不能为空");
        this.workspaces = Objects.requireNonNull(workspaces, "workspaces 不能为空");
        this.commandService = Objects.requireNonNull(commandService, "commandService 不能为空");
        this.gitDiffService = Objects.requireNonNull(gitDiffService, "gitDiffService 不能为空");
    }

    public CodingAgentExecutionPlan prepare(
            String projectId,
            ModelRole role,
            String goal,
            String workspaceId,
            String actorId,
            String testCommand
    ) {
        actorId = requireText(actorId, "执行人不能为空");
        var task = taskService.plan(projectId, role, goal, workspaceId, actorId);
        workspaces.requireAuthorized(task.projectId(), task.workspaceId());
        var testTask = commandService.submit(task.projectId(), task.workspaceId(), actorId, commandOrDefault(testCommand));
        var diffSummary = gitDiffService.capture(task.projectId(), task.workspaceId());
        var executionSteps = task.steps().stream()
                .map(step -> mapStep(step, testTask, diffSummary))
                .toList();
        var config = roleAgentConfigService.require(projectId, role);
        agentRuntimeService.markRunning(
                task.taskId(),
                projectId,
                role,
                config.agentKind(),
                actorId,
                config.providerId(),
                config.model(),
                goal,
                "执行准备已生成"
        );
        agentRuntimeService.appendEvent(task.taskId(), projectId, "EXECUTION_PREPARED", "编码执行准备已生成", Map.of(
                "workspaceId", workspaceId,
                "testTaskId", testTask.taskId(),
                "testTaskStatus", testTask.status().name(),
                "changedFileCount", diffSummary.changedFiles().size()
        ));
        agentRuntimeService.appendToolTrace(
                task.taskId(),
                projectId,
                "local-execution.commands",
                "submit-test-command",
                testTask.status().name(),
                testTask.taskId(),
                "测试命令已提交审批",
                Map.of(
                        "workspaceId", workspaceId,
                        "approvalRequired", testTask.status() == ExecutionTaskStatus.APPROVAL_PENDING
                )
        );
        agentRuntimeService.appendToolTrace(
                task.taskId(),
                projectId,
                "local-execution.git-diff",
                "capture-baseline",
                "CAPTURED",
                diffSummary.workspaceId(),
                "已采集 Git diff 基线",
                Map.of(
                        "workspaceId", workspaceId,
                        "changedFileCount", diffSummary.changedFiles().size()
                )
        );
        return new CodingAgentExecutionPlan(task, executionSteps, testTask, diffSummary);
    }

    private CodingAgentExecutionStep mapStep(CodingAgentStep step, ExecutionTask testTask, GitDiffSummary diffSummary) {
        return switch (step.type()) {
            case CONTEXT_RECALL -> mapped(step, CodingAgentExecutionStatus.READY, "",
                    "可通过向量上下文召回项目图谱、需求、代码和验证记录。");
            case PLAN_REVIEW -> mapped(step, CodingAgentExecutionStatus.REVIEW_REQUIRED, "",
                    "执行计划需要人工审查后再进入代码修改。");
            case FILE_REVIEW -> mapped(step, CodingAgentExecutionStatus.READY, "",
                    "文件读取必须继续通过授权工作区路径守卫。");
            case CODE_EDIT -> mapped(step, CodingAgentExecutionStatus.APPROVAL_REQUIRED, "",
                    "代码写入未自动执行，必须通过本地文件写入审批边界。");
            case TEST_COMMAND -> mapped(step, statusForTestTask(testTask.status()), testTask.taskId(),
                    "测试命令已提交到本地执行服务，当前状态：" + testTask.status());
            case DIFF_REVIEW -> mapped(step, CodingAgentExecutionStatus.CAPTURED, diffSummary.workspaceId(),
                    "已采集 Git diff 基线，变更文件数：" + diffSummary.changedFiles().size());
            case HANDOFF -> mapped(step, CodingAgentExecutionStatus.REVIEW_REQUIRED, "",
                    "交付回溯需要更新阶段计划、项目图谱和验证记录。");
        };
    }

    private CodingAgentExecutionStep mapped(
            CodingAgentStep step,
            CodingAgentExecutionStatus status,
            String referenceId,
            String summary
    ) {
        return new CodingAgentExecutionStep(
                step.order(),
                step.type(),
                step.title(),
                step.tool(),
                status,
                referenceId,
                summary
        );
    }

    private CodingAgentExecutionStatus statusForTestTask(ExecutionTaskStatus status) {
        if (status == ExecutionTaskStatus.APPROVAL_PENDING || status == ExecutionTaskStatus.DENIED) {
            return CodingAgentExecutionStatus.APPROVAL_REQUIRED;
        }
        return CodingAgentExecutionStatus.SUBMITTED;
    }

    private String commandOrDefault(String command) {
        if (command == null || command.isBlank()) {
            return DEFAULT_TEST_COMMAND;
        }
        return command.trim();
    }

    private String requireText(String value, String message) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(message);
        }
        return value.trim();
    }
}
