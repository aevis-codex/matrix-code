package com.matrixcode.bug.domain;

import java.time.Instant;

public record ProjectBug(
        String id,
        String projectId,
        String title,
        BugSeverity severity,
        BugStatus status,
        String steps,
        String expected,
        String actual,
        String createdByRole,
        String currentOwnerRole,
        String lastNote,
        Instant updatedAt
) {

    public ProjectBug withStatus(BugStatus nextStatus, String note, Instant now) {
        return new ProjectBug(
                id,
                projectId,
                title,
                severity,
                nextStatus,
                steps,
                expected,
                actual,
                createdByRole,
                currentOwnerRole,
                note,
                now
        );
    }
}
