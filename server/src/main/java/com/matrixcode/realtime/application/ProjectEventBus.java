package com.matrixcode.realtime.application;

import com.matrixcode.realtime.domain.ProjectEvent;
import com.matrixcode.workbench.application.InMemoryWorkbenchStateStore;
import com.matrixcode.workbench.application.ProjectActivityRepository;
import com.matrixcode.workbench.application.WorkbenchStateStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

@Component
public class ProjectEventBus {

    private static final Logger LOGGER = LoggerFactory.getLogger(ProjectEventBus.class);

    private final Map<String, CopyOnWriteArrayList<ProjectEvent>> events = new ConcurrentHashMap<>();
    private final Map<String, CopyOnWriteArrayList<Consumer<ProjectEvent>>> subscribers = new ConcurrentHashMap<>();
    private final java.util.Set<String> eventIds = ConcurrentHashMap.newKeySet();
    private final WorkbenchStateStore stateStore;
    private final ProjectActivityRepository activityRepository;
    private final ProjectEventRelay eventRelay;
    private final AutoCloseable relaySubscription;

    public ProjectEventBus() {
        this(new InMemoryWorkbenchStateStore(), null, ProjectEventRelay.noop());
    }

    public ProjectEventBus(WorkbenchStateStore stateStore) {
        this(stateStore, null, ProjectEventRelay.noop());
    }

    @Autowired
    public ProjectEventBus(
            WorkbenchStateStore stateStore,
            Optional<ProjectActivityRepository> activityRepository,
            Optional<ProjectEventRelay> eventRelay
    ) {
        this(stateStore, activityRepository.orElse(null), eventRelay.orElseGet(ProjectEventRelay::noop));
    }

    public ProjectEventBus(WorkbenchStateStore stateStore, ProjectActivityRepository activityRepository) {
        this(stateStore, activityRepository, ProjectEventRelay.noop());
    }

    public ProjectEventBus(
            WorkbenchStateStore stateStore,
            ProjectActivityRepository activityRepository,
            ProjectEventRelay eventRelay
    ) {
        this.stateStore = stateStore;
        this.activityRepository = activityRepository;
        this.eventRelay = eventRelay == null ? ProjectEventRelay.noop() : eventRelay;
        loadInitialEvents();
        this.relaySubscription = this.eventRelay.subscribe(this::acceptRemote);
    }

    /**
     * 发布本机项目事件：先落本机状态和订阅者，再尝试广播到跨节点中继。
     */
    public void publish(ProjectEvent event) {
        var projectEvent = Objects.requireNonNull(event, "event 不能为空");
        if (recordAndNotify(projectEvent)) {
            publishToRelay(projectEvent);
        }
    }

    /**
     * 读取指定项目的本机最近事件快照。
     */
    public List<ProjectEvent> recent(String projectId) {
        if (projectId == null || projectId.isBlank()) {
            throw new IllegalArgumentException("projectId 不能为空");
        }

        var projectEvents = events.get(projectId);
        if (projectEvents == null) {
            return List.of();
        }
        return List.copyOf(projectEvents);
    }

    /**
     * 订阅指定项目后续事件，用于 SSE 控制器把运行态变化推送给浏览器。
     */
    public AutoCloseable subscribe(String projectId, Consumer<ProjectEvent> subscriber) {
        if (projectId == null || projectId.isBlank()) {
            throw new IllegalArgumentException("projectId 不能为空");
        }
        var eventSubscriber = Objects.requireNonNull(subscriber, "subscriber 不能为空");
        var projectSubscribers = subscribers.computeIfAbsent(projectId, ignored -> new CopyOnWriteArrayList<>());
        projectSubscribers.add(eventSubscriber);
        return () -> {
            projectSubscribers.remove(eventSubscriber);
            if (projectSubscribers.isEmpty()) {
                subscribers.remove(projectId, projectSubscribers);
            }
        };
    }

    /**
     * 关闭跨节点中继订阅，避免测试或应用关闭时泄漏底层消费者资源。
     */
    public void close() throws Exception {
        relaySubscription.close();
    }

    private void acceptRemote(ProjectEvent event) {
        recordAndNotify(Objects.requireNonNull(event, "event 不能为空"));
    }

    private void loadInitialEvents() {
        var persisted = activityRepository == null ? Map.<String, List<ProjectEvent>>of()
                : activityRepository.loadProjectEvents();
        if (!persisted.isEmpty()) {
            loadEvents(persisted);
            return;
        }

        var legacy = stateStore.load().projectEvents();
        loadEvents(legacy);
        if (activityRepository != null && !legacy.isEmpty()) {
            activityRepository.saveProjectEvents(legacy);
        }
    }

    private void loadEvents(Map<String, List<ProjectEvent>> source) {
        source.forEach((projectId, projectEvents) -> {
            events.put(projectId, new CopyOnWriteArrayList<>(projectEvents));
            projectEvents.forEach(event -> eventIds.add(event.id()));
        });
    }

    private void saveProjectEvents() {
        var snapshot = snapshot();
        if (activityRepository != null) {
            activityRepository.saveProjectEvents(snapshot);
            return;
        }
        stateStore.saveProjectEvents(snapshot);
    }

    private boolean recordAndNotify(ProjectEvent projectEvent) {
        if (!eventIds.add(projectEvent.id())) {
            return false;
        }
        events.computeIfAbsent(projectEvent.projectId(), ignored -> new CopyOnWriteArrayList<>())
                .add(projectEvent);
        saveProjectEvents();
        notifySubscribers(projectEvent);
        return true;
    }

    private void publishToRelay(ProjectEvent projectEvent) {
        try {
            eventRelay.publish(projectEvent);
        } catch (RuntimeException exception) {
            LOGGER.warn("项目事件跨节点中继发布失败 eventId={} projectId={}：{}",
                    projectEvent.id(),
                    projectEvent.projectId(),
                    exception.getMessage(),
                    exception
            );
        }
    }

    private void notifySubscribers(ProjectEvent projectEvent) {
        var projectSubscribers = subscribers.get(projectEvent.projectId());
        if (projectSubscribers != null) {
            projectSubscribers.forEach(subscriber -> subscriber.accept(projectEvent));
        }
    }

    private Map<String, List<ProjectEvent>> snapshot() {
        var snapshot = new HashMap<String, List<ProjectEvent>>();
        events.forEach((projectId, projectEvents) -> snapshot.put(projectId, List.copyOf(projectEvents)));
        return snapshot;
    }
}
