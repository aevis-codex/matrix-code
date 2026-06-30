package com.matrixcode.workbench;

import com.matrixcode.agent.application.LocalProductDraftAgent;
import com.matrixcode.context.application.ContextEngine;
import com.matrixcode.modelgateway.application.DeterministicModelAdapter;
import com.matrixcode.modelgateway.application.ModelGatewayService;
import com.matrixcode.modelgateway.application.ModelProviderRegistry;
import com.matrixcode.modelgateway.application.PromptCacheEstimator;
import com.matrixcode.modelgateway.application.PromptContractBuilder;
import com.matrixcode.modelgateway.application.RoleModelBindingService;
import com.matrixcode.modelgateway.domain.ModelRequestCommand;
import com.matrixcode.modelgateway.domain.ModelRequestRecord;
import com.matrixcode.modelgateway.domain.ModelRole;
import com.matrixcode.realtime.application.ProjectEventBus;
import com.matrixcode.realtime.domain.ProjectEvent;
import com.matrixcode.roleagent.application.RoleAgentConfigService;
import com.matrixcode.usage.application.UsageCalculator;
import com.matrixcode.usage.domain.UsageRecord;
import com.matrixcode.workbench.application.InMemoryWorkbenchStateStore;
import com.matrixcode.workbench.application.ProjectActivityRepository;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

class ProjectActivityRepositoryServiceTest {

    @Test
    void 模型网关优先读取正式仓储并在请求完成后写回正式仓储() {
        var store = new InMemoryWorkbenchStateStore();
        var oldSnapshotRecord = modelRequest("old-request", "旧快照模型请求");
        store.saveModelRequests(Map.of("demo", List.of(oldSnapshotRecord)));
        var repository = new RecordingProjectActivityRepository();
        var repositoryRecord = modelRequest("repo-request", "正式仓储模型请求");
        repository.modelRequests = Map.of("demo", List.of(repositoryRecord));
        var service = gateway(store, repository, new ProjectEventBus());

        assertThat(service.recentRequests("demo")).containsExactly(repositoryRecord);

        service.request(new ModelRequestCommand("demo", ModelRole.PRODUCT, "补充登录流程", List.of()));

        assertThat(repository.modelRequests.get("demo"))
                .extracting(ModelRequestRecord::requestId)
                .containsExactly("repo-request", service.recentRequests("demo").get(1).requestId());
        assertThat(store.load().modelRequests()).containsEntry("demo", List.of(oldSnapshotRecord));
    }

    @Test
    void 模型网关在正式仓储为空时从旧快照回填() {
        var store = new InMemoryWorkbenchStateStore();
        var oldSnapshotRecord = modelRequest("old-request", "旧快照模型请求");
        store.saveModelRequests(Map.of("demo", List.of(oldSnapshotRecord)));
        var repository = new RecordingProjectActivityRepository();

        var service = gateway(store, repository, new ProjectEventBus());

        assertThat(service.recentRequests("demo")).containsExactly(oldSnapshotRecord);
        assertThat(repository.modelRequests.get("demo")).containsExactly(oldSnapshotRecord);
    }

    @Test
    void 项目事件总线优先读取正式仓储并保持订阅发布能力() throws Exception {
        var store = new InMemoryWorkbenchStateStore();
        var oldEvent = projectEvent("old-event", "旧快照事件");
        store.saveProjectEvents(Map.of("demo", List.of(oldEvent)));
        var repository = new RecordingProjectActivityRepository();
        var repositoryEvent = projectEvent("repo-event", "正式仓储事件");
        repository.projectEvents = Map.of("demo", List.of(repositoryEvent));
        var bus = new ProjectEventBus(store, repository);
        var received = new AtomicReference<ProjectEvent>();
        var subscription = bus.subscribe("demo", received::set);
        var newEvent = projectEvent("new-event", "后续实时事件");

        assertThat(bus.recent("demo")).containsExactly(repositoryEvent);

        bus.publish(newEvent);

        assertThat(received.get()).isEqualTo(newEvent);
        assertThat(repository.projectEvents.get("demo")).containsExactly(repositoryEvent, newEvent);
        assertThat(store.load().projectEvents()).containsEntry("demo", List.of(oldEvent));
        subscription.close();
    }

    @Test
    void 项目事件总线在正式仓储为空时从旧快照回填() {
        var store = new InMemoryWorkbenchStateStore();
        var oldEvent = projectEvent("old-event", "旧快照事件");
        store.saveProjectEvents(Map.of("demo", List.of(oldEvent)));
        var repository = new RecordingProjectActivityRepository();

        var bus = new ProjectEventBus(store, repository);

        assertThat(bus.recent("demo")).containsExactly(oldEvent);
        assertThat(repository.projectEvents.get("demo")).containsExactly(oldEvent);
    }

    private ModelGatewayService gateway(
            InMemoryWorkbenchStateStore store,
            ProjectActivityRepository repository,
            ProjectEventBus events
    ) {
        var providers = new ModelProviderRegistry();
        var roleAgentConfigs = new RoleAgentConfigService(store);
        return new ModelGatewayService(
                providers,
                new RoleModelBindingService(providers, store),
                new PromptContractBuilder(),
                new PromptCacheEstimator(),
                new UsageCalculator(),
                new ContextEngine(),
                List.of(new DeterministicModelAdapter(new LocalProductDraftAgent())),
                events,
                roleAgentConfigs,
                store,
                repository
        );
    }

    private ModelRequestRecord modelRequest(String id, String summary) {
        return new ModelRequestRecord(
                id,
                "demo",
                ModelRole.PRODUCT,
                "qwen",
                "qwen-plus",
                summary,
                new UsageRecord("demo:PRODUCT", "qwen-plus", 10, 20, 30, 0.33, 0.12, "CNY"),
                List.of("PROJECT_RULE"),
                Instant.parse("2026-06-25T12:00:00Z")
        );
    }

    private ProjectEvent projectEvent(String id, String message) {
        return new ProjectEvent(
                id,
                "demo",
                "MODEL_REQUEST_COMPLETED",
                message,
                Instant.parse("2026-06-25T12:00:05Z")
        );
    }

    private static final class RecordingProjectActivityRepository implements ProjectActivityRepository {
        private Map<String, List<ModelRequestRecord>> modelRequests = Map.of();
        private Map<String, List<ProjectEvent>> projectEvents = Map.of();

        @Override
        public Map<String, List<ModelRequestRecord>> loadModelRequests() {
            return modelRequests;
        }

        @Override
        public void saveModelRequests(Map<String, List<ModelRequestRecord>> requests) {
            modelRequests = copy(requests);
        }

        @Override
        public Map<String, List<ProjectEvent>> loadProjectEvents() {
            return projectEvents;
        }

        @Override
        public void saveProjectEvents(Map<String, List<ProjectEvent>> events) {
            projectEvents = copy(events);
        }

        private static <T> Map<String, List<T>> copy(Map<String, List<T>> values) {
            var result = new HashMap<String, List<T>>();
            values.forEach((key, list) -> result.put(key, List.copyOf(list)));
            return Map.copyOf(result);
        }
    }
}
