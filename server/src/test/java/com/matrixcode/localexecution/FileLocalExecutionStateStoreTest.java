package com.matrixcode.localexecution;

import com.fasterxml.jackson.databind.json.JsonMapper;
import com.matrixcode.approval.domain.ApprovalDecision;
import com.matrixcode.approval.domain.AuditRecord;
import com.matrixcode.localexecution.application.FileLocalExecutionStateStore;
import com.matrixcode.localexecution.application.LocalExecutionStorageProperties;
import com.matrixcode.localexecution.domain.ExecutionTask;
import com.matrixcode.localexecution.domain.ExecutionTaskStatus;
import com.matrixcode.localexecution.domain.LocalTaskLog;
import com.matrixcode.localexecution.domain.LocalTaskLogStream;
import com.matrixcode.localexecution.domain.WorkspaceAuthorization;
import com.matrixcode.localexecution.domain.WorkspaceStatus;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class FileLocalExecutionStateStoreTest {

    @TempDir
    Path tempDir;

    @Test
    void 文件不存在时加载空快照() {
        var store = store(tempDir.resolve("missing/local-execution.json"));

        var snapshot = store.load();

        assertThat(snapshot.version()).isEqualTo(1);
        assertThat(snapshot.workspaces()).isEmpty();
        assertThat(snapshot.tasks()).isEmpty();
        assertThat(snapshot.taskLogs()).isEmpty();
        assertThat(snapshot.auditRecords()).isEmpty();
    }

    @Test
    void 分区更新会保留其他分区() {
        var path = tempDir.resolve("state/local-execution.json");
        var store = store(path);

        store.saveWorkspaces(List.of(workspace()));
        store.saveTasks(
                Map.of("demo", List.of(task("task-1", ExecutionTaskStatus.SUCCESS))),
                Map.of("demo", Map.of("task-1", List.of(log("task-1"))))
        );
        store.saveAuditRecords(List.of(audit("task-1")));

        var loaded = store(path).load();
        assertThat(loaded.workspaces()).hasSize(1);
        assertThat(loaded.tasks().get("demo")).hasSize(1);
        assertThat(loaded.taskLogs().get("demo").get("task-1")).hasSize(1);
        assertThat(loaded.auditRecords()).hasSize(1);
    }

    @Test
    void 文件损坏时加载空快照且保留原文件() throws Exception {
        var path = tempDir.resolve("state/local-execution.json");
        Files.createDirectories(path.getParent());
        Files.writeString(path, "{broken");

        var loaded = store(path).load();

        assertThat(loaded.workspaces()).isEmpty();
        assertThat(Files.readString(path)).isEqualTo("{broken");
    }

    private FileLocalExecutionStateStore store(Path path) {
        var properties = new LocalExecutionStorageProperties();
        properties.setStoragePath(path);
        var mapper = JsonMapper.builder().findAndAddModules().build();
        return new FileLocalExecutionStateStore(mapper, properties);
    }

    private WorkspaceAuthorization workspace() {
        return new WorkspaceAuthorization(
                "workspace-1",
                "demo",
                "当前项目",
                tempDir.toString(),
                WorkspaceStatus.AUTHORIZED,
                Instant.parse("2026-06-25T08:00:00Z"),
                Instant.parse("2026-06-25T08:00:00Z")
        );
    }

    private ExecutionTask task(String taskId, ExecutionTaskStatus status) {
        return new ExecutionTask(
                taskId,
                "demo",
                "workspace-1",
                "user-ops",
                "SHELL",
                "git status",
                ApprovalDecision.ASK,
                status,
                null,
                "",
                "",
                0,
                Instant.parse("2026-06-25T08:00:00Z")
        );
    }

    private LocalTaskLog log(String taskId) {
        return new LocalTaskLog(
                "log-1",
                "demo",
                taskId,
                LocalTaskLogStream.SYSTEM,
                "任务运行完成，退出码：0",
                Instant.parse("2026-06-25T08:01:00Z")
        );
    }

    private AuditRecord audit(String taskId) {
        return new AuditRecord(
                "audit-1",
                taskId,
                "user-ops",
                "SHELL",
                tempDir.toString(),
                "git status",
                ApprovalDecision.ASK,
                Instant.parse("2026-06-25T08:00:00Z")
        );
    }
}
