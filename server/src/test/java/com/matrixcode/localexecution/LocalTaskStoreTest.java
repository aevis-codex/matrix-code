package com.matrixcode.localexecution;

import com.matrixcode.approval.domain.ApprovalDecision;
import com.matrixcode.localexecution.application.InMemoryLocalExecutionStateStore;
import com.matrixcode.localexecution.application.LocalTaskStore;
import com.matrixcode.localexecution.domain.ExecutionTask;
import com.matrixcode.localexecution.domain.ExecutionTaskStatus;
import com.matrixcode.localexecution.domain.LocalTaskLogStream;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class LocalTaskStoreTest {

    @Test
    void 可以记录任务查询运行中任务并替换状态() {
        var store = new LocalTaskStore();
        var queued = task("task-1", ExecutionTaskStatus.QUEUED);

        store.record(queued);
        var running = store.replace("demo", "task-1", current -> new ExecutionTask(
                current.taskId(),
                current.projectId(),
                current.workspaceId(),
                current.actorId(),
                current.toolType(),
                current.command(),
                current.approvalDecision(),
                ExecutionTaskStatus.RUNNING,
                null,
                "",
                "",
                0,
                current.createdAt(),
                current.approverId(),
                current.approvalNote(),
                current.decidedAt(),
                current.safetyRejectionReason(),
                "",
                "",
                null
        ));

        assertThat(running.status()).isEqualTo(ExecutionTaskStatus.RUNNING);
        assertThat(store.recentTasks("demo")).extracting("taskId").containsExactly("task-1");
        assertThat(store.activeTasks("demo")).extracting("status").containsExactly(ExecutionTaskStatus.RUNNING);
    }

    @Test
    void 可以追加任务日志并按项目和任务查询() {
        var store = new LocalTaskStore();
        store.record(task("task-1", ExecutionTaskStatus.RUNNING));

        var log = store.appendLog("demo", "task-1", LocalTaskLogStream.SYSTEM, "任务已进入队列");

        assertThat(log.content()).isEqualTo("任务已进入队列");
        assertThat(store.logsForTask("demo", "task-1")).containsExactly(log);
        assertThat(store.recentLogs("demo")).containsExactly(log);
    }

    @Test
    void 替换任务不能改变项目编号和任务编号() {
        var store = new LocalTaskStore();
        store.record(task("task-1", ExecutionTaskStatus.RUNNING));

        assertThatThrownBy(() -> store.replace("demo", "task-1", current -> replacement(current, "other", "task-1")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("替换任务归属不一致");
        assertThatThrownBy(() -> store.replace("demo", "task-1", current -> replacement(current, "demo", "task-2")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("替换任务归属不一致");
        assertThat(store.require("demo", "task-1").taskId()).isEqualTo("task-1");
    }

    @Test
    void 相同任务编号在不同项目下日志互不串联() {
        var store = new LocalTaskStore();
        store.record(task("demo", "task-1", ExecutionTaskStatus.RUNNING));
        store.record(task("other", "task-1", ExecutionTaskStatus.RUNNING));

        var demoLog = store.appendLog("demo", "task-1", LocalTaskLogStream.SYSTEM, "demo 日志");
        var otherLog = store.appendLog("other", "task-1", LocalTaskLogStream.SYSTEM, "other 日志");

        assertThat(store.logsForTask("demo", "task-1")).containsExactly(demoLog);
        assertThat(store.logsForTask("other", "task-1")).containsExactly(otherLog);
        assertThat(store.recentLogs("demo")).containsExactly(demoLog);
        assertThat(store.recentLogs("other")).containsExactly(otherLog);
    }

    @Test
    void 超长日志截断后包含省略号且不超过上限() {
        var store = new LocalTaskStore();
        store.record(task("task-1", ExecutionTaskStatus.RUNNING));

        var log = store.appendLog("demo", "task-1", LocalTaskLogStream.STDOUT, "x".repeat(5000));

        assertThat(log.content().length()).isLessThanOrEqualTo(4096);
        assertThat(log.content()).endsWith("...");
    }

    @Test
    void 活跃任务超过历史上限时不会被淘汰() {
        var store = new LocalTaskStore();
        for (var index = 1; index <= 25; index++) {
            var status = switch (index % 3) {
                case 0 -> ExecutionTaskStatus.APPROVAL_PENDING;
                case 1 -> ExecutionTaskStatus.QUEUED;
                default -> ExecutionTaskStatus.RUNNING;
            };
            store.record(task("task-" + index, status));
        }

        assertThat(store.recentTasks("demo"))
                .hasSize(25)
                .extracting("taskId")
                .contains("task-1", "task-25");
        assertThat(store.activeTasks("demo")).hasSize(25);

        var canceled = store.replace("demo", "task-1", current -> withStatus(current, ExecutionTaskStatus.CANCELED));

        assertThat(canceled.status()).isEqualTo(ExecutionTaskStatus.CANCELED);
        assertThat(store.require("demo", "task-1").status()).isEqualTo(ExecutionTaskStatus.CANCELED);
    }

    @Test
    void 限制终态历史任务和日志容量并清理淘汰任务日志() {
        var store = new LocalTaskStore();
        for (var index = 1; index <= 21; index++) {
            var taskId = "task-" + index;
            store.record(task(taskId, ExecutionTaskStatus.SUCCESS));
            store.appendLog("demo", taskId, LocalTaskLogStream.SYSTEM, "日志-" + index);
        }

        assertThat(store.recentTasks("demo"))
                .hasSize(20)
                .extracting("taskId")
                .containsExactly(
                        "task-21", "task-20", "task-19", "task-18", "task-17",
                        "task-16", "task-15", "task-14", "task-13", "task-12",
                        "task-11", "task-10", "task-9", "task-8", "task-7",
                        "task-6", "task-5", "task-4", "task-3", "task-2"
                );
        assertThatThrownBy(() -> store.require("demo", "task-1"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("执行任务不存在");
        assertThat(store.recentLogs("demo"))
                .extracting("taskId")
                .doesNotContain("task-1")
                .hasSize(20);

        for (var index = 1; index <= 205; index++) {
            store.appendLog("demo", "task-21", LocalTaskLogStream.STDOUT, "输出-" + index);
        }

        assertThat(store.logsForTask("demo", "task-21"))
                .hasSize(200)
                .extracting("content")
                .contains("输出-205")
                .doesNotContain("输出-1", "输出-5");
        assertThat(store.recentLogs("demo")).hasSize(20);
    }

    @Test
    void 旧活跃任务替换为终态后仍保留为最近更新任务并可追加日志() {
        var store = new LocalTaskStore();
        store.record(task("old-running", ExecutionTaskStatus.RUNNING));
        for (var index = 1; index <= 21; index++) {
            store.record(task("terminal-" + index, ExecutionTaskStatus.SUCCESS));
        }

        var finished = store.replace("demo", "old-running", current -> withStatus(current, ExecutionTaskStatus.SUCCESS));
        var log = store.appendLog("demo", "old-running", LocalTaskLogStream.SYSTEM, "旧任务完成");

        assertThat(store.require("demo", "old-running")).isEqualTo(finished);
        assertThat(store.recentTasks("demo")).first().isEqualTo(finished);
        assertThat(log.content()).isEqualTo("旧任务完成");
        assertThat(store.logsForTask("demo", "old-running")).contains(log);
    }

    @Test
    void 全项目任务快照按项目复制且返回不可变集合() {
        var store = new LocalTaskStore();
        store.record(task("demo", "task-1", ExecutionTaskStatus.RUNNING));
        store.record(task("other", "task-1", ExecutionTaskStatus.SUCCESS));

        var snapshot = store.recentTasksByAllProjects();

        assertThat(snapshot).containsOnlyKeys("demo", "other");
        assertThat(snapshot.get("demo")).extracting("taskId").containsExactly("task-1");
        assertThat(snapshot.get("other")).extracting("taskId").containsExactly("task-1");
        assertThatThrownBy(() -> snapshot.put("new", List.of()))
                .isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> snapshot.get("demo").add(task("task-2", ExecutionTaskStatus.RUNNING)))
                .isInstanceOf(UnsupportedOperationException.class);

        store.record(task("demo", "task-2", ExecutionTaskStatus.RUNNING));

        assertThat(snapshot.get("demo")).extracting("taskId").containsExactly("task-1");
    }

    @Test
    void 查询不存在任务返回中文错误() {
        var store = new LocalTaskStore();

        assertThatThrownBy(() -> store.require("demo", "missing"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("执行任务不存在");
    }

    @Test
    void 重建服务后恢复任务和日志() {
        var persistence = new InMemoryLocalExecutionStateStore();
        var store = new LocalTaskStore(persistence);
        store.record(task("task-1", ExecutionTaskStatus.SUCCESS));
        store.appendLog("demo", "task-1", LocalTaskLogStream.SYSTEM, "任务运行完成，退出码：0");

        var restored = new LocalTaskStore(persistence);

        assertThat(restored.recentTasks("demo")).hasSize(1);
        assertThat(restored.recentLogs("demo")).hasSize(1);
        assertThat(restored.recentLogs("demo").getFirst().content()).contains("退出码：0");
    }

    @Test
    void 恢复运行中任务时转为已取消并追加系统日志() {
        var persistence = new InMemoryLocalExecutionStateStore();
        persistence.saveTasks(
                Map.of("demo", List.of(task("task-running", ExecutionTaskStatus.RUNNING))),
                Map.of()
        );

        var restored = new LocalTaskStore(persistence);

        assertThat(restored.recentTasks("demo").getFirst().status()).isEqualTo(ExecutionTaskStatus.CANCELED);
        assertThat(restored.recentLogs("demo")).anySatisfy(log ->
                assertThat(log.content()).contains("服务重启后任务已停止")
        );
        assertThat(persistence.load().tasks().get("demo").getFirst().status()).isEqualTo(ExecutionTaskStatus.CANCELED);
    }

    private ExecutionTask task(String taskId, ExecutionTaskStatus status) {
        return task("demo", taskId, status);
    }

    private ExecutionTask task(String projectId, String taskId, ExecutionTaskStatus status) {
        return new ExecutionTask(
                taskId,
                projectId,
                "workspace-1",
                "user-dev",
                "SHELL",
                "npm test",
                ApprovalDecision.ALLOW,
                status,
                null,
                "",
                "",
                0,
                Instant.now()
        );
    }

    private ExecutionTask replacement(ExecutionTask current, String projectId, String taskId) {
        return new ExecutionTask(
                taskId,
                projectId,
                current.workspaceId(),
                current.actorId(),
                current.toolType(),
                current.command(),
                current.approvalDecision(),
                current.status(),
                current.exitCode(),
                current.stdoutSummary(),
                current.stderrSummary(),
                current.durationMillis(),
                current.createdAt(),
                current.approverId(),
                current.approvalNote(),
                current.decidedAt(),
                current.safetyRejectionReason(),
                current.canceledBy(),
                current.cancelNote(),
                current.canceledAt()
        );
    }

    private ExecutionTask withStatus(ExecutionTask current, ExecutionTaskStatus status) {
        return new ExecutionTask(
                current.taskId(),
                current.projectId(),
                current.workspaceId(),
                current.actorId(),
                current.toolType(),
                current.command(),
                current.approvalDecision(),
                status,
                current.exitCode(),
                current.stdoutSummary(),
                current.stderrSummary(),
                current.durationMillis(),
                current.createdAt(),
                current.approverId(),
                current.approvalNote(),
                current.decidedAt(),
                current.safetyRejectionReason(),
                current.canceledBy(),
                current.cancelNote(),
                current.canceledAt()
        );
    }
}
