package com.matrixcode.workbench.domain;

import com.matrixcode.document.domain.DocumentState;
import com.matrixcode.document.domain.DocumentType;
import com.matrixcode.document.domain.DocumentVersion;

import java.time.Instant;

public record DocumentSummary(
        String id,
        DocumentType type,
        String title,
        String content,
        int version,
        DocumentState state,
        Instant createdAt
) {

    public static DocumentSummary from(DocumentVersion document) {
        return new DocumentSummary(
                document.id(),
                document.type(),
                document.title(),
                document.content(),
                document.version(),
                document.state(),
                document.createdAt()
        );
    }
}
