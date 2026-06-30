package com.matrixcode.runtime;

import com.matrixcode.approval.domain.ApprovalDecision;
import com.matrixcode.deployment.domain.ComposeEnvironmentStatus;
import com.matrixcode.deployment.domain.ComposeOperationRecord;
import com.matrixcode.deployment.domain.ComposeOperationStatus;
import com.matrixcode.deployment.domain.ComposeOperationType;
import com.matrixcode.localexecution.domain.ExecutionTask;
import com.matrixcode.localexecution.domain.ExecutionTaskStatus;
import com.matrixcode.localexecution.domain.LocalExecutionSummary;
import com.matrixcode.runtime.application.RuntimeNotificationSnapshot;
import com.matrixcode.runtime.application.RuntimeNotificationService;
import com.matrixcode.runtime.application.RuntimeNotificationStore;
import com.matrixcode.runtime.domain.RuntimeNotificationLevel;
import com.matrixcode.workbench.domain.ComposeRuntimeView;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RuntimeNotificationServiceTest {

    @Test
    void 同步本地任务和Compose操作生成稳定提醒() {
        var service = new RuntimeNotificationService();

        var notifications = service.sync(
                "demo",
                localExecution(List.of(
                        task("task-approval", "git status", ExecutionTaskStatus.APPROVAL_PENDING, null, "2026-06-25T06:00:00Z"),
                        task("task-success", "sleep 1", ExecutionTaskStatus.SUCCESS, 0, "2026-06-25T06:01:00Z"),
                        task("task-failed", "exit 1", ExecutionTaskStatus.FAILED, 1, "2026-06-25T06:02:00Z"),
                        canceledTask("task-canceled", "sleep 9", "2026-06-25T06:03:00Z")
                )),
                List.of(compose("compose-op-1", ComposeOperationStatus.FAILED, "Docker Compose 命令超时", "2026-06-25T06:04:00Z"))
        );

        assertThat(notifications).extracting("id")
                .containsExactly(
                        "compose:compose-op-1:FAILED",
                        "local-task:task-canceled:CANCELED",
                        "local-task:task-failed:FAILED",
                        "local-task:task-success:SUCCESS",
                        "approval:task-approval"
                );
        assertThat(notifications.getFirst().level()).isEqualTo(RuntimeNotificationLevel.ERROR);
        assertThat(notifications.getFirst().title()).isEqualTo("Compose 动作失败");
    }

    @Test
    void 重复同步保留已读时间() {
        var service = new RuntimeNotificationService();
        service.sync(
                "demo",
                localExecution(List.of(task("task-1", "git status", ExecutionTaskStatus.APPROVAL_PENDING, null, "2026-06-25T06:00:00Z"))),
                List.of()
        );

        var read = service.markRead("demo", "approval:task-1", "user-reviewer");
        var afterSync = service.sync(
                "demo",
                localExecution(List.of(task("task-1", "git status", ExecutionTaskStatus.APPROVAL_PENDING, null, "2026-06-25T06:00:00Z"))),
                List.of(),
                "user-reviewer"
        );

        assertThat(read.readAt()).isNotNull();
        assertThat(read.readByUserId()).isEqualTo("user-reviewer");
        assertThat(afterSync.getFirst().readAt()).isEqualTo(read.readAt());
        assertThat(afterSync.getFirst().readByUserId()).isEqualTo("user-reviewer");
    }

    @Test
    void 用户级已读不会影响其他成员未读状态() {
        var service = new RuntimeNotificationService();
        service.sync(
                "demo",
                localExecution(List.of(task("task-1", "git status", ExecutionTaskStatus.APPROVAL_PENDING, null, "2026-06-25T06:00:00Z"))),
                List.of()
        );

        var productRead = service.markRead("demo", "approval:task-1", "user-product");

        assertThat(service.recentForUser("demo", "user-product").getFirst().readAt()).isEqualTo(productRead.readAt());
        assertThat(service.recentForUser("demo", "user-product").getFirst().readByUserId()).isEqualTo("user-product");
        assertThat(service.recentForUser("demo", "user-developer").getFirst().readAt()).isNull();
        assertThat(service.recentForUser("demo", "user-developer").getFirst().readByUserId()).isBlank();
    }

    @Test
    void 用户级已读状态随快照恢复() {
        var store = new CapturingRuntimeNotificationStore();
        var service = new RuntimeNotificationService(store);
        service.sync(
                "demo",
                localExecution(List.of(task("task-1", "git status", ExecutionTaskStatus.APPROVAL_PENDING, null, "2026-06-25T06:00:00Z"))),
                List.of()
        );
        var productRead = service.markRead("demo", "approval:task-1", "user-product");

        var restored = new RuntimeNotificationService(store);

        assertThat(restored.recentForUser("demo", "user-product").getFirst().readAt()).isEqualTo(productRead.readAt());
        assertThat(restored.recentForUser("demo", "user-developer").getFirst().readAt()).isNull();
        assertThat(store.saved.readReceipts().get("demo").get("user-product").get("approval:task-1"))
                .isEqualTo(productRead.readAt());
    }

    @Test
    void 重建服务后恢复提醒和已读状态() {
        var store = new CapturingRuntimeNotificationStore();
        var service = new RuntimeNotificationService(store);
        service.sync(
                "demo",
                localExecution(List.of(task("task-1", "git status", ExecutionTaskStatus.APPROVAL_PENDING, null, "2026-06-25T06:00:00Z"))),
                List.of()
        );
        service.markRead("demo", "approval:task-1");

        var restored = new RuntimeNotificationService(store);

        assertThat(restored.recent("demo")).hasSize(1);
        assertThat(restored.recent("demo").getFirst().readAt()).isNotNull();
    }

    @Test
    void 恢复快照后重复同步保留已读状态并写回存储() {
        var store = new CapturingRuntimeNotificationStore();
        var service = new RuntimeNotificationService(store);
        service.sync(
                "demo",
                localExecution(List.of(task("task-1", "git status", ExecutionTaskStatus.APPROVAL_PENDING, null, "2026-06-25T06:00:00Z"))),
                List.of()
        );
        var firstRead = service.markAllRead("demo", "user-reviewer").getFirst();
        var readAt = firstRead.readAt();
        assertThat(firstRead.readByUserId()).isEqualTo("user-reviewer");

        var restored = new RuntimeNotificationService(store);
        var afterSync = restored.sync(
                "demo",
                localExecution(List.of(task("task-1", "git status", ExecutionTaskStatus.APPROVAL_PENDING, null, "2026-06-25T06:00:00Z"))),
                List.of(),
                "user-reviewer"
        );

        assertThat(afterSync.getFirst().readAt()).isEqualTo(readAt);
        assertThat(afterSync.getFirst().readByUserId()).isEqualTo("user-reviewer");
        assertThat(store.saved.readReceipts().get("demo").get("user-reviewer").get("approval:task-1")).isEqualTo(readAt);
    }

    @Test
    void 批量标记项目提醒已读() {
        var service = new RuntimeNotificationService();
        service.sync(
                "demo",
                localExecution(List.of(
                        task("task-approval", "git status", ExecutionTaskStatus.APPROVAL_PENDING, null, "2026-06-25T06:00:00Z"),
                        task("task-success", "sleep 1", ExecutionTaskStatus.SUCCESS, 0, "2026-06-25T06:01:00Z")
                )),
                List.of()
        );

        var notifications = service.markAllRead("demo");

        assertThat(notifications).hasSize(2);
        assertThat(notifications).allSatisfy(notification -> assertThat(notification.readAt()).isNotNull());
        assertThat(service.recent("demo")).allSatisfy(notification -> assertThat(notification.readAt()).isNotNull());
    }

    @Test
    void 重复批量已读不会覆盖原已读时间() {
        var service = new RuntimeNotificationService();
        service.sync(
                "demo",
                localExecution(List.of(task("task-1", "git status", ExecutionTaskStatus.APPROVAL_PENDING, null, "2026-06-25T06:00:00Z"))),
                List.of()
        );

        var firstReadAt = service.markAllRead("demo").getFirst().readAt();
        var secondReadAt = service.markAllRead("demo").getFirst().readAt();

        assertThat(firstReadAt).isNotNull();
        assertThat(secondReadAt).isEqualTo(firstReadAt);
    }

    @Test
    void 每个项目最多保留五十条且最近接口返回十条() {
        var service = new RuntimeNotificationService();

        service.sync(
                "demo",
                localExecution(IntStream.range(0, 55)
                        .mapToObj(index -> task(
                                "task-" + index,
                                "echo " + index,
                                ExecutionTaskStatus.SUCCESS,
                                0,
                                "2026-06-25T06:%02d:00Z".formatted(index)
                        ))
                        .toList()),
                List.of()
        );

        assertThat(service.allForTesting("demo")).hasSize(50);
        assertThat(service.recent("demo")).hasSize(10);
        assertThat(service.recent("demo").getFirst().id()).isEqualTo("local-task:task-54:SUCCESS");
    }

    @Test
    void 标记不存在的提醒已读时返回中文错误() {
        var service = new RuntimeNotificationService();

        assertThatThrownBy(() -> service.markRead("demo", "approval:missing"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("运行态提醒不存在");
    }

    private LocalExecutionSummary localExecution(List<ExecutionTask> tasks) {
        return new LocalExecutionSummary(List.of(), List.of(), tasks, null, List.of());
    }

    private ExecutionTask task(
            String taskId,
            String command,
            ExecutionTaskStatus status,
            Integer exitCode,
            String createdAt
    ) {
        return new ExecutionTask(
                taskId,
                "demo",
                "workspace-1",
                "user-ops",
                "SHELL",
                command,
                status == ExecutionTaskStatus.APPROVAL_PENDING ? ApprovalDecision.ASK : ApprovalDecision.ALLOW,
                status,
                exitCode,
                "",
                status == ExecutionTaskStatus.FAILED ? "命令失败" : "",
                0,
                Instant.parse(createdAt)
        );
    }

    private ExecutionTask canceledTask(String taskId, String command, String canceledAt) {
        return new ExecutionTask(
                taskId,
                "demo",
                "workspace-1",
                "user-ops",
                "SHELL",
                command,
                ApprovalDecision.ALLOW,
                ExecutionTaskStatus.CANCELED,
                null,
                "",
                "",
                0,
                Instant.parse("2026-06-25T06:00:00Z"),
                "user-reviewer",
                "用户取消",
                Instant.parse("2026-06-25T06:00:10Z"),
                "",
                "user-reviewer",
                "不再需要",
                Instant.parse(canceledAt)
        );
    }

    private ComposeRuntimeView compose(
            String operationId,
            ComposeOperationStatus status,
            String summary,
            String createdAt
    ) {
        return new ComposeRuntimeView(
                "compose-1",
                "target-1",
                status == ComposeOperationStatus.FAILED ? ComposeEnvironmentStatus.FAILED : ComposeEnvironmentStatus.RUNNING,
                "compose.yml",
                "matrixcode-demo",
                "web",
                new ComposeOperationRecord(
                        operationId,
                        "demo",
                        "compose-1",
                        "user-ops",
                        ComposeOperationType.START,
                        status,
                        summary,
                        "",
                        Instant.parse(createdAt)
                )
        );
    }

    private static class CapturingRuntimeNotificationStore implements RuntimeNotificationStore {
        private RuntimeNotificationSnapshot saved = RuntimeNotificationSnapshot.empty();

        @Override
        public RuntimeNotificationSnapshot load() {
            return saved;
        }

        @Override
        public void save(RuntimeNotificationSnapshot snapshot) {
            saved = snapshot;
        }
    }
}
