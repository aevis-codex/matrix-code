package com.matrixcode.localexecution;

import com.matrixcode.approval.domain.ApprovalDecision;
import com.matrixcode.localexecution.application.LocalTaskQueueService;
import com.matrixcode.localexecution.application.LocalTaskStore;
import com.matrixcode.localexecution.application.WorkspaceRegistry;
import com.matrixcode.localexecution.domain.ExecutionTask;
import com.matrixcode.localexecution.domain.ExecutionTaskStatus;
import com.matrixcode.localexecution.domain.LocalTaskLogStream;
import com.matrixcode.realtime.application.ProjectEventBus;
import com.matrixcode.realtime.domain.ProjectEvent;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

class LocalTaskQueueServiceTest {

    @TempDir
    Path workspace;

    private LocalTaskQueueService queue;
    private List<Thread> queueWorkerBaseline = List.of();

    @AfterEach
    void shutdownQueue() {
        if (queue != null) {
            queue.shutdown();
        }
    }

    @Test
    void 入队任务会异步运行并记录标准输出和事件() throws Exception {
        writePackageJson();
        var registry = new WorkspaceRegistry();
        var store = new LocalTaskStore();
        var events = new ProjectEventBus();
        queue = new LocalTaskQueueService(registry, store, events);
        var authorized = registry.authorize("demo", "当前项目", workspace.toString());

        var queued = queue.enqueue("demo", authorized.id(), "user-dev", "npm test", ApprovalDecision.ALLOW);

        assertThat(queued.status()).isEqualTo(ExecutionTaskStatus.QUEUED);
        await().atMost(Duration.ofSeconds(10)).untilAsserted(() ->
                assertThat(store.require("demo", queued.taskId()).status()).isEqualTo(ExecutionTaskStatus.SUCCESS));
        assertThat(store.logsForTask("demo", queued.taskId()))
                .anySatisfy(log -> {
                    assertThat(log.stream()).isEqualTo(LocalTaskLogStream.STDOUT);
                    assertThat(log.content()).contains("queue-ok");
                });
        assertThat(events.recent("demo"))
                .extracting(ProjectEvent::type)
                .contains("LOCAL_COMMAND_QUEUED", "LOCAL_COMMAND_STARTED", "LOCAL_COMMAND_COMPLETED");
    }

    @Test
    void 运行中任务可以取消并记录取消信息() {
        var registry = new WorkspaceRegistry();
        var store = new LocalTaskStore();
        var events = new ProjectEventBus();
        queue = new LocalTaskQueueService(registry, store, events);
        var authorized = registry.authorize("demo", "当前项目", workspace.toString());
        writeLongRunningScript("long-running.js");

        var queued = queue.enqueue("demo", authorized.id(), "user-dev",
                "node long-running.js", ApprovalDecision.ALLOW);
        await().atMost(Duration.ofSeconds(5)).untilAsserted(() ->
                assertThat(store.require("demo", queued.taskId()).status()).isEqualTo(ExecutionTaskStatus.RUNNING));

        var canceled = queue.cancel("demo", queued.taskId(), "user-reviewer", "验证取消");

        assertThat(canceled.status()).isEqualTo(ExecutionTaskStatus.CANCELED);
        assertThat(canceled.canceledBy()).isEqualTo("user-reviewer");
        assertThat(canceled.cancelNote()).isEqualTo("验证取消");
        assertThat(store.logsForTask("demo", queued.taskId()))
                .anySatisfy(log -> {
                    assertThat(log.stream()).isEqualTo(LocalTaskLogStream.SYSTEM);
                    assertThat(log.content()).contains("任务已取消");
                });
        await().during(Duration.ofMillis(500)).atMost(Duration.ofSeconds(1)).untilAsserted(() ->
                assertThat(store.require("demo", queued.taskId()).status()).isEqualTo(ExecutionTaskStatus.CANCELED));
    }

    @Test
    void 未批准任务不能入队且不会执行() throws Exception {
        var registry = new WorkspaceRegistry();
        var store = new LocalTaskStore();
        var events = new ProjectEventBus();
        queue = new LocalTaskQueueService(registry, store, events);
        var authorized = registry.authorize("demo", "当前项目", workspace.toString());
        var marker = workspace.resolve("approval-marker.txt");
        Files.writeString(workspace.resolve("approval-marker.js"), """
                require("node:fs").writeFileSync("approval-marker.txt", "x");
                """);
        var command = "node approval-marker.js";

        for (var decision : List.of(ApprovalDecision.ASK, ApprovalDecision.DENY)) {
            assertThatThrownBy(() -> queue.enqueue("demo", authorized.id(), "user-dev", command, decision))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("只有已批准任务可以进入执行队列");
        }
        assertThatThrownBy(() -> queue.enqueueExisting(task("existing-denied", "demo", authorized.id(), ApprovalDecision.DENY), workspace.toString()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("只有已批准任务可以进入执行队列");

        assertThat(store.recentTasks("demo")).isEmpty();
        assertThat(marker).doesNotExist();
    }

    @Test
    void 命令不会通过通用Shell解释元字符() throws Exception {
        Files.writeString(workspace.resolve("argv-writer.js"), """
                const fs = require("node:fs");
                fs.writeFileSync("argv-marker.txt", process.argv.slice(2).join("|"));
                """);
        var registry = new WorkspaceRegistry();
        var store = new LocalTaskStore();
        var events = new ProjectEventBus();
        queue = new LocalTaskQueueService(registry, store, events);
        var authorized = registry.authorize("demo", "当前项目", workspace.toString());

        var queued = queue.enqueue("demo", authorized.id(), "user-dev",
                "node argv-writer.js > shell-marker.txt", ApprovalDecision.ALLOW);

        await().atMost(Duration.ofSeconds(5)).untilAsserted(() ->
                assertThat(store.require("demo", queued.taskId()).status()).isEqualTo(ExecutionTaskStatus.SUCCESS));
        assertThat(Files.readString(workspace.resolve("argv-marker.txt"))).isEqualTo(">|shell-marker.txt");
        assertThat(workspace.resolve("shell-marker.txt")).doesNotExist();
    }

    @Test
    void enqueueExisting传入非授权工作区路径会被拒绝() throws Exception {
        var registry = new WorkspaceRegistry();
        var store = new LocalTaskStore();
        var events = new ProjectEventBus();
        queue = new LocalTaskQueueService(registry, store, events);
        var authorized = registry.authorize("demo", "当前项目", workspace.toString());
        var otherRoot = Files.createDirectory(workspace.resolve("other-root"));

        assertThatThrownBy(() -> queue.enqueueExisting(task("existing-root", "demo", authorized.id(), ApprovalDecision.ALLOW), otherRoot.toString()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("任务工作区路径与授权工作区不一致");

        assertThat(store.recentTasks("demo")).isEmpty();
    }

    @Test
    void 已存在队列任务入队校验失败时不会留下活跃队列状态() throws Exception {
        var registry = new WorkspaceRegistry();
        var store = new LocalTaskStore();
        var events = new ProjectEventBus();
        queue = new LocalTaskQueueService(registry, store, events);
        var authorized = registry.authorize("demo", "当前项目", workspace.toString());
        var queued = task("invalid-root-existing", "demo", authorized.id(), ApprovalDecision.ALLOW, "node queued.js");
        store.record(queued);
        Files.delete(workspace);

        assertThatThrownBy(() -> queue.enqueueExisting(queued, workspace.toString()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("工作区路径必须是已存在目录");

        assertThat(store.require("demo", queued.taskId()).status()).isEqualTo(ExecutionTaskStatus.FAILED);
        assertThat(store.logsForTask("demo", queued.taskId()))
                .anySatisfy(log -> assertThat(log.content()).contains("任务提交执行队列失败"));
        assertThat(store.activeTasks("demo"))
                .extracting("taskId")
                .doesNotContain(queued.taskId());
    }

    @Test
    void 同一个已存在队列任务不能重复提交调度() {
        var registry = new WorkspaceRegistry();
        var store = new LocalTaskStore();
        var events = new ProjectEventBus();
        queue = singleWorkerQueue(registry, store, events);
        var authorized = registry.authorize("demo", "当前项目", workspace.toString());
        writeLongRunningScript("worker-blocker.js");
        writeScript("duplicate-marker.js", """
                const fs = require("node:fs");
                const marker = "duplicate-marker.txt";
                const current = fs.existsSync(marker) ? Number(fs.readFileSync(marker, "utf8")) : 0;
                fs.writeFileSync(marker, String(current + 1));
                """);
        var blocker = queue.enqueue("demo", authorized.id(), "user-dev", "node worker-blocker.js", ApprovalDecision.ALLOW);
        await().atMost(Duration.ofSeconds(5)).untilAsserted(() ->
                assertThat(store.require("demo", blocker.taskId()).status()).isEqualTo(ExecutionTaskStatus.RUNNING));
        var queued = task("duplicate-task", "demo", authorized.id(), ApprovalDecision.ALLOW, "node duplicate-marker.js");

        queue.enqueueExisting(queued, workspace.toString());

        assertThatThrownBy(() -> queue.enqueueExisting(queued, workspace.toString()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("任务已进入队列");
        assertThat(store.logsForTask("demo", queued.taskId()))
                .filteredOn(log -> log.stream() == LocalTaskLogStream.SYSTEM)
                .extracting("content")
                .containsExactly("任务已进入队列");
        assertThat(events.recent("demo").stream()
                .filter(event -> event.type().equals("LOCAL_COMMAND_QUEUED"))
                .filter(event -> event.message().contains("duplicate-marker.js")))
                .hasSize(1);
        assertThat(workspace.resolve("duplicate-marker.txt")).doesNotExist();
    }

    @Test
    void 并发重复提交同一个队列任务只会调度一次() throws Exception {
        var registry = new WorkspaceRegistry();
        var store = new LocalTaskStore();
        var events = new ProjectEventBus();
        queue = singleWorkerQueue(registry, store, events);
        var authorized = registry.authorize("demo", "当前项目", workspace.toString());
        writeLongRunningScript("worker-blocker.js");
        writeScript("concurrent-marker.js", "require('node:fs').writeFileSync('concurrent-marker.txt', 'ran');");
        var blocker = queue.enqueue("demo", authorized.id(), "user-dev", "node worker-blocker.js", ApprovalDecision.ALLOW);
        await().atMost(Duration.ofSeconds(5)).untilAsserted(() ->
                assertThat(store.require("demo", blocker.taskId()).status()).isEqualTo(ExecutionTaskStatus.RUNNING));
        var queued = task("concurrent-duplicate-task", "demo", authorized.id(), ApprovalDecision.ALLOW, "node concurrent-marker.js");
        var ready = new CountDownLatch(2);
        var start = new CountDownLatch(1);
        var executor = Executors.newFixedThreadPool(2);

        Callable<Object> enqueue = () -> {
            ready.countDown();
            start.await();
            return queue.enqueueExisting(queued, workspace.toString());
        };

        var results = new ArrayList<Object>();
        try {
            var futures = List.of(executor.submit(enqueue), executor.submit(enqueue));
            assertThat(ready.await(2, TimeUnit.SECONDS)).isTrue();
            start.countDown();

            for (var future : futures) {
                try {
                    results.add(future.get(5, TimeUnit.SECONDS));
                } catch (ExecutionException exception) {
                    results.add(exception.getCause());
                }
            }
        } finally {
            executor.shutdownNow();
        }

        assertThat(results.stream().filter(ExecutionTask.class::isInstance)).hasSize(1);
        assertThat(results.stream().filter(IllegalArgumentException.class::isInstance)
                .map(IllegalArgumentException.class::cast))
                .singleElement()
                .satisfies(exception -> assertThat(exception).hasMessageContaining("任务已进入队列"));
        assertThat(store.logsForTask("demo", queued.taskId()))
                .filteredOn(log -> log.stream() == LocalTaskLogStream.SYSTEM)
                .extracting("content")
                .containsExactly("任务已进入队列");
        assertThat(events.recent("demo").stream()
                .filter(event -> event.type().equals("LOCAL_COMMAND_QUEUED"))
                .filter(event -> event.message().contains("concurrent-marker.js")))
                .hasSize(1);
        assertThat(workspace.resolve("concurrent-marker.txt")).doesNotExist();
    }

    @Test
    void 完成后的同一任务不能重复提交且新任务仍可入队() throws Exception {
        var registry = new WorkspaceRegistry();
        var store = new LocalTaskStore();
        var events = new ProjectEventBus();
        queue = new LocalTaskQueueService(registry, store, events);
        var authorized = registry.authorize("demo", "当前项目", workspace.toString());
        writeScript("completed-once.js", """
                const fs = require("node:fs");
                const marker = "completed-once-count.txt";
                const current = fs.existsSync(marker) ? Number(fs.readFileSync(marker, "utf8")) : 0;
                fs.writeFileSync(marker, String(current + 1));
                """);
        writeScript("new-task.js", "require('node:fs').writeFileSync('new-task-marker.txt', 'ok');");
        var completed = task("completed-task", "demo", authorized.id(), ApprovalDecision.ALLOW, "node completed-once.js");
        queue.enqueueExisting(completed, workspace.toString());
        await().atMost(Duration.ofSeconds(5)).untilAsserted(() ->
                assertThat(store.require("demo", completed.taskId()).status()).isEqualTo(ExecutionTaskStatus.SUCCESS));

        assertThatThrownBy(() -> queue.enqueueExisting(completed, workspace.toString()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("执行任务已存在");
        assertThat(Files.readString(workspace.resolve("completed-once-count.txt"))).isEqualTo("1");

        var other = task("completed-task-other", "demo", authorized.id(), ApprovalDecision.ALLOW, "node new-task.js");
        queue.enqueueExisting(other, workspace.toString());

        await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> {
            assertThat(store.require("demo", other.taskId()).status()).isEqualTo(ExecutionTaskStatus.SUCCESS);
            assertThat(workspace.resolve("new-task-marker.txt")).exists();
        });
    }

    @Test
    void 历史淘汰后复用任务编号会被拒绝且不会重复执行() throws Exception {
        var registry = new WorkspaceRegistry();
        var store = new LocalTaskStore();
        var events = new ProjectEventBus();
        queue = new LocalTaskQueueService(registry, store, events);
        var authorized = registry.authorize("demo", "当前项目", workspace.toString());
        writeScript("reused-id.js", "require('node:fs').appendFileSync('reused-id-count.txt', 'x');");
        writeScript("evict.js", "console.log('evict');");
        var original = task("reused-task", "demo", authorized.id(), ApprovalDecision.ALLOW, "node reused-id.js");
        queue.enqueueExisting(original, workspace.toString());
        await().atMost(Duration.ofSeconds(5)).untilAsserted(() ->
                assertThat(store.require("demo", original.taskId()).status()).isEqualTo(ExecutionTaskStatus.SUCCESS));

        for (int index = 0; index < 21; index++) {
            var evictingTask = task("evict-" + index, "demo", authorized.id(), ApprovalDecision.ALLOW, "node evict.js");
            queue.enqueueExisting(evictingTask, workspace.toString());
            await().atMost(Duration.ofSeconds(5)).untilAsserted(() ->
                    assertThat(store.require("demo", evictingTask.taskId()).status()).isEqualTo(ExecutionTaskStatus.SUCCESS));
        }
        await().atMost(Duration.ofSeconds(5)).untilAsserted(() ->
                assertThat(store.recentTasks("demo")).extracting("taskId").doesNotContain(original.taskId()));

        assertThatThrownBy(() -> queue.enqueueExisting(original, workspace.toString()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("执行任务已存在");
        assertThat(Files.readString(workspace.resolve("reused-id-count.txt"))).isEqualTo("x");
        assertThat(store.recentTasks("demo"))
                .filteredOn(task -> task.taskId().equals(original.taskId()))
                .extracting("status")
                .doesNotContain(ExecutionTaskStatus.QUEUED);
    }

    @Test
    void 已接收任务标记会按上限裁剪且历史内任务仍拒绝重复提交() throws Exception {
        var registry = new WorkspaceRegistry();
        var store = new LocalTaskStore();
        var events = new ProjectEventBus();
        queue = singleWorkerQueue(registry, store, events, Duration.ofSeconds(5), 3);
        var authorized = registry.authorize("demo", "当前项目", workspace.toString());
        writeScript("bounded-marker.js", "console.log('bounded-ok');");

        for (int index = 0; index < 5; index++) {
            var queued = task("bounded-" + index, "demo", authorized.id(), ApprovalDecision.ALLOW, "node bounded-marker.js");
            queue.enqueueExisting(queued, workspace.toString());
            await().atMost(Duration.ofSeconds(5)).untilAsserted(() ->
                    assertThat(store.require("demo", queued.taskId()).status()).isEqualTo(ExecutionTaskStatus.SUCCESS));
        }

        assertThat(acceptedTaskMarkerCount()).isLessThanOrEqualTo(3);
        var recentTask = task("bounded-4", "demo", authorized.id(), ApprovalDecision.ALLOW, "node bounded-marker.js");
        assertThatThrownBy(() -> queue.enqueueExisting(recentTask, workspace.toString()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("执行任务已存在");
    }

    @Test
    void 取消任务会终止子进程树且不会覆盖取消状态() throws Exception {
        Files.writeString(workspace.resolve("spawn-child.js"), """
                const { spawn } = require("node:child_process");
                spawn(process.execPath, [
                  "-e",
                  "const fs = require('node:fs'); fs.writeFileSync('child-ready.txt', 'ready'); setTimeout(() => fs.writeFileSync('child-marker.txt', 'leftover'), 3000); setTimeout(() => {}, 6000);"
                ], { stdio: "ignore" });
                setTimeout(() => {}, 6000);
                """);
        var registry = new WorkspaceRegistry();
        var store = new LocalTaskStore();
        var events = new ProjectEventBus();
        queue = new LocalTaskQueueService(registry, store, events);
        var authorized = registry.authorize("demo", "当前项目", workspace.toString());
        var ready = workspace.resolve("child-ready.txt");
        var marker = workspace.resolve("child-marker.txt");
        var queued = queue.enqueue("demo", authorized.id(), "user-dev", "node spawn-child.js", ApprovalDecision.ALLOW);
        await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> {
            assertThat(store.require("demo", queued.taskId()).status()).isEqualTo(ExecutionTaskStatus.RUNNING);
            assertThat(ready).exists();
        });

        queue.cancel("demo", queued.taskId(), "user-reviewer", "验证取消子进程");

        await().during(Duration.ofSeconds(4)).atMost(Duration.ofSeconds(5)).untilAsserted(() -> {
            assertThat(marker).doesNotExist();
            assertThat(store.require("demo", queued.taskId()).status()).isEqualTo(ExecutionTaskStatus.CANCELED);
        });
    }

    @Test
    void 执行线程被中断时会终止子进程树() throws Exception {
        Files.writeString(workspace.resolve("interrupt-child.js"), """
                const { spawn } = require("node:child_process");
                spawn(process.execPath, [
                  "-e",
                  "const fs = require('node:fs'); fs.writeFileSync('interrupt-ready.txt', 'ready'); setTimeout(() => fs.writeFileSync('interrupt-marker.txt', 'leftover'), 3000); setTimeout(() => {}, 6000);"
                ], { stdio: "ignore" });
                setTimeout(() => {}, 6000);
                """);
        var registry = new WorkspaceRegistry();
        var store = new LocalTaskStore();
        var events = new ProjectEventBus();
        queue = singleWorkerQueue(registry, store, events);
        var authorized = registry.authorize("demo", "当前项目", workspace.toString());
        var ready = workspace.resolve("interrupt-ready.txt");
        var marker = workspace.resolve("interrupt-marker.txt");
        var queued = queue.enqueue("demo", authorized.id(), "user-dev", "node interrupt-child.js", ApprovalDecision.ALLOW);
        await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> {
            assertThat(store.require("demo", queued.taskId()).status()).isEqualTo(ExecutionTaskStatus.RUNNING);
            assertThat(ready).exists();
        });

        interruptQueueWorker();

        await().atMost(Duration.ofSeconds(3)).untilAsserted(() ->
                assertThat(store.require("demo", queued.taskId()).status()).isEqualTo(ExecutionTaskStatus.FAILED));
        await().during(Duration.ofSeconds(4)).atMost(Duration.ofSeconds(5)).untilAsserted(() ->
                assertThat(marker).doesNotExist());
    }

    @Test
    void 任务超时时会终止子进程树并记录失败() throws Exception {
        Files.writeString(workspace.resolve("timeout-child.js"), """
                const { spawn } = require("node:child_process");
                spawn(process.execPath, [
                  "-e",
                  "const fs = require('node:fs'); fs.writeFileSync('timeout-ready.txt', 'ready'); setTimeout(() => fs.writeFileSync('timeout-marker.txt', 'leftover'), 3000); setTimeout(() => {}, 6000);"
                ], { stdio: "ignore" });
                setTimeout(() => {}, 6000);
                """);
        var registry = new WorkspaceRegistry();
        var store = new LocalTaskStore();
        var events = new ProjectEventBus();
        queue = singleWorkerQueue(registry, store, events, Duration.ofMillis(500));
        var authorized = registry.authorize("demo", "当前项目", workspace.toString());
        var ready = workspace.resolve("timeout-ready.txt");
        var marker = workspace.resolve("timeout-marker.txt");
        var queued = queue.enqueue("demo", authorized.id(), "user-dev", "node timeout-child.js", ApprovalDecision.ALLOW);
        await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> {
            assertThat(ready).exists();
            assertThat(store.require("demo", queued.taskId()).status())
                    .isIn(ExecutionTaskStatus.RUNNING, ExecutionTaskStatus.FAILED);
        });

        await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> {
            assertThat(store.require("demo", queued.taskId()).status()).isEqualTo(ExecutionTaskStatus.FAILED);
            assertThat(store.logsForTask("demo", queued.taskId()))
                    .anySatisfy(log -> assertThat(log.content()).contains("命令执行超时"));
        });
        await().during(Duration.ofSeconds(4)).atMost(Duration.ofSeconds(5)).untilAsserted(() ->
                assertThat(marker).doesNotExist());
    }

    @Test
    void 同项目重复任务编号会被拒绝且不同项目保持隔离() {
        var registry = new WorkspaceRegistry();
        var store = new LocalTaskStore();
        var events = new ProjectEventBus();
        queue = new LocalTaskQueueService(registry, store, events);
        var demoWorkspace = registry.authorize("demo", "当前项目", workspace.toString());
        var otherWorkspace = registry.authorize("other", "其他项目", workspace.toString());
        writeScript("same-one.js", "console.log('one');");
        writeScript("same-two.js", "console.log('two');");
        writeScript("same-other.js", "console.log('other');");

        var first = queue.enqueue("same-task", "demo", demoWorkspace.id(), "user-dev",
                "node same-one.js", ApprovalDecision.ALLOW);
        await().atMost(Duration.ofSeconds(5)).untilAsserted(() ->
                assertThat(store.require("demo", first.taskId()).status()).isEqualTo(ExecutionTaskStatus.SUCCESS));

        assertThatThrownBy(() -> queue.enqueue("same-task", "demo", demoWorkspace.id(), "user-dev",
                "node same-two.js", ApprovalDecision.ALLOW))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("执行任务已存在");
        var other = queue.enqueue("same-task", "other", otherWorkspace.id(), "user-dev",
                "node same-other.js", ApprovalDecision.ALLOW);

        assertThat(other.projectId()).isEqualTo("other");
        assertThat(other.taskId()).isEqualTo("same-task");
    }

    @Test
    void 已完成任务不能取消() throws Exception {
        writePackageJson();
        var registry = new WorkspaceRegistry();
        var store = new LocalTaskStore();
        var events = new ProjectEventBus();
        queue = new LocalTaskQueueService(registry, store, events);
        var authorized = registry.authorize("demo", "当前项目", workspace.toString());
        var queued = queue.enqueue("demo", authorized.id(), "user-dev", "npm test", ApprovalDecision.ALLOW);
        await().atMost(Duration.ofSeconds(10)).untilAsserted(() ->
                assertThat(store.require("demo", queued.taskId()).status()).isEqualTo(ExecutionTaskStatus.SUCCESS));

        assertThatThrownBy(() -> queue.cancel("demo", queued.taskId(), "user-reviewer", "太晚了"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("任务已结束，不能取消");
    }

    private void writePackageJson() throws Exception {
        Files.writeString(
                workspace.resolve("package.json"),
                "{\"scripts\":{\"test\":\"node -e \\\"console.log('queue-ok')\\\"\"}}"
        );
    }

    private void writeLongRunningScript(String filename) {
        writeScript(filename, "setTimeout(() => {}, 5000);");
    }

    private void writeScript(String filename, String content) {
        try {
            Files.writeString(workspace.resolve(filename), content);
        } catch (Exception exception) {
            throw new AssertionError("写入测试脚本失败", exception);
        }
    }

    private void interruptQueueWorker() {
        var activeWorkers = aliveQueueWorkers().stream()
                .filter(thread -> !queueWorkerBaseline.contains(thread))
                .toList();
        assertThat(activeWorkers).hasSize(1);
        activeWorkers.getFirst().interrupt();
    }

    private LocalTaskQueueService singleWorkerQueue(
            WorkspaceRegistry registry,
            LocalTaskStore store,
            ProjectEventBus events
    ) {
        queueWorkerBaseline = aliveQueueWorkers();
        try {
            var constructor = LocalTaskQueueService.class.getDeclaredConstructor(
                    WorkspaceRegistry.class,
                    LocalTaskStore.class,
                    ProjectEventBus.class,
                    int.class
            );
            constructor.setAccessible(true);
            return constructor.newInstance(registry, store, events, 1);
        } catch (ReflectiveOperationException exception) {
            throw new AssertionError("LocalTaskQueueService 应提供测试可见的单 worker 构造器", exception);
        }
    }

    private LocalTaskQueueService singleWorkerQueue(
            WorkspaceRegistry registry,
            LocalTaskStore store,
            ProjectEventBus events,
            Duration timeout
    ) {
        queueWorkerBaseline = aliveQueueWorkers();
        try {
            var constructor = LocalTaskQueueService.class.getDeclaredConstructor(
                    WorkspaceRegistry.class,
                    LocalTaskStore.class,
                    ProjectEventBus.class,
                    int.class,
                    Duration.class
            );
            constructor.setAccessible(true);
            return constructor.newInstance(registry, store, events, 1, timeout);
        } catch (ReflectiveOperationException exception) {
            throw new AssertionError("LocalTaskQueueService 应提供测试可见的超时构造器", exception);
        }
    }

    private LocalTaskQueueService singleWorkerQueue(
            WorkspaceRegistry registry,
            LocalTaskStore store,
            ProjectEventBus events,
            Duration timeout,
            int acceptedTaskLimit
    ) {
        queueWorkerBaseline = aliveQueueWorkers();
        try {
            var constructor = LocalTaskQueueService.class.getDeclaredConstructor(
                    WorkspaceRegistry.class,
                    LocalTaskStore.class,
                    ProjectEventBus.class,
                    int.class,
                    Duration.class,
                    int.class
            );
            constructor.setAccessible(true);
            return constructor.newInstance(registry, store, events, 1, timeout, acceptedTaskLimit);
        } catch (ReflectiveOperationException exception) {
            throw new AssertionError("LocalTaskQueueService 应提供测试可见的已接收任务标记上限构造器", exception);
        }
    }

    private int acceptedTaskMarkerCount() {
        try {
            var method = LocalTaskQueueService.class.getDeclaredMethod("acceptedTaskMarkerCount");
            method.setAccessible(true);
            return (Integer) method.invoke(queue);
        } catch (ReflectiveOperationException exception) {
            throw new AssertionError("LocalTaskQueueService 应提供测试可见的已接收任务标记计数", exception);
        }
    }

    private List<Thread> aliveQueueWorkers() {
        return Thread.getAllStackTraces().keySet().stream()
                .filter(thread -> thread.getName().startsWith("local-task-queue-"))
                .filter(Thread::isAlive)
                .toList();
    }

    private ExecutionTask task(String taskId, String projectId, String workspaceId, ApprovalDecision decision) {
        return task(taskId, projectId, workspaceId, decision, "node queued.js");
    }

    private ExecutionTask task(
            String taskId,
            String projectId,
            String workspaceId,
            ApprovalDecision decision,
            String command
    ) {
        return new ExecutionTask(
                taskId,
                projectId,
                workspaceId,
                "user-dev",
                "SHELL",
                command,
                decision,
                ExecutionTaskStatus.QUEUED,
                null,
                "",
                "",
                0,
                Instant.now()
        );
    }
}
