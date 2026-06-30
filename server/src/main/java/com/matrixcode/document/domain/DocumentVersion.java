package com.matrixcode.document.domain;

import java.time.Instant;

public record DocumentVersion(
        String id,
        String projectId,
        DocumentType type,
        String title,
        String content,
        int version,
        DocumentState state,
        String parentVersionId,
        Instant createdAt,
        String frozenBy,
        Instant frozenAt
) {

    public DocumentVersion withContent(String nextContent) {
        if (state == DocumentState.FROZEN) {
            throw new IllegalStateException("冻结版本不能修改：" + id);
        }
        return new DocumentVersion(id, projectId, type, title, nextContent, version, state, parentVersionId, createdAt, frozenBy, frozenAt);
    }

    public DocumentVersion freeze(String actorId, Instant now) {
        if (state == DocumentState.FROZEN) {
            throw new IllegalStateException("文档已冻结：" + id);
        }
        return new DocumentVersion(id, projectId, type, title, content, version, DocumentState.FROZEN, parentVersionId, createdAt, actorId, now);
    }
}
