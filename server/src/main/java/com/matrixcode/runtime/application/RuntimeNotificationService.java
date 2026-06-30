package com.matrixcode.runtime.application;

import com.matrixcode.deployment.domain.ComposeOperationStatus;
import com.matrixcode.localexecution.domain.ExecutionTask;
import com.matrixcode.localexecution.domain.ExecutionTaskStatus;
import com.matrixcode.localexecution.domain.LocalExecutionSummary;
import com.matrixcode.runtime.domain.RuntimeNotification;
import com.matrixcode.runtime.domain.RuntimeNotificationLevel;
import com.matrixcode.runtime.domain.RuntimeNotificationSourceType;
import com.matrixcode.workbench.domain.ComposeRuntimeView;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

@Service
public class RuntimeNotificationService {

    private static final int PROJECT_LIMIT = 50;
    private static final int RECENT_LIMIT = 10;

    private final Map<String, Map<String, RuntimeNotification>> notifications = new ConcurrentHashMap<>();
    private final Map<String, Map<String, Map<String, Instant>>> readReceipts = new ConcurrentHashMap<>();
    private final RuntimeNotificationStore store;

    public RuntimeNotificationService() {
        this(new InMemoryRuntimeNotificationStore());
    }

    @Autowired
    public RuntimeNotificationService(RuntimeNotificationStore store) {
        this.store = store;
        restore(store.load());
    }

    public List<RuntimeNotification> sync(
            String projectId,
            LocalExecutionSummary localExecution,
            List<ComposeRuntimeView> composeRuntimeViews
    ) {
        return sync(projectId, localExecution, composeRuntimeViews, "");
    }

    /**
     * 同步项目运行态提醒，并按当前用户返回个性化已读状态。
     *
     * <p>提醒本身仍是项目级事实；`actorUserId` 只影响返回对象中的 `readAt/readByUserId`，
     * 避免多人协作时一个成员已读后影响其他成员的未读状态。</p>
     */
    public List<RuntimeNotification> sync(
            String projectId,
            LocalExecutionSummary localExecution,
            List<ComposeRuntimeView> composeRuntimeViews,
            String actorUserId
    ) {
        projectId = requireText(projectId, "项目编号不能为空");
        var projectNotifications = notifications.computeIfAbsent(projectId, ignored -> new ConcurrentHashMap<>());

        generatedNotifications(projectId, localExecution, composeRuntimeViews).forEach(notification ->
                projectNotifications.merge(
                        notification.id(),
                        notification,
                        (existing, replacement) -> new RuntimeNotification(
                                replacement.id(),
                                replacement.projectId(),
                                replacement.level(),
                                replacement.title(),
                                replacement.message(),
                                replacement.sourceType(),
                                replacement.sourceId(),
                                replacement.occurredAt(),
                                existing.readAt(),
                                existing.readByUserId()
                        )
                )
        );
        trim(projectId, projectNotifications);
        persist();
        return recentForUser(projectId, actorUserId);
    }

    public RuntimeNotification markRead(String projectId, String notificationId) {
        return markRead(projectId, notificationId, "");
    }

    public RuntimeNotification markRead(String projectId, String notificationId, String actorUserId) {
        projectId = requireText(projectId, "项目编号不能为空");
        notificationId = requireText(notificationId, "运行态提醒编号不能为空");
        var projectNotifications = notifications.get(projectId);
        if (projectNotifications == null || !projectNotifications.containsKey(notificationId)) {
            throw new IllegalArgumentException("运行态提醒不存在：" + notificationId);
        }
        var readByUserId = optionalText(actorUserId);
        RuntimeNotification updated;
        if (readByUserId.isBlank()) {
            updated = projectNotifications.compute(notificationId,
                    (ignored, notification) -> notification.withReadAt(Instant.now(), readByUserId));
        } else {
            var notification = projectNotifications.get(notificationId);
            var readAt = readReceipts
                    .computeIfAbsent(projectId, ignored -> new ConcurrentHashMap<>())
                    .computeIfAbsent(readByUserId, ignored -> new ConcurrentHashMap<>())
                    .computeIfAbsent(notificationId, ignored -> Instant.now());
            updated = notificationForUser(notification, readByUserId, readAt);
        }
        persist();
        return updated;
    }

    public List<RuntimeNotification> markAllRead(String projectId) {
        return markAllRead(projectId, "");
    }

    public List<RuntimeNotification> markAllRead(String projectId, String actorUserId) {
        projectId = requireText(projectId, "项目编号不能为空");
        var projectNotifications = notifications.get(projectId);
        if (projectNotifications == null || projectNotifications.isEmpty()) {
            return List.of();
        }

        var readByUserId = optionalText(actorUserId);
        if (readByUserId.isBlank()) {
            var readAt = Instant.now();
            projectNotifications.replaceAll((ignored, notification) ->
                    notification.readAt() == null ? notification.withReadAt(readAt, readByUserId) : notification
            );
        } else {
            var userReceipts = readReceipts
                    .computeIfAbsent(projectId, ignored -> new ConcurrentHashMap<>())
                    .computeIfAbsent(readByUserId, ignored -> new ConcurrentHashMap<>());
            var readAt = Instant.now();
            projectNotifications.keySet().forEach(notificationId ->
                    userReceipts.computeIfAbsent(notificationId, ignored -> readAt)
            );
        }
        persist();
        return recentForUser(projectId, readByUserId);
    }

    public List<RuntimeNotification> recent(String projectId) {
        projectId = requireText(projectId, "项目编号不能为空");
        return sorted(projectId).stream()
                .limit(RECENT_LIMIT)
                .toList();
    }

    public List<RuntimeNotification> recentForUser(String projectId, String actorUserId) {
        var userId = optionalText(actorUserId);
        return sorted(projectId).stream()
                .map(notification -> notificationForUser(notification, userId))
                .limit(RECENT_LIMIT)
                .toList();
    }

    public List<RuntimeNotification> allForTesting(String projectId) {
        return sorted(projectId);
    }

    private List<RuntimeNotification> generatedNotifications(
            String projectId,
            LocalExecutionSummary localExecution,
            List<ComposeRuntimeView> composeRuntimeViews
    ) {
        var generated = new ArrayList<RuntimeNotification>();
        if (localExecution != null) {
            localExecution.recentTasks().stream()
                    .map(task -> fromTask(projectId, task))
                    .flatMap(List::stream)
                    .forEach(generated::add);
        }
        if (composeRuntimeViews != null) {
            composeRuntimeViews.stream()
                    .map(view -> fromCompose(projectId, view))
                    .flatMap(List::stream)
                    .forEach(generated::add);
        }
        return generated;
    }

    private List<RuntimeNotification> fromTask(String projectId, ExecutionTask task) {
        if (task.status() == ExecutionTaskStatus.APPROVAL_PENDING) {
            return List.of(new RuntimeNotification(
                    "approval:" + task.taskId(),
                    projectId,
                    RuntimeNotificationLevel.ACTION,
                    "需要审批本地命令",
                    task.command(),
                    RuntimeNotificationSourceType.APPROVAL,
                    task.taskId(),
                    task.createdAt(),
                    null,
                    ""
            ));
        }
        if (task.status() == ExecutionTaskStatus.SUCCESS) {
            return List.of(localTaskNotification(projectId, task, RuntimeNotificationLevel.SUCCESS, "本地命令执行成功"));
        }
        if (task.status() == ExecutionTaskStatus.FAILED) {
            return List.of(localTaskNotification(projectId, task, RuntimeNotificationLevel.ERROR, "本地命令执行失败"));
        }
        if (task.status() == ExecutionTaskStatus.CANCELED) {
            return List.of(localTaskNotification(projectId, task, RuntimeNotificationLevel.WARNING, "本地命令已取消"));
        }
        return List.of();
    }

    private RuntimeNotification localTaskNotification(
            String projectId,
            ExecutionTask task,
            RuntimeNotificationLevel level,
            String title
    ) {
        return new RuntimeNotification(
                "local-task:" + task.taskId() + ":" + task.status(),
                projectId,
                level,
                title,
                task.command(),
                RuntimeNotificationSourceType.LOCAL_TASK,
                task.taskId(),
                task.canceledAt() == null ? task.createdAt() : task.canceledAt(),
                null,
                ""
        );
    }

    private List<RuntimeNotification> fromCompose(String projectId, ComposeRuntimeView view) {
        var operation = view.latestOperation();
        if (operation == null) {
            return List.of();
        }
        var failed = operation.status() == ComposeOperationStatus.FAILED;
        return List.of(new RuntimeNotification(
                "compose:" + operation.id() + ":" + operation.status(),
                projectId,
                failed ? RuntimeNotificationLevel.ERROR : RuntimeNotificationLevel.SUCCESS,
                failed ? "Compose 动作失败" : "Compose 动作成功",
                operation.summary(),
                RuntimeNotificationSourceType.COMPOSE_OPERATION,
                operation.id(),
                operation.createdAt(),
                null,
                ""
        ));
    }

    private void trim(String projectId, Map<String, RuntimeNotification> projectNotifications) {
        var sorted = projectNotifications.values().stream()
                .sorted(notificationComparator())
                .toList();
        if (sorted.size() <= PROJECT_LIMIT) {
            return;
        }
        var retainedIds = sorted.stream()
                .limit(PROJECT_LIMIT)
                .map(RuntimeNotification::id)
                .toList();
        projectNotifications.keySet().removeIf(id -> !retainedIds.contains(id));
        readReceipts.getOrDefault(projectId, Map.of()).values().forEach(reads ->
                reads.keySet().removeIf(id -> !retainedIds.contains(id))
        );
    }

    private List<RuntimeNotification> sorted(String projectId) {
        return notifications.getOrDefault(projectId, Map.of()).values().stream()
                .sorted(notificationComparator())
                .toList();
    }

    private void restore(RuntimeNotificationSnapshot snapshot) {
        snapshot.projects().forEach((projectId, projectNotifications) -> {
            var restored = new ConcurrentHashMap<String, RuntimeNotification>();
            projectNotifications.forEach(notification -> restored.put(notification.id(), notification));
            trim(projectId, restored);
            notifications.put(projectId, restored);
        });
        snapshot.readReceipts().forEach((projectId, users) -> {
            var restoredUsers = new ConcurrentHashMap<String, Map<String, Instant>>();
            users.forEach((userId, reads) -> restoredUsers.put(userId, new ConcurrentHashMap<>(reads)));
            readReceipts.put(projectId, restoredUsers);
        });
    }

    private void persist() {
        store.save(new RuntimeNotificationSnapshot(
                1,
                notifications.keySet().stream()
                        .collect(Collectors.toMap(projectId -> projectId, this::sorted)),
                readReceiptSnapshot()
        ));
    }

    private Map<String, Map<String, Map<String, Instant>>> readReceiptSnapshot() {
        var copy = new HashMap<String, Map<String, Map<String, Instant>>>();
        readReceipts.forEach((projectId, users) -> {
            var userCopy = new HashMap<String, Map<String, Instant>>();
            users.forEach((userId, reads) -> {
                if (!reads.isEmpty()) {
                    userCopy.put(userId, Map.copyOf(reads));
                }
            });
            if (!userCopy.isEmpty()) {
                copy.put(projectId, Map.copyOf(userCopy));
            }
        });
        return Map.copyOf(copy);
    }

    private RuntimeNotification notificationForUser(RuntimeNotification notification, String actorUserId) {
        if (actorUserId == null || actorUserId.isBlank()) {
            return notification;
        }
        var readAt = readReceipts
                .getOrDefault(notification.projectId(), Map.of())
                .getOrDefault(actorUserId, Map.of())
                .get(notification.id());
        if (readAt != null) {
            return notificationForUser(notification, actorUserId, readAt);
        }
        if (!notification.readByUserId().isBlank() && !notification.readByUserId().equals(actorUserId)) {
            return notificationForUser(notification, "", null);
        }
        return notification;
    }

    private RuntimeNotification notificationForUser(RuntimeNotification notification, String actorUserId, Instant readAt) {
        return new RuntimeNotification(
                notification.id(),
                notification.projectId(),
                notification.level(),
                notification.title(),
                notification.message(),
                notification.sourceType(),
                notification.sourceId(),
                notification.occurredAt(),
                readAt,
                actorUserId
        );
    }

    private Comparator<RuntimeNotification> notificationComparator() {
        return Comparator.comparing(RuntimeNotification::occurredAt).reversed()
                .thenComparing(notification -> levelWeight(notification.level()), Comparator.reverseOrder())
                .thenComparing(RuntimeNotification::id);
    }

    private int levelWeight(RuntimeNotificationLevel level) {
        return switch (level) {
            case ACTION -> 4;
            case ERROR -> 3;
            case WARNING -> 2;
            case SUCCESS -> 1;
        };
    }

    private String requireText(String value, String message) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(message);
        }
        return value.trim();
    }

    private String optionalText(String value) {
        return value == null ? "" : value.trim();
    }
}
