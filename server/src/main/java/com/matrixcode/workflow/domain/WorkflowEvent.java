package com.matrixcode.workflow.domain;

import java.time.Instant;

public record WorkflowEvent(
        String id,
        String itemId,
        WorkflowEventType type,
        WorkflowState fromState,
        WorkflowState toState,
        String actorId,
        Instant occurredAt
) {
}
