package com.matrixcode.localexecution.api;

import com.matrixcode.approval.domain.ApprovalDecision;
import com.matrixcode.identity.api.ProjectRequestPermissionGuard;
import com.matrixcode.localexecution.application.LocalCommandService;
import com.matrixcode.localexecution.application.LocalExecutionSummaryService;
import com.matrixcode.localexecution.application.LocalFileService;
import com.matrixcode.localexecution.application.LocalGitDiffService;
import com.matrixcode.localexecution.application.WorkspaceRegistry;
import com.matrixcode.localexecution.domain.DirectoryEntry;
import com.matrixcode.localexecution.domain.ExecutionTask;
import com.matrixcode.localexecution.domain.FileReadResult;
import com.matrixcode.localexecution.domain.FileWriteResult;
import com.matrixcode.localexecution.domain.GitDiffSummary;
import com.matrixcode.localexecution.domain.LocalExecutionSummary;
import com.matrixcode.localexecution.domain.LocalTaskLog;
import com.matrixcode.localexecution.domain.WorkspaceAuthorization;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 本地执行和工作区文件 API。
 *
 * <p>作用域：项目成员和当前操作者；场景：授权本地工作区、浏览文件、受控写文件、
 * 提交命令、审批命令、取消任务和采集 Git diff。</p>
 */
@RestController
@RequestMapping("/api/projects/{projectId}/local-execution")
public class LocalExecutionController {

    private final WorkspaceRegistry workspaceRegistry;
    private final LocalFileService fileService;
    private final LocalCommandService commandService;
    private final LocalGitDiffService gitDiffService;
    private final LocalExecutionSummaryService summaryService;
    private final ProjectRequestPermissionGuard requestPermissionGuard;

    public LocalExecutionController(
            WorkspaceRegistry workspaceRegistry,
            LocalFileService fileService,
            LocalCommandService commandService,
            LocalGitDiffService gitDiffService,
            LocalExecutionSummaryService summaryService,
            ProjectRequestPermissionGuard requestPermissionGuard
    ) {
        this.workspaceRegistry = workspaceRegistry;
        this.fileService = fileService;
        this.commandService = commandService;
        this.gitDiffService = gitDiffService;
        this.summaryService = summaryService;
        this.requestPermissionGuard = requestPermissionGuard;
    }

    /**
     * 授权本地工作区。
     *
     * <p>作用域：项目成员；场景：为编码智能体限定允许访问的根目录。</p>
     */
    @PostMapping("/workspaces")
    public WorkspaceAuthorization authorizeWorkspace(
            @PathVariable String projectId,
            @RequestBody WorkspaceCommand command,
            HttpServletRequest request
    ) {
        requestPermissionGuard.assertProjectMember(request, projectId);
        return workspaceRegistry.authorize(projectId, command.name(), command.rootPath());
    }

    /**
     * 读取本地执行摘要。
     *
     * <p>作用域：项目成员；场景：运行中心展示工作区、任务、审批和最近日志。</p>
     */
    @GetMapping("/summary")
    public LocalExecutionSummary summary(@PathVariable String projectId, HttpServletRequest request) {
        requestPermissionGuard.assertProjectMember(request, projectId);
        return summaryService.summary(projectId);
    }

    /**
     * 列出授权工作区目录。
     *
     * <p>作用域：项目成员；场景：文件浏览器和编码智能体上下文选择。</p>
     */
    @PostMapping("/files/list")
    public List<DirectoryEntry> listFiles(
            @PathVariable String projectId,
            @RequestBody FilePathCommand command,
            HttpServletRequest request
    ) {
        requestPermissionGuard.assertProjectMember(request, projectId);
        return fileService.list(projectId, command.workspaceId(), command.relativePath());
    }

    /**
     * 读取授权工作区文件。
     *
     * <p>作用域：项目成员；场景：预览代码、配置和文档正文。</p>
     */
    @PostMapping("/files/read")
    public FileReadResult readFile(
            @PathVariable String projectId,
            @RequestBody FilePathCommand command,
            HttpServletRequest request
    ) {
        requestPermissionGuard.assertProjectMember(request, projectId);
        return fileService.read(projectId, command.workspaceId(), command.relativePath());
    }

    /**
     * 写入授权工作区文件。
     *
     * <p>作用域：当前操作者；场景：受控 Patch、人工保存或编码智能体写入文件。</p>
     */
    @PostMapping("/files/write")
    public FileWriteResult writeFile(
            @PathVariable String projectId,
            @RequestBody FileWriteCommand command,
            HttpServletRequest request
    ) {
        requestPermissionGuard.assertActor(request, command.actorId());
        return fileService.write(projectId, command.workspaceId(), command.relativePath(), command.content());
    }

    /**
     * 提交本地命令任务。
     *
     * <p>作用域：当前操作者；场景：提交测试、构建或诊断命令进入审批/执行队列。</p>
     */
    @PostMapping("/commands")
    public ExecutionTask submitCommand(
            @PathVariable String projectId,
            @RequestBody CommandRequest command,
            HttpServletRequest request
    ) {
        requestPermissionGuard.assertActor(request, command.actorId());
        return commandService.submit(projectId, command.workspaceId(), command.actorId(), command.command());
    }

    /**
     * 审批本地命令任务。
     *
     * <p>作用域：当前操作者；场景：人工允许或拒绝高风险命令执行。</p>
     */
    @PostMapping("/commands/{taskId}/approval")
    public ExecutionTask decideCommandApproval(
            @PathVariable String projectId,
            @PathVariable String taskId,
            @RequestBody ApprovalCommand command,
            HttpServletRequest request
    ) {
        requestPermissionGuard.assertActor(request, command.actorId());
        return commandService.decide(projectId, taskId, command.actorId(), command.decision(), command.note());
    }

    /**
     * 取消本地命令任务。
     *
     * <p>作用域：当前操作者；场景：排队、审批或运行中的任务被用户终止。</p>
     */
    @PostMapping("/commands/{taskId}/cancel")
    public ExecutionTask cancelCommand(
            @PathVariable String projectId,
            @PathVariable String taskId,
            @RequestBody CancelCommand command,
            HttpServletRequest request
    ) {
        requestPermissionGuard.assertActor(request, command.actorId());
        return commandService.cancel(projectId, taskId, command.actorId(), command.note());
    }

    /**
     * 读取本地命令日志。
     *
     * <p>作用域：项目成员；场景：查看 stdout、stderr 和系统事件。</p>
     */
    @GetMapping("/commands/{taskId}/logs")
    public List<LocalTaskLog> commandLogs(
            @PathVariable String projectId,
            @PathVariable String taskId,
            HttpServletRequest request
    ) {
        requestPermissionGuard.assertProjectMember(request, projectId);
        return commandService.logsForTask(projectId, taskId);
    }

    /**
     * 捕获授权工作区 Git diff。
     *
     * <p>作用域：项目成员；场景：交付回溯、代码审查和智能体 Patch 前后对比。</p>
     */
    @PostMapping("/git-diff")
    public GitDiffSummary captureGitDiff(
            @PathVariable String projectId,
            @RequestBody WorkspaceIdCommand command,
            HttpServletRequest request
    ) {
        requestPermissionGuard.assertProjectMember(request, projectId);
        return gitDiffService.capture(projectId, command.workspaceId());
    }

    /**
     * 创建或登记本地工作区的请求体。
     *
     * <p>作用域：本地执行 API；场景：把开发者授权过的本机目录纳入项目工作区。</p>
     */
    public record WorkspaceCommand(String name, String rootPath) {
    }

    /**
     * 仅携带工作区 ID 的请求体。
     *
     * <p>作用域：本地执行 API；场景：刷新摘要、读取目录树或捕获 Git diff 时定位授权工作区。</p>
     */
    public record WorkspaceIdCommand(String workspaceId) {
    }

    /**
     * 读取工作区相对路径文件的请求体。
     *
     * <p>作用域：本地执行 API；场景：开发智能体或人工查看指定文件内容。</p>
     */
    public record FilePathCommand(String workspaceId, String relativePath) {
    }

    /**
     * 写入工作区相对路径文件的请求体。
     *
     * <p>作用域：本地执行 API；场景：受控文件写入、记录操作者并生成文件操作审计。</p>
     */
    public record FileWriteCommand(String workspaceId, String relativePath, String content, String actorId) {
    }

    /**
     * 启动受控本地命令的请求体。
     *
     * <p>作用域：本地执行 API；场景：在授权工作区内执行测试、构建或诊断命令。</p>
     */
    public record CommandRequest(String workspaceId, String actorId, String command) {
    }

    /**
     * 审批待确认本地命令的请求体。
     *
     * <p>作用域：本地执行 API；场景：人工批准或拒绝需要确认的命令执行。</p>
     */
    public record ApprovalCommand(String actorId, ApprovalDecision decision, String note) {
    }

    /**
     * 取消本地命令任务的请求体。
     *
     * <p>作用域：本地执行 API；场景：终止仍在运行或等待审批的本地命令。</p>
     */
    public record CancelCommand(String actorId, String note) {
    }
}
