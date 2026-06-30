package com.matrixcode.workbench.application;

import com.matrixcode.agent.application.LocalProductDraftAgent;
import com.matrixcode.agent.domain.ProductDraftRequest;
import com.matrixcode.bug.application.BugService;
import com.matrixcode.bug.domain.BugSeverity;
import com.matrixcode.bug.domain.BugStatus;
import com.matrixcode.bug.domain.ProjectBug;
import com.matrixcode.context.domain.ContextBlock;
import com.matrixcode.deployment.application.ComposeEnvironmentService;
import com.matrixcode.deployment.application.DeploymentHealthService;
import com.matrixcode.deployment.application.DeploymentOperationService;
import com.matrixcode.deployment.application.DeploymentTargetService;
import com.matrixcode.deployment.domain.ComposeEnvironment;
import com.matrixcode.deployment.domain.ComposeOperationRecord;
import com.matrixcode.deployment.domain.DeploymentHealthCheck;
import com.matrixcode.deployment.domain.DeploymentOperationRecord;
import com.matrixcode.deployment.domain.DeploymentOperationStatus;
import com.matrixcode.deployment.domain.DeploymentOperationType;
import com.matrixcode.deployment.domain.DeploymentTarget;
import com.matrixcode.document.application.DocumentService;
import com.matrixcode.document.domain.DocumentState;
import com.matrixcode.document.domain.DocumentType;
import com.matrixcode.document.domain.DocumentVersion;
import com.matrixcode.localexecution.application.LocalExecutionSummaryService;
import com.matrixcode.modelgateway.application.ModelGatewayService;
import com.matrixcode.modelgateway.domain.ModelRequestCommand;
import com.matrixcode.modelgateway.domain.ModelRole;
import com.matrixcode.realtime.application.ProjectEventBus;
import com.matrixcode.realtime.domain.ProjectEvent;
import com.matrixcode.runtime.application.RuntimeNotificationService;
import com.matrixcode.runtime.domain.RuntimeNotification;
import com.matrixcode.workbench.domain.ComposeRuntimeView;
import com.matrixcode.workbench.domain.DocumentSummary;
import com.matrixcode.workbench.domain.DeploymentRuntimeSummary;
import com.matrixcode.workbench.domain.ProjectWorkbench;
import com.matrixcode.workbench.domain.RoleSummary;
import com.matrixcode.workbench.domain.WorkbenchMetrics;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class WorkbenchService {

    private static final Map<BugStatus, List<BugStatus>> BUG_TRANSITIONS = new EnumMap<>(BugStatus.class);

    static {
        BUG_TRANSITIONS.put(BugStatus.NEW, List.of(BugStatus.CONFIRMED, BugStatus.CLOSED));
        BUG_TRANSITIONS.put(BugStatus.CONFIRMED, List.of(BugStatus.FIXING, BugStatus.CLOSED));
        BUG_TRANSITIONS.put(BugStatus.FIXING, List.of(BugStatus.REGRESSION_PENDING));
        BUG_TRANSITIONS.put(BugStatus.REGRESSION_PENDING, List.of(BugStatus.CLOSED, BugStatus.REOPENED));
        BUG_TRANSITIONS.put(BugStatus.REOPENED, List.of(BugStatus.FIXING, BugStatus.CLOSED));
        BUG_TRANSITIONS.put(BugStatus.CLOSED, List.of(BugStatus.REOPENED));
    }

    private final DocumentService documentService;
    private final LocalProductDraftAgent productDraftAgent;
    private final BugService bugService;
    private final DeploymentTargetService deploymentTargetService;
    private final DeploymentHealthService deploymentHealthService;
    private final DeploymentOperationService deploymentOperationService;
    private final ComposeEnvironmentService composeEnvironmentService;
    private final ProjectEventBus eventBus;
    private final ModelGatewayService modelGatewayService;
    private final LocalExecutionSummaryService localExecutionSummaryService;
    private final RuntimeNotificationService runtimeNotificationService;
    private final WorkbenchStateStore stateStore;
    private final WorkbenchProgressRepository progressRepository;
    private final Map<String, WorkbenchStateSnapshot.AcceptanceState> latestAcceptances = new ConcurrentHashMap<>();

    public WorkbenchService(
            DocumentService documentService,
            LocalProductDraftAgent productDraftAgent,
            BugService bugService,
            DeploymentTargetService deploymentTargetService,
            DeploymentHealthService deploymentHealthService,
            DeploymentOperationService deploymentOperationService,
            ComposeEnvironmentService composeEnvironmentService,
            ProjectEventBus eventBus,
            ModelGatewayService modelGatewayService,
            LocalExecutionSummaryService localExecutionSummaryService,
            RuntimeNotificationService runtimeNotificationService
    ) {
        this(
                documentService,
                productDraftAgent,
                bugService,
                deploymentTargetService,
                deploymentHealthService,
                deploymentOperationService,
                composeEnvironmentService,
                eventBus,
                modelGatewayService,
                localExecutionSummaryService,
                runtimeNotificationService,
                new InMemoryWorkbenchStateStore(),
                (WorkbenchProgressRepository) null
        );
    }

    public WorkbenchService(
            DocumentService documentService,
            LocalProductDraftAgent productDraftAgent,
            BugService bugService,
            DeploymentTargetService deploymentTargetService,
            DeploymentHealthService deploymentHealthService,
            DeploymentOperationService deploymentOperationService,
            ComposeEnvironmentService composeEnvironmentService,
            ProjectEventBus eventBus,
            ModelGatewayService modelGatewayService,
            LocalExecutionSummaryService localExecutionSummaryService,
            RuntimeNotificationService runtimeNotificationService,
            WorkbenchStateStore stateStore
    ) {
        this(
                documentService,
                productDraftAgent,
                bugService,
                deploymentTargetService,
                deploymentHealthService,
                deploymentOperationService,
                composeEnvironmentService,
                eventBus,
                modelGatewayService,
                localExecutionSummaryService,
                runtimeNotificationService,
                stateStore,
                (WorkbenchProgressRepository) null
        );
    }

    @Autowired
    public WorkbenchService(
            DocumentService documentService,
            LocalProductDraftAgent productDraftAgent,
            BugService bugService,
            DeploymentTargetService deploymentTargetService,
            DeploymentHealthService deploymentHealthService,
            DeploymentOperationService deploymentOperationService,
            ComposeEnvironmentService composeEnvironmentService,
            ProjectEventBus eventBus,
            ModelGatewayService modelGatewayService,
            LocalExecutionSummaryService localExecutionSummaryService,
            RuntimeNotificationService runtimeNotificationService,
            WorkbenchStateStore stateStore,
            Optional<WorkbenchProgressRepository> progressRepository
    ) {
        this(
                documentService,
                productDraftAgent,
                bugService,
                deploymentTargetService,
                deploymentHealthService,
                deploymentOperationService,
                composeEnvironmentService,
                eventBus,
                modelGatewayService,
                localExecutionSummaryService,
                runtimeNotificationService,
                stateStore,
                progressRepository.orElse(null)
        );
    }

    public WorkbenchService(
            DocumentService documentService,
            LocalProductDraftAgent productDraftAgent,
            BugService bugService,
            DeploymentTargetService deploymentTargetService,
            DeploymentHealthService deploymentHealthService,
            DeploymentOperationService deploymentOperationService,
            ComposeEnvironmentService composeEnvironmentService,
            ProjectEventBus eventBus,
            ModelGatewayService modelGatewayService,
            LocalExecutionSummaryService localExecutionSummaryService,
            RuntimeNotificationService runtimeNotificationService,
            WorkbenchStateStore stateStore,
            WorkbenchProgressRepository progressRepository
    ) {
        this.documentService = documentService;
        this.productDraftAgent = productDraftAgent;
        this.bugService = bugService;
        this.deploymentTargetService = deploymentTargetService;
        this.deploymentHealthService = deploymentHealthService;
        this.deploymentOperationService = deploymentOperationService;
        this.composeEnvironmentService = composeEnvironmentService;
        this.eventBus = eventBus;
        this.modelGatewayService = modelGatewayService;
        this.localExecutionSummaryService = localExecutionSummaryService;
        this.runtimeNotificationService = runtimeNotificationService;
        this.stateStore = stateStore;
        this.progressRepository = progressRepository;
        loadInitialAcceptances();
    }

    public ProjectWorkbench get(String projectId) {
        return get(projectId, "");
    }

    /**
     * 获取项目工作台投影。
     *
     * <p>`actorUserId` 只用于运行态提醒的用户级已读计算，不改变项目级工作台事实数据。
     * 空用户 ID 会保留历史项目级提醒视图，兼容旧调用方。</p>
     */
    public ProjectWorkbench get(String projectId, String actorUserId) {
        requireText(projectId, "项目编号不能为空");
        var documents = documentService.listByProject(projectId);
        var bugs = bugService.listByProject(projectId);
        var deploymentTargets = deploymentTargetService.listByProject(projectId);
        var deploymentRuntimeSummaries = deploymentTargets.stream()
                .map(target -> new DeploymentRuntimeSummary(
                        target.id(),
                        deploymentHealthService.latestForTarget(projectId, target.id()),
                        deploymentOperationService.latestDeploymentForTarget(projectId, target.id()),
                        deploymentOperationService.latestRollbackForTarget(projectId, target.id())
                ))
                .toList();
        var composeEnvironments = composeEnvironmentService.listByProject(projectId);
        var composeRuntimeViews = composeEnvironments.stream()
                .map(environment -> new ComposeRuntimeView(
                        environment.id(),
                        environment.targetId(),
                        environment.status(),
                        environment.composeFilePath(),
                        environment.projectName(),
                        environment.serviceName(),
                        composeEnvironmentService.latestOperationForEnvironment(projectId, environment.id())
                ))
                .toList();
        var events = eventBus.recent(projectId);
        var currentStage = currentStage(projectId);
        var modelGateway = modelGatewayService.summary(projectId);
        var localExecution = localExecutionSummaryService.summary(projectId);
        var runtimeNotifications = runtimeNotificationService.sync(projectId, localExecution, composeRuntimeViews, actorUserId);
        var modelMetrics = modelGateway.metrics();
        var documentSummaries = documents.stream()
                .map(DocumentSummary::from)
                .toList();
        var metrics = new WorkbenchMetrics(
                modelMetrics.cacheHitRate(),
                modelMetrics.cacheHitTokens() + modelMetrics.cacheMissInputTokens() + modelMetrics.outputTokens(),
                events.size(),
                documents.size(),
                openBugCount(bugs)
        );

        return new ProjectWorkbench(
                projectId,
                "MatrixCode 项目",
                currentStage,
                roles(currentStage),
                documentSummaries,
                bugs,
                deploymentTargets,
                deploymentRuntimeSummaries,
                composeEnvironments,
                composeRuntimeViews,
                metrics,
                modelGateway,
                localExecution,
                events,
                runtimeNotifications
        );
    }

    public RuntimeNotification markRuntimeNotificationRead(String projectId, String notificationId) {
        return markRuntimeNotificationRead(projectId, notificationId, "");
    }

    public RuntimeNotification markRuntimeNotificationRead(String projectId, String notificationId, String actorUserId) {
        requireText(projectId, "项目编号不能为空");
        notificationId = requireText(notificationId, "运行态提醒编号不能为空");
        var notification = runtimeNotificationService.markRead(projectId, notificationId, actorUserId);
        publish(projectId, "RUNTIME_NOTIFICATION_READ", "运行态提醒已读");
        return notification;
    }

    public List<RuntimeNotification> markAllRuntimeNotificationsRead(String projectId) {
        return markAllRuntimeNotificationsRead(projectId, "");
    }

    public List<RuntimeNotification> markAllRuntimeNotificationsRead(String projectId, String actorUserId) {
        requireText(projectId, "项目编号不能为空");
        var notifications = runtimeNotificationService.markAllRead(projectId, actorUserId);
        publish(projectId, "RUNTIME_NOTIFICATIONS_READ", "运行态提醒已全部已读");
        return notifications;
    }

    public List<DocumentSummary> createProductDrafts(String projectId, String requirement) {
        requireText(projectId, "项目编号不能为空");
        requirement = requireText(requirement, "产品需求不能为空");
        modelGatewayService.request(new ModelRequestCommand(
                projectId,
                ModelRole.PRODUCT,
                requirement,
                List.of(new ContextBlock("PROJECT_RULE", "产品草稿必须使用中文并保留待确认问题", true))
        ));
        var generated = productDraftAgent.generate(new ProductDraftRequest(requirement));
        var summaries = generated.documents().stream()
                .map(document -> documentService.createDraft(projectId, document.type(), document.title(), document.content()))
                .map(DocumentSummary::from)
                .toList();
        publish(projectId, "PRODUCT_DRAFT_CREATED", "产品生成了需求草稿");
        return summaries;
    }

    public DocumentSummary freezeDocument(String projectId, String documentId, String actorId) {
        requireText(projectId, "项目编号不能为空");
        var document = documentService.findById(documentId);
        if (!projectId.equals(document.projectId())) {
            throw new IllegalArgumentException("文档不属于项目：" + documentId);
        }
        var frozen = documentService.freeze(documentId, actorId);
        publish(projectId, "DOCUMENT_FROZEN", "产品冻结了文档");
        return DocumentSummary.from(frozen);
    }

    public List<DocumentSummary> submitDeveloperDelivery(
            String projectId,
            String workspacePath,
            String implementationNote,
            String selfTestResult,
            String apiDoc,
            String databaseScript,
            String deploymentDoc
    ) {
        requireText(projectId, "项目编号不能为空");
        workspacePath = requireText(workspacePath, "工作区路径不能为空");
        implementationNote = requireText(implementationNote, "实现说明不能为空");
        selfTestResult = requireText(selfTestResult, "自测结果不能为空");
        var documents = List.of(
                documentService.createDraft(projectId, DocumentType.IMPLEMENTATION_NOTE, "实现说明",
                        """
                                工作区路径：%s
                                实现说明：%s
                                自测结果：%s
                                """.formatted(workspacePath, implementationNote, selfTestResult)),
                documentService.createDraft(projectId, DocumentType.API_DOC, "接口文档", apiDoc),
                documentService.createDraft(projectId, DocumentType.DATABASE_SCRIPT, "数据库脚本", databaseScript),
                documentService.createDraft(projectId, DocumentType.DEPLOYMENT_DOC, "部署文档", deploymentDoc)
        );
        publish(projectId, "DEVELOPER_DELIVERY_SUBMITTED", "开发提交了实现交付物");
        return documents.stream()
                .map(DocumentSummary::from)
                .toList();
    }

    public ProjectBug createBug(
            String projectId,
            String title,
            BugSeverity severity,
            String steps,
            String expected,
            String actual,
            String createdByRole,
            String currentOwnerRole
    ) {
        var bug = bugService.create(projectId, title, severity, steps, expected, actual, createdByRole, currentOwnerRole);
        publish(projectId, "BUG_CREATED", "测试记录了 Bug");
        return bug;
    }

    public ProjectBug transitionBug(String projectId, String bugId, String nextStatus, String note) {
        requireText(projectId, "项目编号不能为空");
        var targetStatus = parseBugStatus(nextStatus);
        var current = requireProjectBug(projectId, bugId);
        var updated = transitionAlongPath(current, targetStatus, note);
        publish(projectId, "BUG_TRANSITIONED", "Bug 状态已流转");
        return updated;
    }

    public DocumentSummary submitTestReport(String projectId, String report) {
        var document = documentService.createDraft(projectId, DocumentType.QA_REPORT, "测试报告", report);
        publish(projectId, "TEST_REPORT_SUBMITTED", "测试提交了测试报告");
        return DocumentSummary.from(document);
    }

    public DeploymentTarget configureDeploymentTarget(
            String projectId,
            String environmentName,
            String environmentUrl,
            String sshAddress,
            String deployNote,
            String healthCheckUrl,
            String rollbackNote
    ) {
        var target = deploymentTargetService.configure(
                projectId,
                environmentName,
                environmentUrl,
                sshAddress,
                deployNote,
                healthCheckUrl,
                rollbackNote
        );
        publish(projectId, "DEPLOYMENT_TARGET_CONFIGURED", "运维配置了部署目标");
        return target;
    }

    public DeploymentHealthCheck runDeploymentHealthCheck(String projectId, String targetId, String actorId) {
        var check = deploymentHealthService.check(projectId, targetId, actorId);
        publish(projectId, "DEPLOYMENT_HEALTH_CHECKED", "运维运行了部署健康检查");
        return check;
    }

    public DeploymentOperationRecord recordDeploymentOperation(
            String projectId,
            String targetId,
            String actorId,
            DeploymentOperationType type,
            DeploymentOperationStatus status,
            String note
    ) {
        var record = deploymentOperationService.record(projectId, targetId, actorId, type, status, note);
        var message = type == DeploymentOperationType.ROLLBACK ? "运维记录了回滚结果" : "运维记录了部署结果";
        publish(projectId, "DEPLOYMENT_OPERATION_RECORDED", message);
        return record;
    }

    public ComposeEnvironment configureComposeEnvironment(
            String projectId,
            String targetId,
            String workspaceId,
            String composeFilePath,
            String projectName,
            String serviceName
    ) {
        var environment = composeEnvironmentService.configure(
                projectId,
                targetId,
                workspaceId,
                composeFilePath,
                projectName,
                serviceName
        );
        publish(projectId, "COMPOSE_ENVIRONMENT_CONFIGURED", "运维配置了 Compose 演示环境");
        return environment;
    }

    public ComposeOperationRecord validateComposeEnvironment(String projectId, String environmentId, String actorId) {
        var record = composeEnvironmentService.validate(projectId, environmentId, actorId);
        publish(projectId, "COMPOSE_OPERATION_RECORDED", "运维校验了 Compose 演示环境");
        return record;
    }

    public ComposeOperationRecord startComposeEnvironment(String projectId, String environmentId, String actorId) {
        var record = composeEnvironmentService.start(projectId, environmentId, actorId);
        publish(projectId, "COMPOSE_OPERATION_RECORDED", "运维启动了 Compose 演示环境");
        return record;
    }

    public ComposeOperationRecord stopComposeEnvironment(String projectId, String environmentId, String actorId) {
        var record = composeEnvironmentService.stop(projectId, environmentId, actorId);
        publish(projectId, "COMPOSE_OPERATION_RECORDED", "运维停止了 Compose 演示环境");
        return record;
    }

    public ComposeOperationRecord captureComposeLogs(String projectId, String environmentId, String actorId) {
        var record = composeEnvironmentService.captureLogs(projectId, environmentId, actorId);
        publish(projectId, "COMPOSE_OPERATION_RECORDED", "运维采集了 Compose 演示环境日志");
        return record;
    }

    public DocumentSummary submitAcceptance(String projectId, boolean accepted, String note) {
        return submitAcceptance(projectId, accepted, note, "开发");
    }

    public DocumentSummary submitAcceptance(String projectId, boolean accepted, String note, String returnToRole) {
        var normalizedReturnToRole = normalizeReturnToRole(returnToRole);
        var conclusion = accepted ? "通过" : "不通过";
        var returnTarget = accepted ? "无" : normalizedReturnToRole;
        var document = documentService.createDraft(projectId, DocumentType.ACCEPTANCE_RECORD, "产品验收记录",
                """
                        验收结论：%s
                        退回角色：%s
                        备注：%s
                        """.formatted(conclusion, returnTarget, note));
        latestAcceptances.put(projectId,
                new WorkbenchStateSnapshot.AcceptanceState(document.id(), accepted, normalizedReturnToRole));
        saveAcceptances();
        publish(projectId, "ACCEPTANCE_SUBMITTED", "产品提交了验收结论");
        return DocumentSummary.from(document);
    }

    private String currentStage(String projectId) {
        var documents = documentService.listByProject(projectId);
        var hasFrozenPrd = documents.stream()
                .anyMatch(document -> document.type() == DocumentType.PRD && document.state() == DocumentState.FROZEN);
        var hasDeveloperDelivery = documents.stream()
                .anyMatch(document -> document.type() == DocumentType.IMPLEMENTATION_NOTE);
        var hasTestReport = documents.stream()
                .anyMatch(document -> document.type() == DocumentType.QA_REPORT);
        var hasAcceptedAcceptance = hasAcceptedLatestAcceptance(projectId, documents);
        var latestAcceptance = latestAcceptances.get(projectId);
        var hasDeployment = !deploymentTargetService.listByProject(projectId).isEmpty();
        var hasBlockingBug = bugService.listByProject(projectId).stream()
                .anyMatch(bug -> bug.status() != BugStatus.CLOSED
                        && (bug.severity() == BugSeverity.HIGH || bug.severity() == BugSeverity.BLOCKER));
        if (!hasFrozenPrd) {
            return documents.isEmpty() ? "需求录入" : "需求草稿";
        }
        if (!hasDeveloperDelivery) {
            return "开发中";
        }
        if (hasBlockingBug) {
            return "缺陷处理中";
        }
        if (!hasTestReport) {
            return "测试中";
        }
        if (latestAcceptance != null && !latestAcceptance.accepted()) {
            return "测试".equals(latestAcceptance.returnToRole()) ? "验收退回测试" : "验收退回开发";
        }
        if (!hasAcceptedAcceptance) {
            return "待产品验收";
        }
        return hasDeployment ? "上线准备" : "待运维配置";
    }

    private boolean hasAcceptedLatestAcceptance(String projectId, List<DocumentVersion> documents) {
        var latestAcceptance = latestAcceptances.get(projectId);
        if (latestAcceptance == null || !latestAcceptance.accepted()) {
            return false;
        }
        return documents.stream()
                .anyMatch(document -> document.id().equals(latestAcceptance.documentId())
                        && document.type() == DocumentType.ACCEPTANCE_RECORD);
    }

    private List<RoleSummary> roles(String currentStage) {
        var productState = switch (currentStage) {
            case "需求录入", "需求草稿" -> "整理需求";
            case "待产品验收" -> "待验收";
            case "验收退回开发", "验收退回测试" -> "验收已退回";
            default -> "需求已冻结";
        };
        var developerState = switch (currentStage) {
            case "开发中", "验收退回开发" -> "可开始实现";
            case "缺陷处理中" -> "修复缺陷";
            case "需求录入", "需求草稿" -> "等待冻结需求";
            default -> "已提交交付物";
        };
        var testerState = switch (currentStage) {
            case "测试中", "验收退回测试" -> "可开始测试";
            case "缺陷处理中" -> "跟进回归";
            default -> "等待测试窗口";
        };
        var operationsState = switch (currentStage) {
            case "待运维配置" -> "待配置环境";
            case "上线准备" -> "可准备上线";
            default -> "等待验收结论";
        };

        return List.of(
                new RoleSummary("产品", productState, todoCount(currentStage, "产品"), "当前阶段：" + currentStage),
                new RoleSummary("开发", developerState, todoCount(currentStage, "开发"), "当前阶段：" + currentStage),
                new RoleSummary("测试", testerState, todoCount(currentStage, "测试"), "当前阶段：" + currentStage),
                new RoleSummary("运维", operationsState, todoCount(currentStage, "运维"), "当前阶段：" + currentStage)
        );
    }

    private int todoCount(String currentStage, String role) {
        return switch (role) {
            case "产品" -> ("需求录入".equals(currentStage) || "需求草稿".equals(currentStage) || "待产品验收".equals(currentStage)) ? 1 : 0;
            case "开发" -> ("开发中".equals(currentStage) || "缺陷处理中".equals(currentStage) || "验收退回开发".equals(currentStage)) ? 1 : 0;
            case "测试" -> ("测试中".equals(currentStage) || "缺陷处理中".equals(currentStage) || "验收退回测试".equals(currentStage)) ? 1 : 0;
            case "运维" -> ("待运维配置".equals(currentStage) || "上线准备".equals(currentStage)) ? 1 : 0;
            default -> 0;
        };
    }

    private ProjectBug transitionAlongPath(ProjectBug current, BugStatus targetStatus, String note) {
        if (current.status() == targetStatus) {
            return current;
        }

        var path = transitionPath(current.status(), targetStatus);
        if (path.isEmpty()) {
            throw new IllegalStateException("非法 Bug 状态流转：" + current.status() + " -> " + targetStatus);
        }

        var updated = current;
        for (var status : path) {
            var stepNote = status == targetStatus ? note : "工作台自动流转至 " + status;
            updated = bugService.transition(updated.id(), status, stepNote);
        }
        return updated;
    }

    private List<BugStatus> transitionPath(BugStatus start, BugStatus target) {
        var queue = new ArrayDeque<List<BugStatus>>();
        queue.add(List.of(start));
        while (!queue.isEmpty()) {
            var path = queue.removeFirst();
            var tail = path.getLast();
            for (var next : BUG_TRANSITIONS.getOrDefault(tail, List.of())) {
                if (path.contains(next)) {
                    continue;
                }
                var nextPath = new ArrayList<>(path);
                nextPath.add(next);
                if (next == target) {
                    return nextPath.subList(1, nextPath.size());
                }
                queue.add(List.copyOf(nextPath));
            }
        }
        return List.of();
    }

    private ProjectBug requireProjectBug(String projectId, String bugId) {
        return bugService.listByProject(projectId).stream()
                .filter(bug -> bug.id().equals(bugId))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Bug 不属于项目：" + bugId));
    }

    private BugStatus parseBugStatus(String nextStatus) {
        var normalizedStatus = requireText(nextStatus, "目标 Bug 状态不能为空");
        try {
            return BugStatus.valueOf(normalizedStatus);
        } catch (RuntimeException ex) {
            throw new IllegalArgumentException("Bug 状态不合法：" + normalizedStatus, ex);
        }
    }

    private int openBugCount(List<ProjectBug> bugs) {
        return (int) bugs.stream()
                .filter(bug -> bug.status() != BugStatus.CLOSED)
                .count();
    }

    private void publish(String projectId, String type, String message) {
        eventBus.publish(new ProjectEvent(projectId, type, message));
    }

    private void loadInitialAcceptances() {
        if (progressRepository != null) {
            var persisted = progressRepository.loadAcceptances();
            if (!persisted.isEmpty()) {
                latestAcceptances.putAll(persisted);
                return;
            }
        }

        var legacy = stateStore.load().acceptances();
        latestAcceptances.putAll(legacy);
        if (progressRepository != null && !legacy.isEmpty()) {
            progressRepository.saveAcceptances(legacy);
        }
    }

    private void saveAcceptances() {
        var snapshot = Map.copyOf(latestAcceptances);
        if (progressRepository != null) {
            progressRepository.saveAcceptances(snapshot);
            return;
        }
        stateStore.saveAcceptances(snapshot);
    }

    private String requireText(String value, String message) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(message);
        }
        return value.trim();
    }

    private String normalizeReturnToRole(String returnToRole) {
        if (returnToRole == null || returnToRole.isBlank()) {
            return "开发";
        }
        var normalized = returnToRole.trim();
        if (!"开发".equals(normalized) && !"测试".equals(normalized)) {
            throw new IllegalArgumentException("退回角色只能是开发或测试");
        }
        return normalized;
    }
}
