package com.matrixcode.document.application;

import com.matrixcode.document.domain.DocumentState;
import com.matrixcode.document.domain.DocumentType;
import com.matrixcode.document.domain.DocumentVersion;
import com.matrixcode.workbench.application.InMemoryWorkbenchStateStore;
import com.matrixcode.workbench.application.WorkbenchStateStore;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class DocumentService {

    private final Object lock = new Object();
    private final Map<String, DocumentVersion> documents = new ConcurrentHashMap<>();
    private final WorkbenchStateStore stateStore;
    private final DocumentRepository repository;
    private volatile boolean loaded;

    public DocumentService() {
        this(new InMemoryWorkbenchStateStore(), (DocumentRepository) null);
    }

    @Autowired
    public DocumentService(WorkbenchStateStore stateStore, ObjectProvider<DocumentRepository> repository) {
        this(stateStore, repository.getIfAvailable());
    }

    public DocumentService(WorkbenchStateStore stateStore) {
        this(stateStore, (DocumentRepository) null);
    }

    public DocumentService(WorkbenchStateStore stateStore, DocumentRepository repository) {
        this.stateStore = stateStore;
        this.repository = repository;
    }

    public DocumentVersion createDraft(String projectId, DocumentType type, String title, String content) {
        requireText(projectId, "项目编号不能为空");
        if (type == null) {
            throw new IllegalArgumentException("文档类型不能为空");
        }
        requireText(title, "文档标题不能为空");
        requireText(content, "文档内容不能为空");
        synchronized (lock) {
            ensureLoaded();
            var now = Instant.now();
            var document = new DocumentVersion(
                    UUID.randomUUID().toString(),
                    projectId,
                    type,
                    title,
                    content,
                    1,
                    DocumentState.DRAFT,
                    null,
                    now,
                    null,
                    null
            );
            documents.put(document.id(), document);
            save();
            return document;
        }
    }

    public DocumentVersion updateContent(String documentId, String content) {
        synchronized (lock) {
            ensureLoaded();
            var updated = require(documentId).withContent(content);
            documents.put(documentId, updated);
            save();
            return updated;
        }
    }

    public DocumentVersion freeze(String documentId, String actorId) {
        synchronized (lock) {
            ensureLoaded();
            var frozen = require(documentId).freeze(actorId, Instant.now());
            documents.put(documentId, frozen);
            save();
            return frozen;
        }
    }

    public DocumentVersion createChangeDraft(String frozenVersionId, String content) {
        synchronized (lock) {
            ensureLoaded();
            var parent = require(frozenVersionId);
            if (parent.state() != DocumentState.FROZEN) {
                throw new IllegalStateException("只能基于冻结版本创建变更草稿：" + frozenVersionId);
            }
            if (hasChangeDraft(frozenVersionId)) {
                throw new IllegalStateException("已存在变更草稿：" + frozenVersionId);
            }
            var now = Instant.now();
            var draft = new DocumentVersion(
                    UUID.randomUUID().toString(),
                    parent.projectId(),
                    parent.type(),
                    parent.title(),
                    content,
                    parent.version() + 1,
                    DocumentState.DRAFT,
                    parent.id(),
                    now,
                    null,
                    null
            );
            documents.put(draft.id(), draft);
            save();
            return draft;
        }
    }

    public List<DocumentVersion> listByProject(String projectId) {
        requireText(projectId, "项目编号不能为空");
        synchronized (lock) {
            ensureLoaded();
            var projectDocuments = documents.values().stream()
                    .filter(document -> projectId.equals(document.projectId()))
                    .sorted(Comparator.comparing(DocumentVersion::title).thenComparing(DocumentVersion::id))
                    .toList();
            return List.copyOf(projectDocuments);
        }
    }

    public DocumentVersion findById(String documentId) {
        synchronized (lock) {
            ensureLoaded();
            return require(documentId);
        }
    }

    private DocumentVersion require(String documentId) {
        var document = documents.get(documentId);
        if (document == null) {
            throw new IllegalArgumentException("文档版本不存在：" + documentId);
        }
        return document;
    }

    private boolean hasChangeDraft(String frozenVersionId) {
        return documents.values().stream()
                .anyMatch(document -> frozenVersionId.equals(document.parentVersionId()));
    }

    private void save() {
        var values = List.copyOf(documents.values());
        if (repository != null) {
            repository.save(values);
        } else {
            stateStore.saveDocuments(values);
        }
    }

    private void ensureLoaded() {
        if (loaded) {
            return;
        }
        synchronized (lock) {
            if (loaded) {
                return;
            }
            loadPersistedDocuments().forEach(document -> documents.put(document.id(), document));
            loaded = true;
        }
    }

    private List<DocumentVersion> loadPersistedDocuments() {
        if (repository != null) {
            var persisted = repository.load();
            if (!persisted.isEmpty()) {
                return persisted;
            }
        }
        var restored = stateStore.load().documents();
        if (repository != null && !restored.isEmpty()) {
            repository.save(restored);
        }
        return restored;
    }

    private void requireText(String value, String message) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(message);
        }
    }
}
