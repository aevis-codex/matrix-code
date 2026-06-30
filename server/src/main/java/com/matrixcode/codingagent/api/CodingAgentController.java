package com.matrixcode.codingagent.api;

import com.matrixcode.codingagent.application.CodingAgentExecutionService;
import com.matrixcode.codingagent.application.CodingAgentHandoffService;
import com.matrixcode.codingagent.application.CodingAgentPatchService;
import com.matrixcode.codingagent.application.CodingAgentTaskService;
import com.matrixcode.codingagent.domain.CodingAgentExecutionPlan;
import com.matrixcode.codingagent.domain.CodingAgentPatchResult;
import com.matrixcode.codingagent.domain.CodingAgentTask;
import com.matrixcode.identity.api.ProjectRequestPermissionGuard;
import com.matrixcode.modelgateway.domain.ModelRole;
import com.matrixcode.workbench.domain.DocumentSummary;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 编码智能体受控执行 API。
 *
 * <p>作用域：当前操作者；场景：生成编码计划、应用受控 Patch、记录交付文档和关联 Agent 运行。</p>
 */
@RestController
@RequestMapping("/api/projects/{projectId}/roles/{role}/coding-agent")
public class CodingAgentController {

    private final CodingAgentTaskService taskService;
    private final CodingAgentExecutionService executionService;
    private final CodingAgentPatchService patchService;
    private final CodingAgentHandoffService handoffService;
    private final ProjectRequestPermissionGuard requestPermissionGuard;

    public CodingAgentController(
            CodingAgentTaskService taskService,
            CodingAgentExecutionService executionService,
            CodingAgentPatchService patchService,
            CodingAgentHandoffService handoffService,
            ProjectRequestPermissionGuard requestPermissionGuard
    ) {
        this.taskService = taskService;
        this.executionService = executionService;
        this.patchService = patchService;
        this.handoffService = handoffService;
        this.requestPermissionGuard = requestPermissionGuard;
    }

    /**
     * 创建编码智能体任务计划。
     *
     * <p>作用域：当前操作者；场景：把开发目标和工作区转为可审计的编码任务。</p>
     */
    @PostMapping("/tasks")
    public CodingAgentTask createTask(
            @PathVariable String projectId,
            @PathVariable String role,
            @RequestBody CodingAgentTaskCommand command,
            HttpServletRequest request
    ) {
        requestPermissionGuard.assertActor(request, command.actorId());
        return taskService.plan(projectId, ModelRole.fromPath(role), command.goal(), command.workspaceId(), command.actorId());
    }

    /**
     * 准备编码智能体执行计划。
     *
     * <p>作用域：当前操作者；场景：生成执行步骤、测试命令和后续本地命令审批入口。</p>
     */
    @PostMapping("/execution-plans")
    public CodingAgentExecutionPlan prepareExecution(
            @PathVariable String projectId,
            @PathVariable String role,
            @RequestBody CodingAgentExecutionCommand command,
            HttpServletRequest request
    ) {
        requestPermissionGuard.assertActor(request, command.actorId());
        return executionService.prepare(
                projectId,
                ModelRole.fromPath(role),
                command.goal(),
                command.workspaceId(),
                command.actorId(),
                command.testCommand()
        );
    }

    /**
     * 应用编码智能体 Patch。
     *
     * <p>作用域：当前操作者；场景：把已审批的文件变更写入授权工作区并记录 diff。</p>
     */
    @PostMapping("/patches")
    public CodingAgentPatchResult applyPatch(
            @PathVariable String projectId,
            @PathVariable String role,
            @RequestBody CodingAgentPatchCommand command,
            HttpServletRequest request
    ) {
        requestPermissionGuard.assertActor(request, command.actorId());
        return patchService.apply(
                projectId,
                ModelRole.fromPath(role),
                command.workspaceId(),
                command.actorId(),
                command.relativePath(),
                command.expectedContent(),
                command.nextContent(),
                command.summary(),
                command.approved(),
                command.runId()
        );
    }

    /**
     * 记录编码智能体交接文档。
     *
     * <p>作用域：当前操作者；场景：开发完成后沉淀实现、测试、diff 和交付结论。</p>
     */
    @PostMapping("/handoffs")
    public DocumentSummary recordHandoff(
            @PathVariable String projectId,
            @PathVariable String role,
            @RequestBody CodingAgentHandoffCommand command,
            HttpServletRequest request
    ) {
        requestPermissionGuard.assertActor(request, command.actorId());
        return handoffService.record(
                projectId,
                ModelRole.fromPath(role),
                command.workspaceId(),
                command.actorId(),
                command.goal(),
                command.relativePath(),
                command.patchSummary(),
                command.diffSummary(),
                command.testTaskId(),
                command.testTaskStatus(),
                command.testCommand(),
                command.deliveryConclusion(),
                command.runId()
        );
    }

    /**
     * 创建编码智能体任务的请求体。
     *
     * <p>作用域：开发角色工作台；场景：把开发目标、授权工作区和操作者绑定成可排队执行的编码任务。</p>
     */
    public record CodingAgentTaskCommand(String goal, String workspaceId, String actorId) {
    }

    /**
     * 启动编码智能体执行的请求体。
     *
     * <p>作用域：开发角色工作台；场景：在授权工作区内生成执行计划并可选关联测试命令。</p>
     */
    public record CodingAgentExecutionCommand(String goal, String workspaceId, String actorId, String testCommand) {
    }

    /**
     * 提交编码智能体 Patch 的请求体。
     *
     * <p>作用域：开发角色工作台；场景：在人工批准后把期望内容和新内容写入指定相对路径，并关联运行记录。</p>
     */
    public record CodingAgentPatchCommand(
            String workspaceId,
            String actorId,
            String relativePath,
            String expectedContent,
            String nextContent,
            String summary,
            boolean approved,
            String runId
    ) {
    }

    /**
     * 生成编码智能体交接文档的请求体。
     *
     * <p>作用域：开发角色工作台；场景：汇总代码变更、测试结果和交付结论，交给测试或部署角色继续处理。</p>
     */
    public record CodingAgentHandoffCommand(
            String workspaceId,
            String actorId,
            String goal,
            String relativePath,
            String patchSummary,
            String diffSummary,
            String testTaskId,
            String testTaskStatus,
            String testCommand,
            String deliveryConclusion,
            String runId
    ) {
    }
}
