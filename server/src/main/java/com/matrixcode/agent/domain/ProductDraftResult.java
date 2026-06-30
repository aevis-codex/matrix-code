package com.matrixcode.agent.domain;

import com.matrixcode.document.domain.DocumentType;

import java.util.List;

public record ProductDraftResult(List<DraftDocument> documents) {

    public ProductDraftResult {
        documents = List.copyOf(documents);
    }

    public record DraftDocument(DocumentType type, String title, String content) {
    }
}
