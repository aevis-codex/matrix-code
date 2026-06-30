package com.matrixcode.realtime;

import com.matrixcode.identity.api.ProjectRequestPermissionGuard;
import com.matrixcode.identity.api.RequestActorResolver;
import com.matrixcode.identity.api.SaTokenActorSession;
import com.matrixcode.identity.application.MatrixCodeAuthProperties;
import com.matrixcode.identity.application.ProjectIdentityRepository;
import com.matrixcode.identity.application.ProjectIdentityService;
import com.matrixcode.identity.application.ProjectMemberPermissionGuard;
import com.matrixcode.identity.application.SignedActorTokenService;
import com.matrixcode.identity.domain.MatrixUser;
import com.matrixcode.identity.domain.ProjectMember;
import com.matrixcode.identity.domain.UserAuditRecord;
import com.matrixcode.realtime.api.ProjectEventController;
import com.matrixcode.realtime.application.ProjectEventBus;
import com.matrixcode.realtime.application.ProjectEventRelay;
import com.matrixcode.realtime.domain.ProjectEvent;
import jakarta.servlet.http.HttpServletRequest;
import com.matrixcode.workbench.application.InMemoryWorkbenchStateStore;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class ProjectEventStreamTest {

    @Test
    void 发布项目事件后可以读取最近事件() {
        var bus = new ProjectEventBus();
        bus.publish(new ProjectEvent("project-1", "WORKFLOW_CHANGED", "需求已冻结"));

        assertThat(bus.recent("project-1")).hasSize(1);
        assertThat(bus.recent("project-1").getFirst().message()).isEqualTo("需求已冻结");
    }

    @Test
    void 不同项目的事件互相隔离并保持发布顺序() {
        var bus = new ProjectEventBus();
        bus.publish(new ProjectEvent("project-1", "WORKFLOW_CHANGED", "需求已冻结"));
        bus.publish(new ProjectEvent("project-2", "WORKFLOW_CHANGED", "任务已开始"));
        bus.publish(new ProjectEvent("project-1", "AGENT_REPORTED", "验收已提交"));

        assertThat(bus.recent("project-1"))
                .extracting(ProjectEvent::message)
                .containsExactly("需求已冻结", "验收已提交");
        assertThat(bus.recent("project-2"))
                .extracting(ProjectEvent::message)
                .containsExactly("任务已开始");
    }

    @Test
    void 最近事件返回副本且外部不能修改总线状态() {
        var bus = new ProjectEventBus();
        bus.publish(new ProjectEvent("project-1", "WORKFLOW_CHANGED", "需求已冻结"));

        var recent = bus.recent("project-1");

        assertThatThrownBy(() -> recent.add(new ProjectEvent("project-1", "AGENT_REPORTED", "验收已提交")))
                .isInstanceOf(UnsupportedOperationException.class);
        assertThat(bus.recent("project-1"))
                .extracting(ProjectEvent::message)
                .containsExactly("需求已冻结");
    }

    @Test
    void 服务重建后恢复项目事件且不恢复订阅者() {
        var store = new InMemoryWorkbenchStateStore();
        var firstBus = new ProjectEventBus(store);
        firstBus.publish(new ProjectEvent("project-1", "WORKFLOW_CHANGED", "需求已冻结"));

        var secondBus = new ProjectEventBus(store);

        assertThat(secondBus.recent("project-1"))
                .extracting(ProjectEvent::message)
                .containsExactly("需求已冻结");
    }

    @Test
    void 项目事件拒绝空白核心字段() {
        assertThatThrownBy(() -> new ProjectEvent("", "WORKFLOW_CHANGED", "需求已冻结"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("projectId");
        assertThatThrownBy(() -> new ProjectEvent("project-1", " ", "需求已冻结"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("type");
        assertThatThrownBy(() -> new ProjectEvent("project-1", "WORKFLOW_CHANGED", null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("message");
    }

    @Test
    void 并发发布同一项目事件不会丢失() {
        assertTimeoutPreemptively(Duration.ofSeconds(5), () -> {
            var bus = new ProjectEventBus();
            var executor = Executors.newFixedThreadPool(8);
            var ready = new CountDownLatch(8);
            var start = new CountDownLatch(1);
            try {
                var tasks = new ArrayList<Callable<Void>>();
                IntStream.range(0, 8).forEach(threadIndex -> tasks.add(() -> {
                    ready.countDown();
                    start.await();
                    for (var eventIndex = 0; eventIndex < 200; eventIndex++) {
                        bus.publish(new ProjectEvent("project-1", "AGENT_REPORTED", "事件-" + threadIndex + "-" + eventIndex));
                    }
                    return null;
                }));

                for (var task : tasks) {
                    executor.submit(task);
                }
                assertThat(ready.await(1, TimeUnit.SECONDS)).isTrue();
                start.countDown();
                executor.shutdown();
                assertThat(executor.awaitTermination(3, TimeUnit.SECONDS)).isTrue();
            } finally {
                executor.shutdownNow();
            }

            assertThat(bus.recent("project-1")).hasSize(1_600);
        });
    }

    @Test
    void 订阅项目事件后会收到后续发布的事件() throws Exception {
        var bus = new ProjectEventBus();
        var received = new AtomicReference<ProjectEvent>();
        var receivedSignal = new CountDownLatch(1);

        var subscription = bus.subscribe("project-1", event -> {
            received.set(event);
            receivedSignal.countDown();
        });
        bus.publish(new ProjectEvent("project-1", "WORKFLOW_CHANGED", "需求已冻结"));

        assertThat(receivedSignal.await(1, TimeUnit.SECONDS)).isTrue();
        assertThat(received.get().message()).isEqualTo("需求已冻结");

        subscription.close();
    }

    @Test
    void 本地发布项目事件后会进入跨节点中继且远端事件只在本机落地一次() throws Exception {
        var relay = new RecordingProjectEventRelay();
        var bus = new ProjectEventBus(new InMemoryWorkbenchStateStore(), null, relay);
        var received = new ArrayList<ProjectEvent>();
        var receivedSignal = new CountDownLatch(2);
        bus.subscribe("project-1", event -> {
            received.add(event);
            receivedSignal.countDown();
        });

        var localEvent = new ProjectEvent(
                "local-event-1",
                "project-1",
                "WORKFLOW_CHANGED",
                "需求已冻结",
                Instant.parse("2026-06-29T10:00:00Z")
        );
        bus.publish(localEvent);
        var remoteEvent = new ProjectEvent(
                "remote-event-1",
                "project-1",
                "AGENT_REPORTED",
                "开发智能体已接收任务",
                Instant.parse("2026-06-29T10:00:01Z")
        );
        relay.deliver(remoteEvent);
        relay.deliver(remoteEvent);
        relay.deliver(localEvent);

        assertThat(receivedSignal.await(1, TimeUnit.SECONDS)).isTrue();
        assertThat(received)
                .extracting(ProjectEvent::id)
                .containsExactly("local-event-1", "remote-event-1");
        assertThat(bus.recent("project-1"))
                .extracting(ProjectEvent::id)
                .containsExactly("local-event-1", "remote-event-1");
        assertThat(relay.published())
                .extracting(ProjectEvent::id)
                .containsExactly("local-event-1");
    }

    @Test
    void 取消订阅后不再收到项目事件() throws Exception {
        var bus = new ProjectEventBus();
        var receivedSignal = new CountDownLatch(1);

        var subscription = bus.subscribe("project-1", ignored -> receivedSignal.countDown());
        subscription.close();
        bus.publish(new ProjectEvent("project-1", "WORKFLOW_CHANGED", "需求已冻结"));

        assertThat(receivedSignal.await(150, TimeUnit.MILLISECONDS)).isFalse();
    }

    @Test
    void 只读接口可以查询项目最近事件() throws Exception {
        var bus = new ProjectEventBus();
        bus.publish(new ProjectEvent("project-1", "WORKFLOW_CHANGED", "需求已冻结"));
        var mockMvc = mockMvc(bus, "project-1");

        mockMvc.perform(get("/api/projects/project-1/events")
                        .header(RequestActorResolver.CURRENT_USER_HEADER, "user-dev"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].projectId").value("project-1"))
                .andExpect(jsonPath("$[0].type").value("WORKFLOW_CHANGED"))
                .andExpect(jsonPath("$[0].message").value("需求已冻结"));
    }

    @Test
    void 流式接口以Sse连接订阅项目事件() throws Exception {
        var bus = new ProjectEventBus();
        var mockMvc = mockMvc(bus, "project-1");

        mockMvc.perform(get("/api/projects/project-1/events/stream")
                        .header(RequestActorResolver.CURRENT_USER_HEADER, "user-dev"))
                .andExpect(status().isOk())
                .andExpect(request().asyncStarted());

        var streamMapping = ProjectEventController.class
                .getMethod("stream", String.class, HttpServletRequest.class)
                .getAnnotation(GetMapping.class);
        assertThat(streamMapping.produces()).contains(MediaType.TEXT_EVENT_STREAM_VALUE);
    }

    @Test
    void 强制SaToken模式下事件流可以通过URL中的SaToken订阅() throws Exception {
        var bus = new ProjectEventBus();
        var properties = new MatrixCodeAuthProperties();
        properties.setRequireSaToken(true);
        var resolver = new RequestActorResolver(
                new SignedActorTokenService(properties),
                properties,
                new SaTokenActorSession() {
                    @Override
                    public Optional<String> currentUserId() {
                        return Optional.empty();
                    }

                    @Override
                    public Optional<String> userIdForToken(String token) {
                        return "sa-token-user-dev".equals(token) ? Optional.of("user-dev") : Optional.empty();
                    }
                }
        );
        var mockMvc = mockMvc(bus, "project-1", resolver);

        mockMvc.perform(get("/api/projects/project-1/events/stream")
                        .param("actorUserId", "user-dev")
                        .param("actorToken", "sa-token-user-dev"))
                .andExpect(status().isOk())
                .andExpect(request().asyncStarted());
    }

    @Test
    void 运行态事件可以通过项目事件流发布() throws Exception {
        var bus = new ProjectEventBus();
        bus.publish(new ProjectEvent("project-1", "LOCAL_COMMAND_COMPLETED", "任务运行完成，退出码：0"));
        bus.publish(new ProjectEvent("project-1", "COMPOSE_OPERATION_RECORDED", "运维启动了 Compose 演示环境"));
        var mockMvc = mockMvc(bus, "project-1");

        mockMvc.perform(get("/api/projects/project-1/events")
                        .header(RequestActorResolver.CURRENT_USER_HEADER, "user-dev"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].type").value("LOCAL_COMMAND_COMPLETED"))
                .andExpect(jsonPath("$[0].message").value("任务运行完成，退出码：0"))
                .andExpect(jsonPath("$[1].type").value("COMPOSE_OPERATION_RECORDED"))
                .andExpect(jsonPath("$[1].message").value("运维启动了 Compose 演示环境"));

        var streamMapping = ProjectEventController.class
                .getMethod("stream", String.class, HttpServletRequest.class)
                .getAnnotation(GetMapping.class);
        assertThat(streamMapping.produces()).contains(MediaType.TEXT_EVENT_STREAM_VALUE);
    }

    private MockMvc mockMvc(ProjectEventBus bus, String projectId) {
        return mockMvc(bus, projectId, new RequestActorResolver());
    }

    private MockMvc mockMvc(ProjectEventBus bus, String projectId, RequestActorResolver requestActorResolver) {
        var repository = new FakeProjectIdentityRepository();
        repository.ensureMember(member(projectId, "user-dev", "DEVELOPER"));
        var identityService = new ProjectIdentityService(repository, java.time.Clock.systemUTC());
        var permissionGuard = new ProjectMemberPermissionGuard(identityService);
        var requestPermissionGuard = new ProjectRequestPermissionGuard(requestActorResolver, permissionGuard);
        return MockMvcBuilders.standaloneSetup(new ProjectEventController(bus, requestPermissionGuard)).build();
    }

    private ProjectMember member(String projectId, String userId, String roleKey) {
        var now = Instant.parse("2026-06-29T10:00:00Z");
        return new ProjectMember(projectId + ":" + userId + ":" + roleKey, projectId, userId, roleKey, "ACTIVE", now, now, now);
    }

    private static final class RecordingProjectEventRelay implements ProjectEventRelay {
        private final List<ProjectEvent> published = new ArrayList<>();
        private Consumer<ProjectEvent> subscriber = ignored -> {
        };

        @Override
        public void publish(ProjectEvent event) {
            published.add(event);
        }

        @Override
        public AutoCloseable subscribe(Consumer<ProjectEvent> subscriber) {
            this.subscriber = subscriber;
            return () -> this.subscriber = ignored -> {
            };
        }

        private void deliver(ProjectEvent event) {
            subscriber.accept(event);
        }

        private List<ProjectEvent> published() {
            return published;
        }
    }

    private static final class FakeProjectIdentityRepository implements ProjectIdentityRepository {
        private final Map<String, MatrixUser> users = new LinkedHashMap<>();
        private final Map<String, ProjectMember> members = new LinkedHashMap<>();

        @Override
        public void ensureUser(MatrixUser user) {
            users.put(user.id(), user);
        }

        @Override
        public void ensureProject(String projectId, String name, String ownerUserId, String currentStage) {
        }

        @Override
        public void ensureMember(ProjectMember member) {
            members.put(member.projectId() + ":" + member.userId() + ":" + member.roleKey(), member);
        }

        @Override
        public List<ProjectMember> members(String projectId) {
            return members.values().stream()
                    .filter(member -> member.projectId().equals(projectId))
                    .toList();
        }

        @Override
        public List<String> projectsForUser(String userId) {
            return List.of();
        }

        @Override
        public List<UserAuditRecord> auditRecords(String projectId, String userId) {
            return List.of();
        }
    }
}
