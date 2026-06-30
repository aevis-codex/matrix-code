package com.matrixcode.codingagent.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.matrixcode.agentruntime.application.AgentRuntimeService;
import com.matrixcode.document.application.DocumentService;
import com.matrixcode.document.domain.DocumentType;
import com.matrixcode.modelgateway.domain.ModelRole;
import com.matrixcode.realtime.application.ProjectEventBus;
import com.matrixcode.realtime.domain.ProjectEvent;
import com.matrixcode.roleagent.application.RoleAgentConfigService;
import com.matrixcode.workbench.application.InMemoryWorkbenchStateStore;
import com.matrixcode.workbench.domain.DocumentSummary;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.util.Map;
import java.util.Optional;

@Service
public class CodingAgentHandoffService {

    private static final String TITLE = "编码智能体交付回溯";

    private final DocumentService documentService;
    private final ProjectEventBus eventBus;
    private final RoleAgentConfigService roleAgentConfigService;
    private final AgentRuntimeService agentRuntimeService;

    public CodingAgentHandoffService(DocumentService documentService, ProjectEventBus eventBus) {
        this(
                documentService,
                eventBus,
                new RoleAgentConfigService(new InMemoryWorkbenchStateStore()),
                new AgentRuntimeService(Optional.empty(), new ObjectMapper(), Clock.systemUTC())
        );
    }

    @Autowired
    public CodingAgentHandoffService(
            DocumentService documentService,
            ProjectEventBus eventBus,
            RoleAgentConfigService roleAgentConfigService,
            AgentRuntimeService agentRuntimeService
    ) {
        this.documentService = documentService;
        this.eventBus = eventBus;
        this.roleAgentConfigService = roleAgentConfigService;
        this.agentRuntimeService = agentRuntimeService;
    }

    public DocumentSummary record(
            String projectId,
            ModelRole role,
            String workspaceId,
            String actorId,
            String goal,
            String relativePath,
            String patchSummary,
            String diffSummary,
            String testTaskId,
            String testTaskStatus,
            String testCommand,
            String deliveryConclusion
    ) {
        return record(
                projectId,
                role,
                workspaceId,
                actorId,
                goal,
                relativePath,
                patchSummary,
                diffSummary,
                testTaskId,
                testTaskStatus,
                testCommand,
                deliveryConclusion,
                ""
        );
    }

    /**
     * 记录编码智能体交接回溯文档。
     *
     * <p>方法会先创建可审阅的交接文档，再发布项目事件，并把交付结果写入 Agent 运行事件。
     * 交接成功才记录 `HANDOFF_RECORDED`，因此运行时间线可作为真实交付链路的审计依据。</p>
     */
    public DocumentSummary record(
            String projectId,
            ModelRole role,
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
        projectId = requireText(projectId, "项目编号不能为空");
        if (role == null) {
            throw new IllegalArgumentException("角色不能为空");
        }
        workspaceId = requireText(workspaceId, "工作区编号不能为空");
        actorId = requireText(actorId, "操作者不能为空");
        goal = requireText(goal, "执行目标不能为空");
        relativePath = requireText(relativePath, "变更文件不能为空");
        patchSummary = requireText(patchSummary, "Patch 摘要不能为空");
        diffSummary = requireText(diffSummary, "Diff 摘要不能为空");
        testTaskId = requireText(testTaskId, "测试任务不能为空");
        testTaskStatus = requireText(testTaskStatus, "测试状态不能为空");
        testCommand = requireText(testCommand, "测试命令不能为空");
        deliveryConclusion = requireText(deliveryConclusion, "交付结论不能为空");

        var document = documentService.createDraft(projectId, DocumentType.CODING_AGENT_HANDOFF, TITLE,
                """
                        角色：%s
                        操作者：%s
                        工作区：%s
                        执行目标：%s
                        变更文件：%s
                        Patch 摘要：%s
                        Diff 摘要：%s
                        测试任务：%s
                        测试命令：%s
                        测试状态：%s
                        交付结论：%s
                        """.formatted(
                        role.name(),
                        actorId,
                        workspaceId,
                        goal,
                        relativePath,
                        patchSummary,
                        diffSummary,
                        testTaskId,
                        testCommand,
                        testTaskStatus,
                        deliveryConclusion
                ));
        eventBus.publish(new ProjectEvent(
                projectId,
                "CODING_AGENT_HANDOFF_RECORDED",
                "开发记录了编码智能体交付回溯",
                role.name(),
                actorId
        ));
        var config = roleAgentConfigService.require(projectId, role);
        var run = agentRuntimeService.markSucceeded(
                runId,
                projectId,
                role,
                config.agentKind(),
                actorId,
                config.providerId(),
                config.model(),
                goal,
                "编码智能体交接回溯已记录"
        );
        agentRuntimeService.appendEvent(run.id(), projectId, "HANDOFF_RECORDED", "编码智能体交接回溯已记录", Map.of(
                "workspaceId", workspaceId,
                "relativePath", relativePath,
                "patchSummary", patchSummary,
                "diffSummary", diffSummary,
                "actorUserId", actorId,
                "testTaskId", testTaskId,
                "testTaskStatus", testTaskStatus,
                "testCommand", testCommand,
                "documentTitle", document.title()
        ));
        agentRuntimeService.appendToolTrace(
                run.id(),
                projectId,
                "document.center",
                "create-coding-agent-handoff",
                "SUCCEEDED",
                document.id(),
                "编码智能体交接回溯文档已创建",
                Map.of(
                        "workspaceId", workspaceId,
                        "relativePath", relativePath,
                        "actorUserId", actorId,
                        "testTaskId", testTaskId,
                        "testTaskStatus", testTaskStatus,
                        "documentTitle", document.title()
                )
        );
        return DocumentSummary.from(document);
    }

    private static String requireText(String value, String message) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(message);
        }
        return value.trim();
    }
}
