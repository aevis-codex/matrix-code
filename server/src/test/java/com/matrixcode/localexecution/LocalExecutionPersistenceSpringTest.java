package com.matrixcode.localexecution;

import com.matrixcode.MatrixCodeServerApplication;
import com.matrixcode.approval.domain.ApprovalDecision;
import com.matrixcode.localexecution.application.LocalCommandService;
import com.matrixcode.localexecution.application.LocalExecutionSummaryService;
import com.matrixcode.localexecution.application.LocalTaskStore;
import com.matrixcode.localexecution.application.WorkspaceRegistry;
import com.matrixcode.localexecution.domain.ExecutionTaskStatus;
import com.matrixcode.localexecution.domain.LocalTaskLogStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class LocalExecutionPersistenceSpringTest {

    @TempDir
    Path tempDir;

    @Test
    void Spring上下文重启后恢复本地执行状态并可继续处理审批() throws Exception {
        var storagePath = tempDir.resolve("state/local-execution.json");
        var notificationStoragePath = tempDir.resolve("state/runtime-notifications.json");
        var workspaceRoot = tempDir.resolve("workspace");
        Files.createDirectories(workspaceRoot);
        Files.writeString(workspaceRoot.resolve("README.md"), "MatrixCode 本地执行持久化验证");

        String workspaceId;
        String taskId;
        try (var context = startContext(storagePath, notificationStoragePath)) {
            var workspaceRegistry = context.getBean(WorkspaceRegistry.class);
            var commandService = context.getBean(LocalCommandService.class);
            var taskStore = context.getBean(LocalTaskStore.class);

            workspaceId = workspaceRegistry.authorize("demo", "持久化工作区", workspaceRoot.toString()).id();
            var task = commandService.submit("demo", workspaceId, "user-dev", "git status");
            taskId = task.taskId();
            taskStore.appendLog("demo", taskId, LocalTaskLogStream.SYSTEM, "重启前保留的系统日志");

            assertThat(task.status()).isEqualTo(ExecutionTaskStatus.APPROVAL_PENDING);
            assertThat(task.approvalDecision()).isEqualTo(ApprovalDecision.ASK);
        }

        try (var context = startContext(storagePath, notificationStoragePath)) {
            var summaryService = context.getBean(LocalExecutionSummaryService.class);
            var commandService = context.getBean(LocalCommandService.class);

            var restored = summaryService.summary("demo");
            assertThat(restored.workspaces()).extracting("id").contains(workspaceId);
            assertThat(restored.recentTasks()).anySatisfy(task -> {
                assertThat(task.taskId()).isEqualTo(taskId);
                assertThat(task.status()).isEqualTo(ExecutionTaskStatus.APPROVAL_PENDING);
            });
            assertThat(restored.recentTaskLogs()).anySatisfy(log ->
                    assertThat(log.content()).contains("重启前保留")
            );
            assertThat(restored.recentAuditRecords()).anySatisfy(record -> {
                assertThat(record.taskId()).isEqualTo(taskId);
                assertThat(record.decision()).isEqualTo(ApprovalDecision.ASK);
            });

            var denied = commandService.decide("demo", taskId, "user-reviewer", ApprovalDecision.DENY, "重启后拒绝");

            assertThat(denied.status()).isEqualTo(ExecutionTaskStatus.DENIED);
            assertThat(denied.approverId()).isEqualTo("user-reviewer");
            var afterDecision = summaryService.summary("demo");
            assertThat(afterDecision.recentAuditRecords()).anySatisfy(record -> {
                assertThat(record.taskId()).isEqualTo(taskId);
                assertThat(record.decision()).isEqualTo(ApprovalDecision.DENY);
            });
        }

        assertThat(Files.exists(storagePath)).isTrue();
    }

    private ConfigurableApplicationContext startContext(Path storagePath, Path notificationStoragePath) {
        return new SpringApplicationBuilder(MatrixCodeServerApplication.class)
                .web(WebApplicationType.NONE)
                .properties(Map.of(
                        "matrixcode.local-execution.storage-path", storagePath.toString(),
                        "matrixcode.runtime-notifications.storage-path", notificationStoragePath.toString(),
                        "spring.main.banner-mode", "off"
                ))
                .run();
    }
}
