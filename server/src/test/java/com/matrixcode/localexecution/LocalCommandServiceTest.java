package com.matrixcode.localexecution;

import com.matrixcode.approval.application.ApprovalPolicy;
import com.matrixcode.approval.application.AuditService;
import com.matrixcode.approval.domain.ApprovalDecision;
import com.matrixcode.localexecution.application.LocalCommandService;
import com.matrixcode.localexecution.application.LocalTaskQueueService;
import com.matrixcode.localexecution.application.LocalTaskStore;
import com.matrixcode.localexecution.application.WorkspaceRegistry;
import com.matrixcode.localexecution.domain.ExecutionTask;
import com.matrixcode.localexecution.domain.ExecutionTaskStatus;
import com.matrixcode.localexecution.domain.LocalTaskLogStream;
import com.matrixcode.realtime.application.ProjectEventBus;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
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

class LocalCommandServiceTest {

    @TempDir
    Path workspace;

    private final List<LocalTaskQueueService> queues = new ArrayList<>();

    @AfterEach
    void shutdownQueues() {
        queues.forEach(LocalTaskQueueService::shutdown);
        queues.clear();
    }

    @Test
    void 安全命令会进入队列并记录最终结果() throws Exception {
        var services = services();
        var authorized = services.registry().authorize("demo", "当前项目", workspace.toString());
        writeNpmTestScript();

        var task = services.commandService().submit("demo", authorized.id(), "user-dev", "npm test");

        assertThat(task.approvalDecision()).isEqualTo(ApprovalDecision.ALLOW);
        assertThat(task.status()).isEqualTo(ExecutionTaskStatus.QUEUED);
        assertThat(task.exitCode()).isNull();
        assertThat(services.commandService().recentTasks("demo")).extracting("taskId").contains(task.taskId());
        assertThat(services.audit().records()).hasSize(1);
        await().atMost(Duration.ofSeconds(10)).untilAsserted(() ->
                assertThat(services.store().require("demo", task.taskId()).status()).isEqualTo(ExecutionTaskStatus.SUCCESS));
        assertThat(services.commandService().logsForTask("demo", task.taskId()))
                .anySatisfy(log -> {
                    assertThat(log.stream()).isEqualTo(LocalTaskLogStream.STDOUT);
                    assertThat(log.content()).contains("queue-ok");
                });
    }

    @Test
    void 危险命令只进入待审批不执行() {
        var services = services();
        var authorized = services.registry().authorize("demo", "当前项目", workspace.toString());

        var task = services.commandService().submit("demo", authorized.id(), "user-ops", "ssh prod systemctl restart app");

        assertThat(task.approvalDecision()).isEqualTo(ApprovalDecision.ASK);
        assertThat(task.status()).isEqualTo(ExecutionTaskStatus.APPROVAL_PENDING);
        assertThat(task.exitCode()).isNull();
        assertThat(services.audit().records().getFirst().summary()).isEqualTo("ssh prod systemctl restart app");
    }

    @Test
    void 空命令会被拒绝且不写入任务列表() {
        var services = services();
        var authorized = services.registry().authorize("demo", "当前项目", workspace.toString());

        assertThatThrownBy(() -> services.commandService().submit("demo", authorized.id(), "user-dev", " "))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("命令不能为空");
        assertThat(services.commandService().recentTasks("demo")).isEmpty();
    }

    @Test
    void 带凭证的未知命令进入待审批且审计摘要脱敏() {
        var services = services();
        var authorized = services.registry().authorize("demo", "当前项目", workspace.toString());

        var task = services.commandService().submit("demo", authorized.id(), "user-dev", "deploy --token sk-secret");

        assertThat(task.status()).isEqualTo(ExecutionTaskStatus.APPROVAL_PENDING);
        assertThat(services.audit().records().getFirst().summary()).isEqualTo("deploy --token ***");
    }

    @Test
    void 待审批命令可以被拒绝且不会执行() {
        var services = services();
        var authorized = services.registry().authorize("demo", "当前项目", workspace.toString());
        var pending = services.commandService().submit("demo", authorized.id(), "user-dev", "git status");

        var denied = services.commandService().decide("demo", pending.taskId(), "user-reviewer", ApprovalDecision.DENY, "风险太高");

        assertThat(denied.status()).isEqualTo(ExecutionTaskStatus.DENIED);
        assertThat(denied.approvalDecision()).isEqualTo(ApprovalDecision.DENY);
        assertThat(denied.exitCode()).isNull();
        assertThat(denied.approverId()).isEqualTo("user-reviewer");
        assertThat(denied.approvalNote()).isEqualTo("风险太高");
        assertThat(services.commandService().recentTasks("demo").getFirst().taskId()).isEqualTo(pending.taskId());
        assertThat(services.commandService().recentTasks("demo").getFirst().status()).isEqualTo(ExecutionTaskStatus.DENIED);
        assertThat(services.audit().records()).extracting("decision").containsExactly(ApprovalDecision.ASK, ApprovalDecision.DENY);
    }

    @Test
    void 待审批本地命令批准后进入队列() {
        var services = services();
        var authorized = services.registry().authorize("demo", "当前项目", workspace.toString());
        var pending = services.commandService().submit("demo", authorized.id(), "user-dev", "git status");

        var queued = services.commandService().decide("demo", pending.taskId(), "user-reviewer", ApprovalDecision.ALLOW, "允许查看状态");

        assertThat(queued.taskId()).isEqualTo(pending.taskId());
        assertThat(queued.command()).isEqualTo("git status");
        assertThat(queued.approvalDecision()).isEqualTo(ApprovalDecision.ALLOW);
        assertThat(queued.status()).isEqualTo(ExecutionTaskStatus.QUEUED);
        assertThat(queued.exitCode()).isNull();
        assertThat(queued.approverId()).isEqualTo("user-reviewer");
        assertThat(queued.decidedAt()).isNotNull();
        assertThat(services.commandService().recentTasks("demo").getFirst().taskId()).isEqualTo(pending.taskId());
        assertThat(services.audit().records()).extracting("decision").containsExactly(ApprovalDecision.ASK, ApprovalDecision.ALLOW);
    }

    @Test
    void 可以通过命令服务取消运行中任务() {
        var services = services();
        var authorized = services.registry().authorize("demo", "当前项目", workspace.toString());
        var pending = services.commandService().submit("demo", authorized.id(), "user-dev", "sleep 5");
        var queued = services.commandService().decide("demo", pending.taskId(), "user-reviewer", ApprovalDecision.ALLOW, "允许等待");
        await().atMost(Duration.ofSeconds(5)).untilAsserted(() ->
                assertThat(services.store().require("demo", queued.taskId()).status()).isEqualTo(ExecutionTaskStatus.RUNNING));

        var canceled = services.commandService().cancel("demo", queued.taskId(), "user-reviewer", "用户取消");

        assertThat(canceled.status()).isEqualTo(ExecutionTaskStatus.CANCELED);
        assertThat(canceled.canceledBy()).isEqualTo("user-reviewer");
        assertThat(canceled.cancelNote()).isEqualTo("用户取消");
        assertThat(services.commandService().recentTasks("demo").getFirst().status()).isEqualTo(ExecutionTaskStatus.CANCELED);
        assertThat(services.commandService().activeTasks("demo")).isEmpty();
        assertThat(services.commandService().recentLogs("demo"))
                .anySatisfy(log -> assertThat(log.content()).contains("任务已取消"));
        assertThat(services.audit().records()).extracting("decision")
                .containsExactly(ApprovalDecision.ASK, ApprovalDecision.ALLOW, ApprovalDecision.DENY);
        await().during(Duration.ofMillis(500)).atMost(Duration.ofSeconds(1)).untilAsserted(() ->
                assertThat(services.store().require("demo", queued.taskId()).status()).isEqualTo(ExecutionTaskStatus.CANCELED));
    }

    @Test
    void 待审批任务不能通过取消入口处理() {
        var services = services();
        var authorized = services.registry().authorize("demo", "当前项目", workspace.toString());
        var pending = services.commandService().submit("demo", authorized.id(), "user-dev", "git status");

        assertThatThrownBy(() -> services.commandService().cancel("demo", pending.taskId(), "user-reviewer", "取消待审批"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("任务未进入执行队列，不能取消");

        assertThat(services.store().require("demo", pending.taskId()).status()).isEqualTo(ExecutionTaskStatus.APPROVAL_PENDING);
        assertThat(services.commandService().logsForTask("demo", pending.taskId())).isEmpty();
        assertThat(services.audit().records()).extracting("decision").containsExactly(ApprovalDecision.ASK);
    }

    @Test
    void 活跃任务视图不包含待审批任务() {
        var services = services();
        var authorized = services.registry().authorize("demo", "当前项目", workspace.toString());
        var pending = services.commandService().submit("demo", authorized.id(), "user-dev", "git status");
        var approvedPending = services.commandService().submit("demo", authorized.id(), "user-dev", "sleep 5");
        var queued = services.commandService().decide("demo", approvedPending.taskId(), "user-reviewer", ApprovalDecision.ALLOW, "允许等待");

        assertThat(services.commandService().activeTasks("demo"))
                .extracting("taskId")
                .contains(queued.taskId())
                .doesNotContain(pending.taskId());
        assertThat(services.commandService().activeTasks("demo"))
                .extracting("status")
                .allSatisfy(status -> assertThat(status).isIn(ExecutionTaskStatus.QUEUED, ExecutionTaskStatus.RUNNING));
    }

    @Test
    void 已处理任务不能重复审批() {
        var services = services();
        var authorized = services.registry().authorize("demo", "当前项目", workspace.toString());
        var pending = services.commandService().submit("demo", authorized.id(), "user-dev", "git status");
        services.commandService().decide("demo", pending.taskId(), "user-reviewer", ApprovalDecision.DENY, "拒绝");

        assertThatThrownBy(() -> services.commandService().decide("demo", pending.taskId(), "user-reviewer", ApprovalDecision.ALLOW, "再次批准"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("任务已完成审批，不能重复处理");
    }

    @Test
    void 高风险命令即使批准也不会执行() {
        var services = services();
        var authorized = services.registry().authorize("demo", "当前项目", workspace.toString());
        var pending = services.commandService().submit("demo", authorized.id(), "user-ops", "ssh prod systemctl restart app");

        var rejected = services.commandService().decide("demo", pending.taskId(), "user-reviewer", ApprovalDecision.ALLOW, "不应执行远程命令");

        assertThat(rejected.status()).isEqualTo(ExecutionTaskStatus.DENIED);
        assertThat(rejected.approvalDecision()).isEqualTo(ApprovalDecision.DENY);
        assertThat(rejected.exitCode()).isNull();
        assertThat(rejected.stderrSummary()).contains("该命令不在第五阶段可批准执行范围内");
        assertThat(rejected.safetyRejectionReason()).contains("该命令不在第五阶段可批准执行范围内");
        assertThat(services.audit().records()).extracting("decision").containsExactly(ApprovalDecision.ASK, ApprovalDecision.DENY);
    }

    @Test
    void 工作区撤销后待审批任务不能被批准执行() {
        var services = services();
        var authorized = services.registry().authorize("demo", "当前项目", workspace.toString());
        var pending = services.commandService().submit("demo", authorized.id(), "user-dev", "git status");
        services.registry().revoke("demo", authorized.id());

        assertThatThrownBy(() -> services.commandService().decide("demo", pending.taskId(), "user-reviewer", ApprovalDecision.ALLOW, "批准"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("工作区未授权");
    }

    @Test
    void 工作区目录失效后批准不会留下假队列任务且不记录允许审计() throws Exception {
        var services = services();
        var authorized = services.registry().authorize("demo", "当前项目", workspace.toString());
        var pending = services.commandService().submit("demo", authorized.id(), "user-dev", "git status");
        Files.delete(workspace);

        assertThatThrownBy(() -> services.commandService().decide("demo", pending.taskId(), "user-reviewer", ApprovalDecision.ALLOW, "批准"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("工作区路径必须是已存在目录");

        assertThat(services.store().require("demo", pending.taskId()).status())
                .isIn(ExecutionTaskStatus.APPROVAL_PENDING, ExecutionTaskStatus.FAILED);
        assertThat(services.commandService().activeTasks("demo"))
                .extracting("taskId")
                .doesNotContain(pending.taskId());
        assertThat(services.audit().records()).extracting("decision").containsExactly(ApprovalDecision.ASK);
    }

    @Test
    void 并发审批同一待审批任务只能一次成功() throws Exception {
        var services = services();
        var authorized = services.registry().authorize("demo", "当前项目", workspace.toString());
        var pending = services.commandService().submit("demo", authorized.id(), "user-dev", "sleep 1");
        var ready = new CountDownLatch(2);
        var start = new CountDownLatch(1);
        var executor = Executors.newFixedThreadPool(2);

        Callable<Object> approve = () -> {
            ready.countDown();
            start.await();
            return services.commandService().decide("demo", pending.taskId(), "user-reviewer-a", ApprovalDecision.ALLOW, "批准");
        };
        Callable<Object> alsoApprove = () -> {
            ready.countDown();
            start.await();
            return services.commandService().decide("demo", pending.taskId(), "user-reviewer-b", ApprovalDecision.ALLOW, "重复批准");
        };

        var results = new ArrayList<Object>();
        try {
            var futures = List.of(executor.submit(approve), executor.submit(alsoApprove));
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

        var successfulDecisions = results.stream()
                .filter(ExecutionTask.class::isInstance)
                .map(ExecutionTask.class::cast)
                .toList();
        var duplicateFailures = results.stream()
                .filter(IllegalArgumentException.class::isInstance)
                .map(IllegalArgumentException.class::cast)
                .toList();
        var allowAuditCount = services.audit().records().stream()
                .filter(record -> record.decision() == ApprovalDecision.ALLOW)
                .count();

        assertThat(successfulDecisions).hasSize(1);
        assertThat(successfulDecisions.getFirst().status()).isEqualTo(ExecutionTaskStatus.QUEUED);
        assertThat(duplicateFailures).hasSize(1);
        assertThat(duplicateFailures.getFirst()).hasMessageContaining("任务已完成审批，不能重复处理");
        assertThat(allowAuditCount).isLessThanOrEqualTo(1);
    }

    @Test
    void 高风险包装命令即使批准也不会执行() {
        var services = services();
        var authorized = services.registry().authorize("demo", "当前项目", workspace.toString());

        for (var command : List.of("/usr/bin/ssh -V", "/bin/rm README.md", "env ssh -V", "bash -lc whoami")) {
            var pending = services.commandService().submit("demo", authorized.id(), "user-ops", command);

            var rejected = services.commandService().decide("demo", pending.taskId(), "user-reviewer", ApprovalDecision.ALLOW, "不应执行");

            assertThat(rejected.status()).isEqualTo(ExecutionTaskStatus.DENIED);
            assertThat(rejected.approvalDecision()).isEqualTo(ApprovalDecision.DENY);
            assertThat(rejected.exitCode()).isNull();
            assertThat(rejected.safetyRejectionReason()).contains("该命令不在第五阶段可批准执行范围内");
        }
    }

    @Test
    void 系统包装器命令即使批准也不会执行() {
        var services = services();
        var authorized = services.registry().authorize("demo", "当前项目", workspace.toString());

        for (var command : List.of("nohup /bin/rm README.md", "nice ssh prod uptime")) {
            var pending = services.commandService().submit("demo", authorized.id(), "user-ops", command);

            var rejected = services.commandService().decide("demo", pending.taskId(), "user-reviewer", ApprovalDecision.ALLOW, "不应执行");

            assertThat(rejected.status()).isEqualTo(ExecutionTaskStatus.DENIED);
            assertThat(rejected.approvalDecision()).isEqualTo(ApprovalDecision.DENY);
            assertThat(rejected.exitCode()).isNull();
            assertThat(rejected.safetyRejectionReason()).contains("该命令不在第五阶段可批准执行范围内");
        }
    }

    @Test
    void 补充高风险命令和绝对路径逃逸即使批准也不会执行() {
        var services = services();
        var authorized = services.registry().authorize("demo", "当前项目", workspace.toString());

        for (var command : List.of("rollback production", "rmdir build", "/tmp/custom-tool status")) {
            var pending = services.commandService().submit("demo", authorized.id(), "user-ops", command);

            var rejected = services.commandService().decide("demo", pending.taskId(), "user-reviewer", ApprovalDecision.ALLOW, "不应执行");

            assertThat(rejected.status()).isEqualTo(ExecutionTaskStatus.DENIED);
            assertThat(rejected.approvalDecision()).isEqualTo(ApprovalDecision.DENY);
            assertThat(rejected.exitCode()).isNull();
            assertThat(rejected.safetyRejectionReason()).contains("该命令不在第五阶段可批准执行范围内");
        }
    }

    @Test
    void 危险子命令即使批准也不会执行() {
        var services = services();
        var authorized = services.registry().authorize("demo", "当前项目", workspace.toString());

        for (var command : List.of("git clean -fdx", "find . -delete", "mvn deploy")) {
            var pending = services.commandService().submit("demo", authorized.id(), "user-ops", command);

            var rejected = services.commandService().decide("demo", pending.taskId(), "user-reviewer", ApprovalDecision.ALLOW, "不应执行");

            assertThat(rejected.status()).isEqualTo(ExecutionTaskStatus.DENIED);
            assertThat(rejected.approvalDecision()).isEqualTo(ApprovalDecision.DENY);
            assertThat(rejected.exitCode()).isNull();
            assertThat(rejected.safetyRejectionReason()).contains("该命令不在第五阶段可批准执行范围内");
        }
    }

    @Test
    void 凭证参数和删除型Git子命令即使批准也不会执行() {
        var services = services();
        var authorized = services.registry().authorize("demo", "当前项目", workspace.toString());

        for (var command : List.of("git status --key-file /tmp/prod.key", "git rm README.md")) {
            var pending = services.commandService().submit("demo", authorized.id(), "user-ops", command);

            var rejected = services.commandService().decide("demo", pending.taskId(), "user-reviewer", ApprovalDecision.ALLOW, "不应执行");

            assertThat(rejected.status()).isEqualTo(ExecutionTaskStatus.DENIED);
            assertThat(rejected.approvalDecision()).isEqualTo(ApprovalDecision.DENY);
            assertThat(rejected.exitCode()).isNull();
            assertThat(rejected.safetyRejectionReason()).contains("该命令不在第五阶段可批准执行范围内");
        }
    }

    @Test
    void 拒绝审批审计记录使用真实工作区路径() {
        var services = services();
        var authorized = services.registry().authorize("demo", "当前项目", workspace.toString());
        var pending = services.commandService().submit("demo", authorized.id(), "user-dev", "git status");

        services.commandService().decide("demo", pending.taskId(), "user-reviewer", ApprovalDecision.DENY, "拒绝");

        assertThat(services.audit().records().getLast().workspacePath()).isEqualTo(authorized.rootPath());
    }

    private TestServices services() {
        var registry = new WorkspaceRegistry();
        var audit = new AuditService();
        var store = new LocalTaskStore();
        var queue = new LocalTaskQueueService(registry, store, new ProjectEventBus());
        queues.add(queue);
        var commandService = new LocalCommandService(registry, new ApprovalPolicy(), audit, store, queue);
        return new TestServices(registry, audit, store, queue, commandService);
    }

    private void writeNpmTestScript() throws Exception {
        Files.writeString(workspace.resolve("safe-command.js"), "console.log('queue-ok');\n");
        Files.writeString(workspace.resolve("package.json"), "{\"scripts\":{\"test\":\"node safe-command.js\"}}");
    }

    private record TestServices(
            WorkspaceRegistry registry,
            AuditService audit,
            LocalTaskStore store,
            LocalTaskQueueService queue,
            LocalCommandService commandService
    ) {
    }
}
