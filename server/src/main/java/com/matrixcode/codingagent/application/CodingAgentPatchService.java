package com.matrixcode.codingagent.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.matrixcode.agentruntime.application.AgentRuntimeService;
import com.matrixcode.codingagent.domain.CodingAgentPatchResult;
import com.matrixcode.localexecution.application.LocalFileService;
import com.matrixcode.localexecution.application.LocalGitDiffService;
import com.matrixcode.modelgateway.domain.ModelRole;
import com.matrixcode.roleagent.application.RoleAgentConfigService;
import com.matrixcode.workbench.application.InMemoryWorkbenchStateStore;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.util.Map;
import java.util.Optional;

@Service
public class CodingAgentPatchService {

    private final LocalFileService fileService;
    private final LocalGitDiffService gitDiffService;
    private final RoleAgentConfigService roleAgentConfigService;
    private final AgentRuntimeService agentRuntimeService;

    public CodingAgentPatchService(LocalFileService fileService, LocalGitDiffService gitDiffService) {
        this(
                fileService,
                gitDiffService,
                new RoleAgentConfigService(new InMemoryWorkbenchStateStore()),
                new AgentRuntimeService(Optional.empty(), new ObjectMapper(), Clock.systemUTC())
        );
    }

    @Autowired
    public CodingAgentPatchService(
            LocalFileService fileService,
            LocalGitDiffService gitDiffService,
            RoleAgentConfigService roleAgentConfigService,
            AgentRuntimeService agentRuntimeService
    ) {
        this.fileService = fileService;
        this.gitDiffService = gitDiffService;
        this.roleAgentConfigService = roleAgentConfigService;
        this.agentRuntimeService = agentRuntimeService;
    }

    public CodingAgentPatchResult apply(
            String projectId,
            ModelRole role,
            String workspaceId,
            String actorId,
            String relativePath,
            String expectedContent,
            String nextContent,
            String summary,
            boolean approved
    ) {
        return apply(projectId, role, workspaceId, actorId, relativePath, expectedContent, nextContent, summary, approved, "");
    }

    /**
     * 应用一次经审批的编码智能体 Patch。
     *
     * <p>方法先校验审批和文件基线，写入文件后采集 Git diff，再记录 Agent 运行和 Patch 事件。
     * 只有文件写入成功后才记录成功事件，避免审计时间线展示未实际生效的 Patch。</p>
     */
    public CodingAgentPatchResult apply(
            String projectId,
            ModelRole role,
            String workspaceId,
            String actorId,
            String relativePath,
            String expectedContent,
            String nextContent,
            String summary,
            boolean approved,
            String runId
    ) {
        if (!approved) {
            throw new IllegalArgumentException("应用 patch 前必须确认审批");
        }
        requireText(actorId, "操作者不能为空");
        requireText(relativePath, "相对路径不能为空");
        requireText(summary, "变更说明不能为空");
        if (expectedContent == null) {
            throw new IllegalArgumentException("期望内容不能为空");
        }
        if (nextContent == null) {
            throw new IllegalArgumentException("目标内容不能为空");
        }

        var current = fileService.read(projectId, workspaceId, relativePath);
        if (!current.content().equals(expectedContent)) {
            throw new IllegalArgumentException("文件内容已变化，请重新生成 patch");
        }

        var write = fileService.write(projectId, workspaceId, relativePath, nextContent);
        var diff = gitDiffService.capture(projectId, workspaceId);
        var config = roleAgentConfigService.require(projectId, role);
        var run = agentRuntimeService.markSucceeded(
                runId,
                projectId,
                role,
                config.agentKind(),
                actorId,
                config.providerId(),
                config.model(),
                "应用 Patch：" + relativePath,
                summary
        );
        agentRuntimeService.appendEvent(run.id(), projectId, "PATCH_APPLIED", "编码 Patch 已应用", Map.of(
                "workspaceId", workspaceId,
                "relativePath", write.relativePath(),
                "summary", summary,
                "bytesWritten", write.bytesWritten(),
                "changedFileCount", diff.changedFiles().size()
        ));
        agentRuntimeService.appendToolTrace(
                run.id(),
                projectId,
                "local-execution.files.write",
                "apply-patch",
                "SUCCEEDED",
                write.relativePath(),
                summary,
                Map.of(
                        "workspaceId", workspaceId,
                        "relativePath", write.relativePath(),
                        "bytesWritten", write.bytesWritten(),
                        "changedFileCount", diff.changedFiles().size()
                )
        );
        return new CodingAgentPatchResult(
                projectId,
                role,
                workspaceId,
                actorId,
                run.id(),
                write.relativePath(),
                summary,
                write.bytesWritten(),
                diff
        );
    }

    private static String requireText(String value, String message) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(message);
        }
        return value.trim();
    }
}
