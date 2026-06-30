package com.matrixcode.workbench.api;

import com.matrixcode.bug.domain.BugSeverity;
import com.matrixcode.bug.domain.ProjectBug;
import com.matrixcode.deployment.domain.ComposeEnvironment;
import com.matrixcode.deployment.domain.ComposeOperationRecord;
import com.matrixcode.deployment.domain.DeploymentHealthCheck;
import com.matrixcode.deployment.domain.DeploymentOperationRecord;
import com.matrixcode.deployment.domain.DeploymentOperationStatus;
import com.matrixcode.deployment.domain.DeploymentOperationType;
import com.matrixcode.deployment.domain.DeploymentTarget;
import com.matrixcode.identity.api.ProjectRequestPermissionGuard;
import com.matrixcode.deployment.application.DeploymentReleaseAuditImportResult;
import com.matrixcode.deployment.application.DeploymentReleaseAuditImportService;
import com.matrixcode.realtime.application.ProjectEventBus;
import com.matrixcode.realtime.domain.ProjectEvent;
import com.matrixcode.runtime.domain.RuntimeNotification;
import com.matrixcode.workbench.application.WorkbenchService;
import com.matrixcode.workbench.domain.DocumentSummary;
import com.matrixcode.workbench.domain.ProjectWorkbench;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;

/**
 * 项目工作台主流程 API。
 *
 * <p>作用域：项目成员；场景：产品、开发、测试、运维在同一工作台完成需求到上线的业务流转。</p>
 */
@RestController
@RequestMapping("/api/projects/{projectId}")
public class WorkbenchController {

    private final WorkbenchService service;
    private final ProjectRequestPermissionGuard requestPermissionGuard;
    private final DeploymentReleaseAuditImportService releaseAuditImportService;
    private final ProjectEventBus eventBus;

    public WorkbenchController(
            WorkbenchService service,
            ProjectRequestPermissionGuard requestPermissionGuard,
            DeploymentReleaseAuditImportService releaseAuditImportService,
            ProjectEventBus eventBus
    ) {
        this.service = service;
        this.requestPermissionGuard = requestPermissionGuard;
        this.releaseAuditImportService = releaseAuditImportService;
        this.eventBus = eventBus;
    }

    /**
     * 读取项目工作台聚合视图。
     *
     * <p>作用域：项目成员；场景：前端首页加载和每次业务操作后的全量刷新。</p>
     */
    @GetMapping("/workbench")
    public ProjectWorkbench workbench(@PathVariable String projectId, HttpServletRequest request) {
        var actorUserId = optionalProjectMember(request, projectId);
        return service.get(projectId, actorUserId);
    }

    /**
     * 标记单条运行态提醒已读。
     *
     * <p>作用域：项目成员；场景：处理审批、本地任务或 Compose 操作提醒后更新用户已读状态。</p>
     */
    @PostMapping("/runtime-notifications/{notificationId}/read")
    public RuntimeNotification markRuntimeNotificationRead(
            @PathVariable String projectId,
            @PathVariable String notificationId,
            @RequestBody(required = false) RuntimeNotificationReadCommand command,
            HttpServletRequest request
    ) {
        var requestActor = assertProjectMember(request, projectId);
        return service.markRuntimeNotificationRead(projectId, notificationId, readByUserId(command, requestActor));
    }

    /**
     * 批量标记运行态提醒已读。
     *
     * <p>作用域：项目成员；场景：提醒中心一键清理当前操作者可见提醒。</p>
     */
    @PostMapping("/runtime-notifications/read-all")
    public List<RuntimeNotification> markAllRuntimeNotificationsRead(
            @PathVariable String projectId,
            @RequestBody(required = false) RuntimeNotificationReadCommand command,
            HttpServletRequest request
    ) {
        var requestActor = assertProjectMember(request, projectId);
        return service.markAllRuntimeNotificationsRead(projectId, readByUserId(command, requestActor));
    }

    /**
     * 生成产品需求草稿。
     *
     * <p>作用域：项目成员；场景：产品输入原始需求后生成 PRD、界面说明和验收标准草稿。</p>
     */
    @PostMapping("/roles/product/drafts")
    public List<DocumentSummary> createProductDrafts(
            @PathVariable String projectId,
            @RequestBody ProductDraftCommand command,
            HttpServletRequest request
    ) {
        assertProjectMember(request, projectId);
        return service.createProductDrafts(projectId, command.requirement());
    }

    /**
     * 冻结文档版本。
     *
     * <p>作用域：项目成员；场景：产品确认 PRD 或交接文档后推进工作流。</p>
     */
    @PostMapping("/documents/{documentId}/freeze")
    public DocumentSummary freezeDocument(
            @PathVariable String projectId,
            @PathVariable String documentId,
            HttpServletRequest request
    ) {
        var requestActor = assertProjectMember(request, projectId);
        return service.freezeDocument(projectId, documentId, requestActor);
    }

    /**
     * 提交开发交付材料。
     *
     * <p>作用域：项目成员；场景：开发提交实现说明、自测结果、接口文档、数据库脚本和部署说明。</p>
     */
    @PostMapping("/roles/developer/deliveries")
    public List<DocumentSummary> submitDeveloperDelivery(
            @PathVariable String projectId,
            @RequestBody DeveloperDeliveryCommand command,
            HttpServletRequest request
    ) {
        assertProjectMember(request, projectId);
        return service.submitDeveloperDelivery(
                projectId,
                command.workspacePath(),
                command.implementationNote(),
                command.selfTestResult(),
                command.apiDoc(),
                command.databaseScript(),
                command.deploymentDoc()
        );
    }

    /**
     * 创建项目缺陷。
     *
     * <p>作用域：项目成员；场景：测试或验收阶段记录复现步骤、期望结果、实际结果和责任角色。</p>
     */
    @PostMapping("/bugs")
    public ProjectBug createBug(
            @PathVariable String projectId,
            @RequestBody BugCommand command,
            HttpServletRequest request
    ) {
        assertProjectMember(request, projectId);
        return service.createBug(
                projectId,
                command.title(),
                command.severity(),
                command.steps(),
                command.expected(),
                command.actual(),
                command.createdByRole(),
                command.currentOwnerRole()
        );
    }

    /**
     * 推进 Bug 状态。
     *
     * <p>作用域：项目成员；场景：缺陷确认、修复、待回归、关闭或重新打开。</p>
     */
    @PostMapping("/bugs/{bugId}/transitions")
    public ProjectBug transitionBug(
            @PathVariable String projectId,
            @PathVariable String bugId,
            @RequestBody BugTransitionCommand command,
            HttpServletRequest request
    ) {
        assertProjectMember(request, projectId);
        return service.transitionBug(projectId, bugId, command.nextStatus(), command.note());
    }

    /**
     * 提交测试报告。
     *
     * <p>作用域：项目成员；场景：测试角色沉淀验证结论、风险和回归结果。</p>
     */
    @PostMapping("/roles/tester/reports")
    public DocumentSummary submitTestReport(
            @PathVariable String projectId,
            @RequestBody TestReportCommand command,
            HttpServletRequest request
    ) {
        assertProjectMember(request, projectId);
        return service.submitTestReport(projectId, command.report());
    }

    /**
     * 配置部署目标。
     *
     * <p>作用域：项目成员；场景：运维登记环境地址、SSH 地址、健康检查和回滚说明。</p>
     */
    @PostMapping("/deployments/targets")
    public DeploymentTarget configureDeploymentTarget(
            @PathVariable String projectId,
            @RequestBody DeploymentTargetCommand command,
            HttpServletRequest request
    ) {
        assertProjectMember(request, projectId);
        return service.configureDeploymentTarget(
                projectId,
                command.environmentName(),
                command.environmentUrl(),
                command.sshAddress(),
                command.deployNote(),
                command.healthCheckUrl(),
                command.rollbackNote()
        );
    }

    /**
     * 执行部署健康检查。
     *
     * <p>作用域：当前操作者；场景：上线前或部署后验证目标环境健康状态。</p>
     */
    @PostMapping("/deployments/targets/{targetId}/health-checks")
    public DeploymentHealthCheck runDeploymentHealthCheck(
            @PathVariable String projectId,
            @PathVariable String targetId,
            @RequestBody DeploymentHealthCheckCommand command,
            HttpServletRequest request
    ) {
        assertProjectMemberActor(request, projectId, command.actorId());
        return service.runDeploymentHealthCheck(projectId, targetId, command.actorId());
    }

    /**
     * 记录部署或回滚操作。
     *
     * <p>作用域：当前操作者；场景：人工记录一次上线、回滚或发布结果。</p>
     */
    @PostMapping("/deployments/targets/{targetId}/operations")
    public DeploymentOperationRecord recordDeploymentOperation(
            @PathVariable String projectId,
            @PathVariable String targetId,
            @RequestBody DeploymentOperationCommand command,
            HttpServletRequest request
    ) {
        assertProjectMemberActor(request, projectId, command.actorId());
        return service.recordDeploymentOperation(
                projectId,
                targetId,
                command.actorId(),
                command.type(),
                command.status(),
                command.note()
        );
    }

    /**
     * 导入服务器侧发布审计。
     *
     * <p>作用域：当前操作者；场景：把发布脚本 JSONL 审计导入部署目标操作记录。</p>
     */
    @PostMapping("/deployments/targets/{targetId}/release-audit-imports")
    public DeploymentReleaseAuditImportResult importDeploymentReleaseAudit(
            @PathVariable String projectId,
            @PathVariable String targetId,
            @RequestBody DeploymentReleaseAuditImportCommand command,
            HttpServletRequest request
    ) {
        assertProjectMemberActor(request, projectId, command.actorId());
        var result = releaseAuditImportService.importJsonLines(
                projectId,
                targetId,
                command.actorId(),
                command.sourceId(),
                command.jsonLines()
        );
        if (result.importedCount() > 0) {
            eventBus.publish(new ProjectEvent(projectId, "DEPLOYMENT_RELEASE_AUDIT_IMPORTED", "运维导入了发布脚本审计"));
        }
        return result;
    }

    /**
     * 配置 Compose 演示环境。
     *
     * <p>作用域：项目成员；场景：登记本地演示或联调环境的工作区、compose 文件和环境变量。</p>
     */
    @PostMapping("/deployments/targets/{targetId}/compose-environments")
    public ComposeEnvironment configureComposeEnvironment(
            @PathVariable String projectId,
            @PathVariable String targetId,
            @RequestBody ComposeEnvironmentCommand command,
            HttpServletRequest request
    ) {
        assertProjectMember(request, projectId);
        return service.configureComposeEnvironment(
                projectId,
                targetId,
                command.workspaceId(),
                command.composeFilePath(),
                command.projectName(),
                command.serviceName()
        );
    }

    /**
     * 校验 Compose 环境。
     *
     * <p>作用域：当前操作者；场景：启动前检查配置是否可用。</p>
     */
    @PostMapping("/compose-environments/{environmentId}/validate")
    public ComposeOperationRecord validateComposeEnvironment(
            @PathVariable String projectId,
            @PathVariable String environmentId,
            @RequestBody ComposeOperationCommand command,
            HttpServletRequest request
    ) {
        assertProjectMemberActor(request, projectId, command.actorId());
        return service.validateComposeEnvironment(projectId, environmentId, command.actorId());
    }

    /**
     * 启动 Compose 环境。
     *
     * <p>作用域：当前操作者；场景：拉起本地演示、测试或联调环境。</p>
     */
    @PostMapping("/compose-environments/{environmentId}/start")
    public ComposeOperationRecord startComposeEnvironment(
            @PathVariable String projectId,
            @PathVariable String environmentId,
            @RequestBody ComposeOperationCommand command,
            HttpServletRequest request
    ) {
        assertProjectMemberActor(request, projectId, command.actorId());
        return service.startComposeEnvironment(projectId, environmentId, command.actorId());
    }

    /**
     * 停止 Compose 环境。
     *
     * <p>作用域：当前操作者；场景：演示或测试结束后释放本地环境。</p>
     */
    @PostMapping("/compose-environments/{environmentId}/stop")
    public ComposeOperationRecord stopComposeEnvironment(
            @PathVariable String projectId,
            @PathVariable String environmentId,
            @RequestBody ComposeOperationCommand command,
            HttpServletRequest request
    ) {
        assertProjectMemberActor(request, projectId, command.actorId());
        return service.stopComposeEnvironment(projectId, environmentId, command.actorId());
    }

    /**
     * 采集 Compose 环境日志。
     *
     * <p>作用域：当前操作者；场景：排查环境启动、停止或运行异常。</p>
     */
    @PostMapping("/compose-environments/{environmentId}/logs")
    public ComposeOperationRecord captureComposeLogs(
            @PathVariable String projectId,
            @PathVariable String environmentId,
            @RequestBody ComposeOperationCommand command,
            HttpServletRequest request
    ) {
        assertProjectMemberActor(request, projectId, command.actorId());
        return service.captureComposeLogs(projectId, environmentId, command.actorId());
    }

    /**
     * 提交产品验收结论。
     *
     * <p>作用域：项目成员；场景：产品确认通过或退回开发/测试，并生成验收文档。</p>
     */
    @PostMapping("/roles/product/acceptance")
    public DocumentSummary submitAcceptance(
            @PathVariable String projectId,
            @RequestBody AcceptanceCommand command,
            HttpServletRequest request
    ) {
        assertProjectMember(request, projectId);
        return service.submitAcceptance(projectId, command.accepted(), command.note(), command.returnToRole());
    }

    /**
     * 生成产品需求草稿的请求体。
     *
     * <p>作用域：产品角色工作台；场景：把原始需求描述交给产品智能体生成可冻结的需求文档。</p>
     */
    public record ProductDraftCommand(String requirement) {
    }

    /**
     * 提交开发交付内容的请求体。
     *
     * <p>作用域：开发角色工作台；场景：登记实现说明、自测结果、接口文档、数据库脚本和部署说明。</p>
     */
    public record DeveloperDeliveryCommand(
            String workspacePath,
            String implementationNote,
            String selfTestResult,
            String apiDoc,
            String databaseScript,
            String deploymentDoc
    ) {
    }

    /**
     * 创建项目 Bug 的请求体。
     *
     * <p>作用域：测试角色工作台；场景：记录缺陷标题、严重级别、复现步骤、预期结果和实际结果。</p>
     */
    public record BugCommand(
            String title,
            BugSeverity severity,
            String steps,
            String expected,
            String actual,
            String createdByRole,
            String currentOwnerRole
    ) {
    }

    /**
     * 推进 Bug 状态的请求体。
     *
     * <p>作用域：测试和开发协作流程；场景：修复、退回、关闭或补充缺陷处理备注。</p>
     */
    public record BugTransitionCommand(String nextStatus, String note) {
    }

    /**
     * 提交测试报告的请求体。
     *
     * <p>作用域：测试角色工作台；场景：写入阶段测试结论并生成项目测试文档。</p>
     */
    public record TestReportCommand(String report) {
    }

    /**
     * 保存部署目标的请求体。
     *
     * <p>作用域：部署角色工作台；场景：配置环境地址、SSH 地址、健康检查地址和回滚说明。</p>
     */
    public record DeploymentTargetCommand(
            String environmentName,
            String environmentUrl,
            String sshAddress,
            String deployNote,
            String healthCheckUrl,
            String rollbackNote
    ) {
    }

    /**
     * 触发部署健康检查的请求体。
     *
     * <p>作用域：部署角色工作台；场景：以指定操作者身份记录一次环境健康探测。</p>
     */
    public record DeploymentHealthCheckCommand(String actorId) {
    }

    /**
     * 记录部署操作的请求体。
     *
     * <p>作用域：部署角色工作台；场景：保存发布、回滚、巡检等操作及其状态和备注。</p>
     */
    public record DeploymentOperationCommand(
            String actorId,
            DeploymentOperationType type,
            DeploymentOperationStatus status,
            String note
    ) {
    }

    /**
     * 导入部署发布审计日志的请求体。
     *
     * <p>作用域：部署角色工作台；场景：从外部发布系统导入 JSON Lines 形式的低敏发布审计记录。</p>
     */
    public record DeploymentReleaseAuditImportCommand(
            String actorId,
            String sourceId,
            List<String> jsonLines
    ) {
    }

    /**
     * 保存 Docker Compose 环境的请求体。
     *
     * <p>作用域：部署角色工作台；场景：绑定工作区、compose 文件、项目名和服务名。</p>
     */
    public record ComposeEnvironmentCommand(
            String workspaceId,
            String composeFilePath,
            String projectName,
            String serviceName
    ) {
    }

    /**
     * 执行 Compose 环境操作的请求体。
     *
     * <p>作用域：部署角色工作台；场景：启动、停止、重启或读取 compose 服务日志时标记操作者。</p>
     */
    public record ComposeOperationCommand(String actorId) {
    }

    /**
     * 提交产品验收结论的请求体。
     *
     * <p>作用域：产品角色工作台；场景：标记本阶段是否验收通过，或指定退回角色继续处理。</p>
     */
    public record AcceptanceCommand(boolean accepted, String note, String returnToRole) {
    }

    /**
     * 标记运行通知已读的请求体。
     *
     * <p>作用域：项目通知 API；场景：把当前登录用户的未读通知标记为已读。</p>
     */
    public record RuntimeNotificationReadCommand(String actorUserId) {
    }

    private String readByUserId(RuntimeNotificationReadCommand command, String requestActor) {
        if (command == null || command.actorUserId() == null || command.actorUserId().isBlank()) {
            return requestActor;
        }
        var commandActor = command.actorUserId().trim();
        if (!requestActor.equals(commandActor)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "请求身份和已读操作者不一致");
        }
        return commandActor;
    }

    private String assertProjectMember(HttpServletRequest request, String projectId) {
        return requestPermissionGuard.assertProjectMember(request, projectId);
    }

    private String optionalProjectMember(HttpServletRequest request, String projectId) {
        try {
            return assertProjectMember(request, projectId);
        } catch (ResponseStatusException exception) {
            if (exception.getStatusCode() == HttpStatus.UNAUTHORIZED) {
                return "";
            }
            throw exception;
        }
    }

    private void assertProjectMemberActor(HttpServletRequest request, String projectId, String actorId) {
        requestPermissionGuard.assertProjectMemberActor(request, projectId, actorId);
    }
}
