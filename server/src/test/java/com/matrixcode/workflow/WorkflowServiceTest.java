package com.matrixcode.workflow;

import com.matrixcode.workflow.application.WorkflowService;
import com.matrixcode.workflow.domain.WorkflowEvent;
import com.matrixcode.workflow.domain.WorkflowEventType;
import com.matrixcode.workflow.domain.WorkflowState;
import com.matrixcode.workbench.application.InMemoryWorkbenchStateStore;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

class WorkflowServiceTest {

    private final WorkflowService service = new WorkflowService();

    @Test
    void 需求文档可以从草稿推进到已冻结() {
        var item = service.createItem("project-1", "PRD-001");

        var reviewed = service.apply(item.id(), WorkflowEventType.SUBMIT_REVIEW, "user-product");
        var frozen = service.apply(reviewed.id(), WorkflowEventType.FREEZE, "user-product");

        assertThat(frozen.state()).isEqualTo(WorkflowState.FROZEN);
        assertThat(service.eventsOf(frozen.id())).hasSize(2);
    }

    @Test
    void 草稿不能直接进入执行中() {
        var item = service.createItem("project-1", "PRD-001");

        assertThatThrownBy(() -> service.apply(item.id(), WorkflowEventType.START_WORK, "user-dev"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("非法状态流转");
    }

    @Test
    void 可以完成完整合法流转并按顺序记录事件字段() {
        var item = service.createItem("project-1", "PRD-001");

        var reviewed = service.apply(item.id(), WorkflowEventType.SUBMIT_REVIEW, "user-product");
        var frozen = service.apply(reviewed.id(), WorkflowEventType.FREEZE, "user-product");
        var started = service.apply(frozen.id(), WorkflowEventType.START_WORK, "user-dev");
        var acceptancePending = service.apply(started.id(), WorkflowEventType.SUBMIT_ACCEPTANCE, "user-dev");
        var done = service.apply(acceptancePending.id(), WorkflowEventType.ACCEPT, "user-product");

        assertThat(done.state()).isEqualTo(WorkflowState.DONE);
        assertThat(service.eventsOf(done.id()))
                .extracting(
                        WorkflowEvent::type,
                        WorkflowEvent::fromState,
                        WorkflowEvent::toState,
                        WorkflowEvent::actorId
                )
                .containsExactly(
                        tuple(WorkflowEventType.SUBMIT_REVIEW, WorkflowState.DRAFT, WorkflowState.REVIEW_PENDING, "user-product"),
                        tuple(WorkflowEventType.FREEZE, WorkflowState.REVIEW_PENDING, WorkflowState.FROZEN, "user-product"),
                        tuple(WorkflowEventType.START_WORK, WorkflowState.FROZEN, WorkflowState.IN_PROGRESS, "user-dev"),
                        tuple(WorkflowEventType.SUBMIT_ACCEPTANCE, WorkflowState.IN_PROGRESS, WorkflowState.ACCEPTANCE_PENDING, "user-dev"),
                        tuple(WorkflowEventType.ACCEPT, WorkflowState.ACCEPTANCE_PENDING, WorkflowState.DONE, "user-product")
                );
    }

    @Test
    void 驳回可以在评审和验收阶段回退() {
        var item = service.createItem("project-1", "PRD-001");

        var reviewPending = service.apply(item.id(), WorkflowEventType.SUBMIT_REVIEW, "user-product");
        var draft = service.apply(reviewPending.id(), WorkflowEventType.REJECT, "user-product");
        var frozen = service.apply(service.apply(draft.id(), WorkflowEventType.SUBMIT_REVIEW, "user-product").id(),
                WorkflowEventType.FREEZE, "user-product");
        var inProgress = service.apply(frozen.id(), WorkflowEventType.START_WORK, "user-dev");
        var acceptancePending = service.apply(inProgress.id(), WorkflowEventType.SUBMIT_ACCEPTANCE, "user-dev");
        var rejectedToWork = service.apply(acceptancePending.id(), WorkflowEventType.REJECT, "user-product");

        assertThat(draft.state()).isEqualTo(WorkflowState.DRAFT);
        assertThat(rejectedToWork.state()).isEqualTo(WorkflowState.IN_PROGRESS);
    }

    @Test
    void 服务重建后恢复工作项状态和事件历史() {
        var store = new InMemoryWorkbenchStateStore();
        var firstService = new WorkflowService(store);
        var item = firstService.createItem("project-1", "PRD-001");
        var reviewed = firstService.apply(item.id(), WorkflowEventType.SUBMIT_REVIEW, "user-product");

        var secondService = new WorkflowService(store);
        var frozen = secondService.apply(reviewed.id(), WorkflowEventType.FREEZE, "user-product");

        assertThat(frozen.state()).isEqualTo(WorkflowState.FROZEN);
        assertThat(secondService.eventsOf(item.id()))
                .extracting(WorkflowEvent::type)
                .containsExactly(WorkflowEventType.SUBMIT_REVIEW, WorkflowEventType.FREEZE);
    }

    @Test
    void 已完成状态拒绝继续流转() {
        var item = service.createItem("project-1", "PRD-001");
        var done = service.apply(
                service.apply(
                        service.apply(
                                service.apply(
                                        service.apply(item.id(), WorkflowEventType.SUBMIT_REVIEW, "user-product").id(),
                                        WorkflowEventType.FREEZE,
                                        "user-product"
                                ).id(),
                                WorkflowEventType.START_WORK,
                                "user-dev"
                        ).id(),
                        WorkflowEventType.SUBMIT_ACCEPTANCE,
                        "user-dev"
                ).id(),
                WorkflowEventType.ACCEPT,
                "user-product"
        );

        assertThatThrownBy(() -> service.apply(done.id(), WorkflowEventType.REJECT, "user-product"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("非法状态流转");
    }

    @Test
    void 并发读写同一工作项不会丢事件或抛出异常() {
        var item = service.createItem("project-1", "PRD-001");
        service.apply(item.id(), WorkflowEventType.SUBMIT_REVIEW, "user-product");
        service.apply(item.id(), WorkflowEventType.FREEZE, "user-product");
        service.apply(item.id(), WorkflowEventType.START_WORK, "user-dev");

        assertTimeoutPreemptively(Duration.ofSeconds(5), () -> {
            var executor = Executors.newFixedThreadPool(8);
            try {
                List<Callable<Void>> tasks = new ArrayList<>();
                tasks.add(() -> {
                    for (var i = 0; i < 500; i++) {
                        service.apply(item.id(), WorkflowEventType.SUBMIT_ACCEPTANCE, "user-dev");
                        service.apply(item.id(), WorkflowEventType.REJECT, "user-product");
                    }
                    return null;
                });
                IntStream.range(0, 7).forEach(index -> tasks.add(() -> {
                    for (var i = 0; i < 1_000; i++) {
                        assertThatCode(() -> service.eventsOf(item.id())).doesNotThrowAnyException();
                    }
                    return null;
                }));

                var results = executor.invokeAll(tasks);
                for (var result : results) {
                    result.get();
                }
            } finally {
                executor.shutdownNow();
                executor.awaitTermination(1, TimeUnit.SECONDS);
            }
        });

        assertThat(service.eventsOf(item.id())).hasSize(1_003);
    }

    @Test
    void 并发流转同一工作项时只能有一次基于当前状态成功() throws Exception {
        for (var round = 0; round < 100; round++) {
            var item = service.createItem("project-1", "PRD-" + round);
            service.apply(item.id(), WorkflowEventType.SUBMIT_REVIEW, "user-product");
            service.apply(item.id(), WorkflowEventType.FREEZE, "user-product");

            var executor = Executors.newFixedThreadPool(16);
            var ready = new CountDownLatch(16);
            var start = new CountDownLatch(1);
            var successCount = new AtomicInteger();
            try {
                var tasks = IntStream.range(0, 16)
                        .mapToObj(index -> (Callable<Void>) () -> {
                            ready.countDown();
                            start.await();
                            try {
                                service.apply(item.id(), WorkflowEventType.START_WORK, "user-dev-" + index);
                                successCount.incrementAndGet();
                            } catch (IllegalStateException ignored) {
                                // 其他线程看到状态已推进后，应被非法流转保护拦截。
                            }
                            return null;
                        })
                        .toList();

                for (var task : tasks) {
                    executor.submit(task);
                }
                ready.await(1, TimeUnit.SECONDS);
                start.countDown();
                executor.shutdown();
                assertThat(executor.awaitTermination(1, TimeUnit.SECONDS)).isTrue();
            } finally {
                executor.shutdownNow();
            }

            assertThat(successCount.get()).isEqualTo(1);
            assertThat(service.eventsOf(item.id()))
                    .extracting(WorkflowEvent::type)
                    .containsExactly(WorkflowEventType.SUBMIT_REVIEW, WorkflowEventType.FREEZE, WorkflowEventType.START_WORK);
        }
    }
}
