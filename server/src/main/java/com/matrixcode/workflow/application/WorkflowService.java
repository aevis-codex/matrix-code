package com.matrixcode.workflow.application;

import com.matrixcode.workflow.domain.WorkflowEvent;
import com.matrixcode.workflow.domain.WorkflowEventType;
import com.matrixcode.workflow.domain.WorkflowItem;
import com.matrixcode.workflow.domain.WorkflowState;
import com.matrixcode.workbench.application.InMemoryWorkbenchStateStore;
import com.matrixcode.workbench.application.WorkbenchProgressRepository;
import com.matrixcode.workbench.application.WorkbenchStateSnapshot;
import com.matrixcode.workbench.application.WorkbenchStateStore;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public class WorkflowService {

    private final Object lock = new Object();
    private final Map<String, WorkflowItem> items = new ConcurrentHashMap<>();
    private final Map<String, List<WorkflowEvent>> events = new ConcurrentHashMap<>();
    private final WorkbenchStateStore stateStore;
    private final WorkbenchProgressRepository progressRepository;

    public WorkflowService() {
        this(new InMemoryWorkbenchStateStore());
    }

    public WorkflowService(WorkbenchStateStore stateStore) {
        this(stateStore, null);
    }

    public WorkflowService(WorkbenchStateStore stateStore, WorkbenchProgressRepository progressRepository) {
        this.stateStore = stateStore;
        this.progressRepository = progressRepository;
        loadInitialState();
    }

    public WorkflowItem createItem(String projectId, String title) {
        synchronized (lock) {
            var item = new WorkflowItem(UUID.randomUUID().toString(), projectId, title, WorkflowState.DRAFT);
            items.put(item.id(), item);
            events.put(item.id(), new ArrayList<>());
            save();
            return item;
        }
    }

    public WorkflowItem apply(String itemId, WorkflowEventType eventType, String actorId) {
        synchronized (lock) {
            var current = items.get(itemId);
            if (current == null) {
                throw new IllegalArgumentException("工作项不存在：" + itemId);
            }
            var nextState = nextState(current.state(), eventType);
            var next = current.withState(nextState);
            items.put(itemId, next);
            events.computeIfAbsent(itemId, ignored -> new ArrayList<>()).add(new WorkflowEvent(
                    UUID.randomUUID().toString(),
                    itemId,
                    eventType,
                    current.state(),
                    nextState,
                    actorId,
                    Instant.now()
            ));
            save();
            return next;
        }
    }

    public List<WorkflowEvent> eventsOf(String itemId) {
        synchronized (lock) {
            return List.copyOf(events.getOrDefault(itemId, List.of()));
        }
    }

    private WorkflowState nextState(WorkflowState state, WorkflowEventType eventType) {
        return switch (state) {
            case DRAFT -> switch (eventType) {
                case SUBMIT_REVIEW -> WorkflowState.REVIEW_PENDING;
                default -> throw illegal(state, eventType);
            };
            case REVIEW_PENDING -> switch (eventType) {
                case FREEZE -> WorkflowState.FROZEN;
                case REJECT -> WorkflowState.DRAFT;
                default -> throw illegal(state, eventType);
            };
            case FROZEN -> switch (eventType) {
                case START_WORK -> WorkflowState.IN_PROGRESS;
                default -> throw illegal(state, eventType);
            };
            case IN_PROGRESS -> switch (eventType) {
                case SUBMIT_ACCEPTANCE -> WorkflowState.ACCEPTANCE_PENDING;
                default -> throw illegal(state, eventType);
            };
            case ACCEPTANCE_PENDING -> switch (eventType) {
                case ACCEPT -> WorkflowState.DONE;
                case REJECT -> WorkflowState.IN_PROGRESS;
                default -> throw illegal(state, eventType);
            };
            case DONE -> throw illegal(state, eventType);
        };
    }

    private IllegalStateException illegal(WorkflowState state, WorkflowEventType eventType) {
        return new IllegalStateException("非法状态流转：" + state + " -> " + eventType);
    }

    private void save() {
        var itemSnapshot = List.copyOf(items.values());
        var eventSnapshot = new HashMap<String, List<WorkflowEvent>>();
        events.forEach((itemId, itemEvents) -> eventSnapshot.put(itemId, List.copyOf(itemEvents)));
        if (progressRepository != null) {
            progressRepository.saveWorkflowItems(itemSnapshot);
            progressRepository.saveWorkflowEvents(eventSnapshot);
            return;
        }
        stateStore.saveWorkflowItems(itemSnapshot);
        stateStore.saveWorkflowEvents(eventSnapshot);
    }

    private void loadInitialState() {
        if (progressRepository != null) {
            var persistedItems = progressRepository.loadWorkflowItems();
            var persistedEvents = progressRepository.loadWorkflowEvents();
            if (!persistedItems.isEmpty() || !persistedEvents.isEmpty()) {
                load(persistedItems, persistedEvents);
                return;
            }
        }

        var legacy = stateStore.load();
        load(legacy.workflowItems(), legacy.workflowEvents());
        if (progressRepository != null) {
            backfill(legacy);
        }
    }

    private void load(List<WorkflowItem> sourceItems, Map<String, List<WorkflowEvent>> sourceEvents) {
        sourceItems.forEach(item -> items.put(item.id(), item));
        sourceEvents.forEach((itemId, itemEvents) -> events.put(itemId, new ArrayList<>(itemEvents)));
    }

    private void backfill(WorkbenchStateSnapshot legacy) {
        if (!legacy.workflowItems().isEmpty()) {
            progressRepository.saveWorkflowItems(legacy.workflowItems());
        }
        if (!legacy.workflowEvents().isEmpty()) {
            progressRepository.saveWorkflowEvents(legacy.workflowEvents());
        }
    }
}
