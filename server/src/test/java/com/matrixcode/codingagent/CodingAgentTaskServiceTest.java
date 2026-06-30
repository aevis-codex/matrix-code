package com.matrixcode.codingagent;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.matrixcode.agentruntime.application.AgentRuntimeRepository;
import com.matrixcode.agentruntime.application.AgentRuntimeService;
import com.matrixcode.agentruntime.domain.AgentRunEventRecord;
import com.matrixcode.agentruntime.domain.AgentRunRecord;
import com.matrixcode.agentruntime.domain.AgentRunStatus;
import com.matrixcode.approval.application.ApprovalPolicy;
import com.matrixcode.approval.application.AuditService;
import com.matrixcode.codingagent.application.CodingAgentExecutionService;
import com.matrixcode.codingagent.application.CodingAgentHandoffService;
import com.matrixcode.codingagent.application.CodingAgentPatchService;
import com.matrixcode.codingagent.application.CodingAgentTaskService;
import com.matrixcode.codingagent.domain.CodingAgentExecutionStatus;
import com.matrixcode.codingagent.domain.CodingAgentStep;
import com.matrixcode.codingagent.domain.CodingAgentStepType;
import com.matrixcode.codingagent.domain.CodingAgentTaskStatus;
import com.matrixcode.document.application.DocumentService;
import com.matrixcode.document.domain.DocumentType;
import com.matrixcode.localexecution.application.LocalCommandService;
import com.matrixcode.localexecution.application.LocalFileService;
import com.matrixcode.localexecution.application.LocalGitDiffService;
import com.matrixcode.localexecution.application.PathGuard;
import com.matrixcode.localexecution.application.LocalTaskQueueService;
import com.matrixcode.localexecution.application.LocalTaskStore;
import com.matrixcode.localexecution.application.WorkspaceRegistry;
import com.matrixcode.localexecution.domain.ExecutionTaskStatus;
import com.matrixcode.modelgateway.domain.ModelRole;
import com.matrixcode.realtime.application.ProjectEventBus;
import com.matrixcode.roleagent.application.RoleAgentConfigService;
import com.matrixcode.workbench.application.InMemoryWorkbenchStateStore;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.nio.file.Path;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CodingAgentTaskServiceTest {

    @TempDir
    Path tempWorkspace;

    private final List<LocalTaskQueueService> queues = new ArrayList<>();

    @AfterEach
    void shutdownQueues() {
        queues.forEach(LocalTaskQueueService::shutdown);
        queues.clear();
    }

    @Test
    void 开发角色任务会生成可审查编码步骤() {
        var service = new CodingAgentTaskService(new RoleAgentConfigService(new InMemoryWorkbenchStateStore()));

        var task = service.plan("demo", ModelRole.DEVELOPER, "实现登录接口", "workspace-main");

        assertThat(task.status()).isEqualTo(CodingAgentTaskStatus.PLANNED);
        assertThat(task.projectId()).isEqualTo("demo");
        assertThat(task.role()).isEqualTo(ModelRole.DEVELOPER);
        assertThat(task.goal()).isEqualTo("实现登录接口");
        assertThat(task.workspaceId()).isEqualTo("workspace-main");
        assertThat(task.steps()).extracting(CodingAgentStep::type)
                .containsExactly(
                        CodingAgentStepType.CONTEXT_RECALL,
                        CodingAgentStepType.PLAN_REVIEW,
                        CodingAgentStepType.FILE_REVIEW,
                        CodingAgentStepType.CODE_EDIT,
                        CodingAgentStepType.TEST_COMMAND,
                        CodingAgentStepType.DIFF_REVIEW,
                        CodingAgentStepType.HANDOFF
                );
        assertThat(task.steps()).filteredOn(step -> step.type() == CodingAgentStepType.TEST_COMMAND)
                .singleElement()
                .satisfies(step -> {
                    assertThat(step.requiresApproval()).isTrue();
                    assertThat(step.tool()).isEqualTo("local-execution.commands");
                });
    }

    @Test
    void 创建编码任务会记录Agent运行主记录和规划事件() {
        var repository = new RecordingAgentRuntimeRepository();
        var service = new CodingAgentTaskService(
                new RoleAgentConfigService(new InMemoryWorkbenchStateStore()),
                runtimeService(repository)
        );

        var task = service.plan("demo", ModelRole.DEVELOPER, "实现登录接口", "workspace-main", "user-dev");

        assertThat(repository.savedRuns).singleElement().satisfies(run -> {
            assertThat(run.id()).isEqualTo(task.taskId());
            assertThat(run.status()).isEqualTo(AgentRunStatus.QUEUED);
            assertThat(run.actorUserId()).isEqualTo("user-dev");
            assertThat(run.agentKind()).isEqualTo("coding");
            assertThat(run.providerId()).isEqualTo("local-deterministic");
            assertThat(run.modelName()).isEqualTo("matrixcode-local-developer");
        });
        assertThat(repository.events).singleElement().satisfies(event -> {
            assertThat(event.runId()).isEqualTo(task.taskId());
            assertThat(event.eventType()).isEqualTo("TASK_PLANNED");
            assertThat(event.eventPayload()).contains("\"workspaceId\":\"workspace-main\"");
        });
    }

    @Test
    void 禁用角色不能创建编码任务() {
        var roleAgentConfigService = new RoleAgentConfigService(new InMemoryWorkbenchStateStore());
        roleAgentConfigService.update("demo", ModelRole.DEVELOPER, new com.matrixcode.roleagent.application.RoleAgentConfigCommand(
                "开发智能体",
                "coding",
                "local-deterministic",
                "matrixcode-local-developer",
                "tools-v1",
                "你是开发智能体",
                "{{instruction}}",
                "#2563EB",
                "Inter",
                14,
                2,
                false,
                "stable-platform-prefix-v1",
                "role-prompt-and-dynamic-context"
        ));
        var service = new CodingAgentTaskService(roleAgentConfigService);

        org.assertj.core.api.Assertions.assertThatThrownBy(
                        () -> service.plan("demo", ModelRole.DEVELOPER, "实现登录接口", "workspace-main"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("角色智能体未启用");
    }

    @Test
    void 执行准备会绑定授权工作区提交测试命令并采集diff() {
        var harness = executionHarness();
        var workspace = harness.workspaces().authorize("demo", "当前项目", tempWorkspace.toString());

        var plan = harness.executionService().prepare(
                "demo",
                ModelRole.DEVELOPER,
                "实现登录接口",
                workspace.id(),
                "user-dev",
                "git status"
        );

        assertThat(plan.task().workspaceId()).isEqualTo(workspace.id());
        assertThat(plan.testCommandTask().status()).isEqualTo(ExecutionTaskStatus.APPROVAL_PENDING);
        assertThat(plan.gitDiffSummary().workspaceId()).isEqualTo(workspace.id());
        assertThat(plan.executionSteps()).filteredOn(step -> step.type() == CodingAgentStepType.CODE_EDIT)
                .singleElement()
                .satisfies(step -> {
                    assertThat(step.status()).isEqualTo(CodingAgentExecutionStatus.APPROVAL_REQUIRED);
                    assertThat(step.localTool()).isEqualTo("local-execution.files.write");
                });
        assertThat(plan.executionSteps()).filteredOn(step -> step.type() == CodingAgentStepType.TEST_COMMAND)
                .singleElement()
                .satisfies(step -> {
                    assertThat(step.status()).isEqualTo(CodingAgentExecutionStatus.APPROVAL_REQUIRED);
                    assertThat(step.referenceId()).isEqualTo(plan.testCommandTask().taskId());
                });
    }

    @Test
    void 执行准备会把同一个Agent运行更新为运行中并记录测试任务事件() {
        var repository = new RecordingAgentRuntimeRepository();
        var harness = executionHarness(repository);
        var workspace = harness.workspaces().authorize("demo", "当前项目", tempWorkspace.toString());

        var plan = harness.executionService().prepare(
                "demo",
                ModelRole.DEVELOPER,
                "实现登录接口",
                workspace.id(),
                "user-dev",
                "git status"
        );

        assertThat(repository.savedRuns).hasSize(2);
        assertThat(repository.savedRuns.get(0).id()).isEqualTo(plan.task().taskId());
        assertThat(repository.savedRuns.get(0).status()).isEqualTo(AgentRunStatus.QUEUED);
        assertThat(repository.savedRuns.get(1).id()).isEqualTo(plan.task().taskId());
        assertThat(repository.savedRuns.get(1).status()).isEqualTo(AgentRunStatus.RUNNING);
        assertThat(repository.events)
                .extracting(AgentRunEventRecord::eventType)
                .containsExactly("TASK_PLANNED", "RUN_STARTED", "EXECUTION_PREPARED", "TOOL_TRACE", "TOOL_TRACE");
        assertThat(repository.events.get(1).eventTitle()).isEqualTo("运行开始");
        assertThat(repository.events.get(1).eventPayload()).contains("\"summary\":\"执行准备已生成\"");
        assertThat(repository.events.get(2).eventPayload()).contains(plan.testCommandTask().taskId());
        assertThat(repository.events.get(3).eventPayload()).contains("\"toolName\":\"local-execution.commands\"");
        assertThat(repository.events.get(3).eventPayload()).contains("\"referenceId\":\"" + plan.testCommandTask().taskId() + "\"");
        assertThat(repository.events.get(4).eventPayload()).contains("\"toolName\":\"local-execution.git-diff\"");
        assertThat(repository.events.get(4).eventPayload()).contains("\"changedFileCount\":0");
    }

    @Test
    void 未授权工作区不能准备执行() {
        var harness = executionHarness();

        assertThatThrownBy(() -> harness.executionService().prepare(
                "demo",
                ModelRole.DEVELOPER,
                "实现登录接口",
                "missing-workspace",
                "user-dev",
                "git status"
        ))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("工作区未授权");
    }

    @Test
    void 未确认审批不能应用编码智能体patch() throws Exception {
        var harness = patchHarness();
        var workspace = harness.workspaces().authorize("demo", "当前项目", tempWorkspace.toString());
        Files.createDirectories(tempWorkspace.resolve("src"));
        Files.writeString(tempWorkspace.resolve("src/App.java"), "class App {}\n");

        assertThatThrownBy(() -> harness.patchService().apply(
                "demo",
                ModelRole.DEVELOPER,
                workspace.id(),
                "user-dev",
                "src/App.java",
                "class App {}\n",
                "class App { void run() {} }\n",
                "补充入口",
                false
        ))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("应用 patch 前必须确认审批");
    }

    @Test
    void 文件内容变化时拒绝应用编码智能体patch() throws Exception {
        var harness = patchHarness();
        var workspace = harness.workspaces().authorize("demo", "当前项目", tempWorkspace.toString());
        Files.createDirectories(tempWorkspace.resolve("src"));
        Files.writeString(tempWorkspace.resolve("src/App.java"), "class App { int changed; }\n");

        assertThatThrownBy(() -> harness.patchService().apply(
                "demo",
                ModelRole.DEVELOPER,
                workspace.id(),
                "user-dev",
                "src/App.java",
                "class App {}\n",
                "class App { void run() {} }\n",
                "补充入口",
                true
        ))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("文件内容已变化，请重新生成 patch");
    }

    @Test
    void 确认审批后可以应用小型文本patch并返回diff摘要() throws Exception {
        var harness = patchHarness();
        var workspace = harness.workspaces().authorize("demo", "当前项目", tempWorkspace.toString());
        Files.createDirectories(tempWorkspace.resolve("src"));
        Files.writeString(tempWorkspace.resolve("src/App.java"), "class App {}\n");

        var result = harness.patchService().apply(
                "demo",
                ModelRole.DEVELOPER,
                workspace.id(),
                "user-dev",
                "src/App.java",
                "class App {}\n",
                "class App { void run() {} }\n",
                "补充入口",
                true
        );

        assertThat(Files.readString(tempWorkspace.resolve("src/App.java"))).isEqualTo("class App { void run() {} }\n");
        assertThat(result.relativePath()).isEqualTo("src/App.java");
        assertThat(result.runId()).isNotBlank();
        assertThat(result.bytesWritten()).isGreaterThan(0);
        assertThat(result.gitDiffSummary().workspaceId()).isEqualTo(workspace.id());
    }

    @Test
    void 应用Patch成功后会记录Agent运行和Patch事件() throws Exception {
        var repository = new RecordingAgentRuntimeRepository();
        var harness = patchHarness(repository);
        var workspace = harness.workspaces().authorize("demo", "当前项目", tempWorkspace.toString());
        Files.createDirectories(tempWorkspace.resolve("src"));
        Files.writeString(tempWorkspace.resolve("src/App.java"), "class App {}\n");

        var result = harness.patchService().apply(
                "demo",
                ModelRole.DEVELOPER,
                workspace.id(),
                "user-dev",
                "src/App.java",
                "class App {}\n",
                "class App { void run() {} }\n",
                "补充入口",
                true
        );

        assertThat(repository.savedRuns).singleElement().satisfies(run -> {
            assertThat(run.id()).isEqualTo(result.runId());
            assertThat(run.status()).isEqualTo(AgentRunStatus.SUCCEEDED);
            assertThat(run.summary()).isEqualTo("补充入口");
        });
        assertThat(repository.events)
                .extracting(AgentRunEventRecord::eventType)
                .containsExactly("RUN_SUCCEEDED", "PATCH_APPLIED", "TOOL_TRACE");
        assertThat(repository.events.get(0).eventTitle()).isEqualTo("运行成功");
        assertThat(repository.events.get(0).eventPayload()).contains("\"summary\":\"补充入口\"");
        assertThat(repository.events.get(1).eventPayload()).contains("\"relativePath\":\"src/App.java\"");
        assertThat(repository.events.get(2).runId()).isEqualTo(result.runId());
        assertThat(repository.events.get(2).eventPayload()).contains("\"toolName\":\"local-execution.files.write\"");
        assertThat(repository.events.get(2).eventPayload()).contains("\"relativePath\":\"src/App.java\"");
    }

    @Test
    void 可以记录编码智能体交付回溯文档并发布事件() {
        var harness = handoffHarness();

        var document = harness.handoffService().record(
                "demo",
                ModelRole.DEVELOPER,
                "workspace-1",
                "user-dev",
                "实现登录接口",
                "src/App.java",
                "补充入口",
                "1 file changed",
                "task-1",
                "SUCCESS",
                "mvn test",
                "测试通过，可以交付"
        );

        assertThat(document.type()).isEqualTo(DocumentType.CODING_AGENT_HANDOFF);
        assertThat(document.title()).isEqualTo("编码智能体交付回溯");
        assertThat(document.content())
                .contains("执行目标：实现登录接口")
                .contains("变更文件：src/App.java")
                .contains("Patch 摘要：补充入口")
                .contains("Diff 摘要：1 file changed")
                .contains("测试任务：task-1")
                .contains("测试命令：mvn test")
                .contains("测试状态：SUCCESS")
                .contains("交付结论：测试通过，可以交付");
        assertThat(harness.events().recent("demo")).singleElement().satisfies(event -> {
            assertThat(event.type()).isEqualTo("CODING_AGENT_HANDOFF_RECORDED");
            assertThat(event.message()).isEqualTo("开发记录了编码智能体交付回溯");
            assertThat(event.sourceRole()).isEqualTo("DEVELOPER");
            assertThat(event.sourceId()).isEqualTo("user-dev");
        });
    }

    @Test
    void 交接回溯成功后会追加Agent交付事件() {
        var repository = new RecordingAgentRuntimeRepository();
        var harness = handoffHarness(repository);

        harness.handoffService().record(
                "demo",
                ModelRole.DEVELOPER,
                "workspace-1",
                "user-dev",
                "实现登录接口",
                "src/App.java",
                "补充入口",
                "1 file changed",
                "task-1",
                "SUCCESS",
                "mvn test",
                "测试通过，可以交付",
                "run-1"
        );

        assertThat(repository.savedRuns).singleElement().satisfies(run -> {
            assertThat(run.id()).isEqualTo("run-1");
            assertThat(run.status()).isEqualTo(AgentRunStatus.SUCCEEDED);
        });
        assertThat(repository.events)
                .extracting(AgentRunEventRecord::eventType)
                .containsExactly("RUN_SUCCEEDED", "HANDOFF_RECORDED", "TOOL_TRACE");
        assertThat(repository.events.get(0).eventTitle()).isEqualTo("运行成功");
        assertThat(repository.events.get(0).eventPayload()).contains("\"summary\":\"编码智能体交接回溯已记录\"");
        assertThat(repository.events.get(1).eventPayload()).contains("\"testTaskStatus\":\"SUCCESS\"");
        assertThat(repository.events.get(1).eventPayload()).contains("\"actorUserId\":\"user-dev\"");
        assertThat(repository.events.get(2).runId()).isEqualTo("run-1");
        assertThat(repository.events.get(2).eventPayload()).contains("\"toolName\":\"document.center\"");
        assertThat(repository.events.get(2).eventPayload()).contains("\"documentTitle\":\"编码智能体交付回溯\"");
        assertThat(repository.events.get(2).eventPayload()).contains("\"actorUserId\":\"user-dev\"");
    }

    @Test
    void 编码智能体交付回溯缺少核心字段会被拒绝() {
        var harness = handoffHarness();

        assertThatThrownBy(() -> harness.handoffService().record(
                "demo",
                ModelRole.DEVELOPER,
                "workspace-1",
                "user-dev",
                " ",
                "src/App.java",
                "补充入口",
                "1 file changed",
                "task-1",
                "SUCCESS",
                "mvn test",
                "测试通过，可以交付"
        ))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("执行目标不能为空");
    }

    private ExecutionHarness executionHarness() {
        return executionHarness(new RecordingAgentRuntimeRepository());
    }

    private ExecutionHarness executionHarness(RecordingAgentRuntimeRepository repository) {
        var workspaces = new WorkspaceRegistry();
        var roleAgentConfigService = new RoleAgentConfigService(new InMemoryWorkbenchStateStore());
        var runtimeService = runtimeService(repository);
        var taskService = new CodingAgentTaskService(roleAgentConfigService, runtimeService);
        var audit = new AuditService();
        var taskStore = new LocalTaskStore();
        var queue = new LocalTaskQueueService(workspaces, taskStore, new ProjectEventBus());
        queues.add(queue);
        var commandService = new LocalCommandService(workspaces, new ApprovalPolicy(), audit, taskStore, queue);
        var gitDiffService = new LocalGitDiffService(workspaces);
        var executionService = new CodingAgentExecutionService(
                taskService,
                roleAgentConfigService,
                runtimeService,
                workspaces,
                commandService,
                gitDiffService
        );
        return new ExecutionHarness(workspaces, executionService);
    }

    private PatchHarness patchHarness() {
        return patchHarness(new RecordingAgentRuntimeRepository());
    }

    private PatchHarness patchHarness(RecordingAgentRuntimeRepository repository) {
        var workspaces = new WorkspaceRegistry();
        var fileService = new LocalFileService(workspaces, new PathGuard());
        var gitDiffService = new LocalGitDiffService(workspaces);
        var roleAgentConfigService = new RoleAgentConfigService(new InMemoryWorkbenchStateStore());
        return new PatchHarness(workspaces, new CodingAgentPatchService(
                fileService,
                gitDiffService,
                roleAgentConfigService,
                runtimeService(repository)
        ));
    }

    private HandoffHarness handoffHarness() {
        return handoffHarness(new RecordingAgentRuntimeRepository());
    }

    private HandoffHarness handoffHarness(RecordingAgentRuntimeRepository repository) {
        var store = new InMemoryWorkbenchStateStore();
        var documentService = new DocumentService(store);
        var events = new ProjectEventBus(store);
        var roleAgentConfigService = new RoleAgentConfigService(store);
        return new HandoffHarness(new CodingAgentHandoffService(
                documentService,
                events,
                roleAgentConfigService,
                runtimeService(repository)
        ), events);
    }

    private AgentRuntimeService runtimeService(RecordingAgentRuntimeRepository repository) {
        return new AgentRuntimeService(
                Optional.of(repository),
                new ObjectMapper(),
                Clock.fixed(Instant.parse("2026-06-25T12:00:00Z"), ZoneOffset.UTC)
        );
    }

    private record ExecutionHarness(
            WorkspaceRegistry workspaces,
            CodingAgentExecutionService executionService
    ) {
    }

    private record PatchHarness(
            WorkspaceRegistry workspaces,
            CodingAgentPatchService patchService
    ) {
    }

    private record HandoffHarness(
            CodingAgentHandoffService handoffService,
            ProjectEventBus events
    ) {
    }

    private static final class RecordingAgentRuntimeRepository implements AgentRuntimeRepository {

        private final List<AgentRunRecord> savedRuns = new ArrayList<>();
        private final List<AgentRunEventRecord> events = new ArrayList<>();

        @Override
        public void saveRun(AgentRunRecord run) {
            savedRuns.add(run);
        }

        @Override
        public void appendEvent(AgentRunEventRecord event) {
            events.add(event);
        }

        @Override
        public Optional<AgentRunRecord> findRun(String runId) {
            for (var index = savedRuns.size() - 1; index >= 0; index--) {
                var run = savedRuns.get(index);
                if (run.id().equals(runId)) {
                    return Optional.of(run);
                }
            }
            return Optional.empty();
        }

        @Override
        public List<AgentRunRecord> recentRuns(String projectId, int limit) {
            return savedRuns.stream()
                    .filter(run -> run.projectId().equals(projectId))
                    .limit(limit)
                    .toList();
        }

        @Override
        public List<AgentRunEventRecord> eventsForRun(String runId) {
            return events.stream()
                    .filter(event -> event.runId().equals(runId))
                    .toList();
        }
    }
}
