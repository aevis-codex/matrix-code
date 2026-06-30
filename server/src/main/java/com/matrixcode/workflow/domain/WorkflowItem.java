package com.matrixcode.workflow.domain;

public record WorkflowItem(
        String id,
        String projectId,
        String title,
        WorkflowState state
) {
    public WorkflowItem withState(WorkflowState nextState) {
        return new WorkflowItem(id, projectId, title, nextState);
    }
}
