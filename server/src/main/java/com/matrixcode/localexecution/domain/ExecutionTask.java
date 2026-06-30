package com.matrixcode.localexecution.domain;

import com.matrixcode.approval.domain.ApprovalDecision;

import java.time.Instant;

public record ExecutionTask(
        String taskId,
        String projectId,
        String workspaceId,
        String actorId,
        String toolType,
        String command,
        ApprovalDecision approvalDecision,
        ExecutionTaskStatus status,
        Integer exitCode,
        String stdoutSummary,
        String stderrSummary,
        long durationMillis,
        Instant createdAt,
        String approverId,
        String approvalNote,
        Instant decidedAt,
        String safetyRejectionReason,
        String canceledBy,
        String cancelNote,
        Instant canceledAt
) {
    public ExecutionTask(
            String taskId,
            String projectId,
            String workspaceId,
            String actorId,
            String toolType,
            String command,
            ApprovalDecision approvalDecision,
            ExecutionTaskStatus status,
            Integer exitCode,
            String stdoutSummary,
            String stderrSummary,
            long durationMillis,
            Instant createdAt
    ) {
        this(taskId, projectId, workspaceId, actorId, toolType, command, approvalDecision, status,
                exitCode, stdoutSummary, stderrSummary, durationMillis, createdAt, "", "", null, "");
    }

    public ExecutionTask(
            String taskId,
            String projectId,
            String workspaceId,
            String actorId,
            String toolType,
            String command,
            ApprovalDecision approvalDecision,
            ExecutionTaskStatus status,
            Integer exitCode,
            String stdoutSummary,
            String stderrSummary,
            long durationMillis,
            Instant createdAt,
            String approverId,
            String approvalNote,
            Instant decidedAt,
            String safetyRejectionReason
    ) {
        this(taskId, projectId, workspaceId, actorId, toolType, command, approvalDecision, status,
                exitCode, stdoutSummary, stderrSummary, durationMillis, createdAt,
                approverId, approvalNote, decidedAt, safetyRejectionReason, "", "", null);
    }

    public ExecutionTask {
        taskId = requireText(taskId, "任务编号不能为空");
        projectId = requireText(projectId, "项目编号不能为空");
        workspaceId = requireText(workspaceId, "工作区编号不能为空");
        actorId = requireText(actorId, "执行人不能为空");
        toolType = requireText(toolType, "工具类型不能为空");
        command = requireText(command, "命令不能为空");
        if (approvalDecision == null) {
            throw new IllegalArgumentException("审批结果不能为空");
        }
        if (status == null) {
            throw new IllegalArgumentException("执行状态不能为空");
        }
        stdoutSummary = stdoutSummary == null ? "" : stdoutSummary;
        stderrSummary = stderrSummary == null ? "" : stderrSummary;
        if (durationMillis < 0) {
            throw new IllegalArgumentException("执行耗时不能为负数");
        }
        if (createdAt == null) {
            throw new IllegalArgumentException("任务创建时间不能为空");
        }
        approverId = approverId == null ? "" : approverId.trim();
        approvalNote = approvalNote == null ? "" : approvalNote.trim();
        safetyRejectionReason = safetyRejectionReason == null ? "" : safetyRejectionReason.trim();
        canceledBy = canceledBy == null ? "" : canceledBy.trim();
        cancelNote = cancelNote == null ? "" : cancelNote.trim();
    }

    private static String requireText(String value, String message) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(message);
        }
        return value.trim();
    }
}
