package com.matrixcode.bug.application;

import com.matrixcode.bug.domain.BugSeverity;
import com.matrixcode.bug.domain.BugStatus;
import com.matrixcode.bug.domain.ProjectBug;
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
public class BugService {

    private final Object lock = new Object();
    private final Map<String, ProjectBug> bugs = new ConcurrentHashMap<>();
    private final WorkbenchStateStore stateStore;
    private final BugRepository repository;
    private volatile boolean loaded;

    public BugService() {
        this(new InMemoryWorkbenchStateStore(), (BugRepository) null);
    }

    @Autowired
    public BugService(WorkbenchStateStore stateStore, ObjectProvider<BugRepository> repository) {
        this(stateStore, repository.getIfAvailable());
    }

    public BugService(WorkbenchStateStore stateStore) {
        this(stateStore, (BugRepository) null);
    }

    public BugService(WorkbenchStateStore stateStore, BugRepository repository) {
        this.stateStore = stateStore;
        this.repository = repository;
    }

    public ProjectBug create(
            String projectId,
            String title,
            BugSeverity severity,
            String steps,
            String expected,
            String actual,
            String createdByRole,
            String currentOwnerRole
    ) {
        requireText(projectId, "项目编号不能为空");
        requireText(title, "Bug 标题不能为空");
        if (severity == null) {
            throw new IllegalArgumentException("Bug 严重级别不能为空");
        }
        requireText(steps, "复现步骤不能为空");
        requireText(expected, "预期结果不能为空");
        requireText(actual, "实际结果不能为空");
        requireText(createdByRole, "创建角色不能为空");
        requireText(currentOwnerRole, "当前负责人角色不能为空");
        synchronized (lock) {
            ensureLoaded();
            var bug = new ProjectBug(
                    UUID.randomUUID().toString(),
                    projectId,
                    title,
                    severity,
                    BugStatus.NEW,
                    steps,
                    expected,
                    actual,
                    createdByRole,
                    currentOwnerRole,
                    "创建 Bug",
                    Instant.now()
            );
            bugs.put(bug.id(), bug);
            save();
            return bug;
        }
    }

    public List<ProjectBug> listByProject(String projectId) {
        requireText(projectId, "项目编号不能为空");
        synchronized (lock) {
            ensureLoaded();
            var projectBugs = bugs.values().stream()
                    .filter(bug -> projectId.equals(bug.projectId()))
                    .sorted(Comparator.comparing(ProjectBug::title).thenComparing(ProjectBug::id))
                    .toList();
            return List.copyOf(projectBugs);
        }
    }

    public ProjectBug transition(String bugId, BugStatus nextStatus, String note) {
        requireText(bugId, "Bug 编号不能为空");
        if (nextStatus == null) {
            throw new IllegalArgumentException("目标 Bug 状态不能为空");
        }
        requireText(note, "流转备注不能为空");
        synchronized (lock) {
            ensureLoaded();
            var current = require(bugId);
            if (!canMove(current.status(), nextStatus)) {
                throw new IllegalStateException("非法 Bug 状态流转：" + current.status() + " -> " + nextStatus);
            }
            var updated = current.withStatus(nextStatus, note, Instant.now());
            bugs.put(bugId, updated);
            save();
            return updated;
        }
    }

    private ProjectBug require(String bugId) {
        var bug = bugs.get(bugId);
        if (bug == null) {
            throw new IllegalArgumentException("Bug 不存在：" + bugId);
        }
        return bug;
    }

    private boolean canMove(BugStatus current, BugStatus next) {
        return switch (current) {
            case NEW -> next == BugStatus.CONFIRMED || next == BugStatus.CLOSED;
            case CONFIRMED -> next == BugStatus.FIXING || next == BugStatus.CLOSED;
            case FIXING -> next == BugStatus.REGRESSION_PENDING;
            case REGRESSION_PENDING -> next == BugStatus.CLOSED || next == BugStatus.REOPENED;
            case REOPENED -> next == BugStatus.FIXING || next == BugStatus.CLOSED;
            case CLOSED -> next == BugStatus.REOPENED;
        };
    }

    private void save() {
        var values = List.copyOf(bugs.values());
        if (repository != null) {
            repository.save(values);
        } else {
            stateStore.saveBugs(values);
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
            loadPersistedBugs().forEach(bug -> bugs.put(bug.id(), bug));
            loaded = true;
        }
    }

    private List<ProjectBug> loadPersistedBugs() {
        if (repository != null) {
            var persisted = repository.load();
            if (!persisted.isEmpty()) {
                return persisted;
            }
        }
        var restored = stateStore.load().bugs();
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
