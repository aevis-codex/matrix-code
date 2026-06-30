package com.matrixcode.codingagent.domain;

import com.matrixcode.localexecution.domain.ExecutionTask;
import com.matrixcode.localexecution.domain.GitDiffSummary;

import java.util.List;

public record CodingAgentExecutionPlan(
        CodingAgentTask task,
        List<CodingAgentExecutionStep> executionSteps,
        ExecutionTask testCommandTask,
        GitDiffSummary gitDiffSummary
) {
    public CodingAgentExecutionPlan {
        if (task == null) {
            throw new IllegalArgumentException("编码任务不能为空");
        }
        executionSteps = List.copyOf(executionSteps == null ? List.of() : executionSteps);
        if (testCommandTask == null) {
            throw new IllegalArgumentException("测试命令任务不能为空");
        }
        if (gitDiffSummary == null) {
            throw new IllegalArgumentException("Git diff 摘要不能为空");
        }
    }
}
