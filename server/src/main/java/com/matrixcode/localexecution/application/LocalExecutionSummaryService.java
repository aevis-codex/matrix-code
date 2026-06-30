package com.matrixcode.localexecution.application;

import com.matrixcode.approval.application.AuditService;
import com.matrixcode.approval.domain.AuditRecord;
import com.matrixcode.localexecution.domain.LocalExecutionSummary;
import org.springframework.stereotype.Service;

import java.util.Comparator;

@Service
public class LocalExecutionSummaryService {

    private final WorkspaceRegistry workspaceRegistry;
    private final LocalFileService fileService;
    private final LocalCommandService commandService;
    private final LocalGitDiffService gitDiffService;
    private final AuditService auditService;

    public LocalExecutionSummaryService(
            WorkspaceRegistry workspaceRegistry,
            LocalFileService fileService,
            LocalCommandService commandService,
            LocalGitDiffService gitDiffService,
            AuditService auditService
    ) {
        this.workspaceRegistry = workspaceRegistry;
        this.fileService = fileService;
        this.commandService = commandService;
        this.gitDiffService = gitDiffService;
        this.auditService = auditService;
    }

    public LocalExecutionSummary summary(String projectId) {
        return new LocalExecutionSummary(
                workspaceRegistry.list(projectId),
                fileService.recentOperations(projectId),
                commandService.recentTasks(projectId),
                commandService.activeTasks(projectId),
                commandService.recentLogs(projectId),
                gitDiffService.latest(projectId),
                auditService.records().stream()
                        .sorted(Comparator.comparing(AuditRecord::occurredAt).reversed())
                        .limit(20)
                        .toList()
        );
    }
}
