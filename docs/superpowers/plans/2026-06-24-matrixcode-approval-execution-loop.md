# MatrixCode 第五阶段人工审批执行闭环实现计划

> **面向 AI 代理的工作者：** 必需子技能：使用 superpowers:subagent-driven-development（推荐）或 superpowers:executing-plans 逐任务实现此计划。步骤使用复选框（`- [ ]`）语法来跟踪进度。

**目标：** 构建第五阶段人工审批执行闭环，让右侧“本地执行代理”卡片可以批准或拒绝单个待审批本地命令，并保证同一任务只会被处理一次。

**架构：** 服务端扩展 `localexecution` 模块，在 `LocalCommandService` 内维护待审批任务的决策和安全复核，控制器新增任务审批接口。桌面端扩展 API 客户端和 `InspectorPanel`，在现有三栏工作台右侧内联展示审批按钮，并由 `App.tsx` 负责调用审批接口后刷新工作台。

**技术栈：** Java 21、Spring Boot 3.5.15、JUnit 5、MockMvc、React 19.2.7、TypeScript 6.0.3、Vitest 4.1.9、本地 Maven `/Users/Masons/Ai/Maven` 和仓库 `/Users/Masons/Ai/Maven_Ai_Store`。

---

## 范围检查

本计划只实现“单个待审批本地命令”的人工批准或拒绝闭环。第五阶段不做真实 SSH、远程部署、多人审批、登录鉴权、批量审批、任务取消、流式日志和数据库持久化。

人工批准仍由服务端做最终安全判断。SSH、部署、回滚、凭证、破坏性命令、Shell 管道、重定向、后台执行、子 Shell 和环境变量赋值命令即使被用户点击批准，也不会启动进程。

## 文件结构

```text
server/src/main/java/com/matrixcode/localexecution/
├── api/
│   └── LocalExecutionController.java
├── application/
│   └── LocalCommandService.java
└── domain/
    └── ExecutionTask.java

server/src/test/java/com/matrixcode/localexecution/
├── LocalCommandServiceTest.java
└── LocalExecutionControllerTest.java

desktop/src/
├── api/
│   ├── client.ts
│   └── client.test.ts
├── components/
│   └── InspectorPanel.tsx
├── test/
│   └── App.test.tsx
├── App.tsx
└── App.css

docs/development/local-run.md
docs/superpowers/plans/2026-06-24-matrixcode-approval-execution-loop.md
```

---

### 任务 1：实现服务端命令审批核心

**文件：**

- 修改：`server/src/main/java/com/matrixcode/localexecution/domain/ExecutionTask.java`
- 修改：`server/src/main/java/com/matrixcode/localexecution/application/LocalCommandService.java`
- 修改：`server/src/test/java/com/matrixcode/localexecution/LocalCommandServiceTest.java`

- [x] **步骤 1：编写命令审批失败测试**

修改 `LocalCommandServiceTest`，新增以下测试。测试直接调用服务层，先验证核心状态转换和安全边界：

```java
@Test
void 待审批命令可以被拒绝且不会执行() {
    var registry = new WorkspaceRegistry();
    var audit = new AuditService();
    var service = new LocalCommandService(registry, new ApprovalPolicy(), audit);
    var authorized = registry.authorize("demo", "当前项目", workspace.toString());
    var pending = service.submit("demo", authorized.id(), "user-dev", "git status");

    var denied = service.decide("demo", pending.taskId(), "user-reviewer", ApprovalDecision.DENY, "风险太高");

    assertThat(denied.status()).isEqualTo(ExecutionTaskStatus.DENIED);
    assertThat(denied.approvalDecision()).isEqualTo(ApprovalDecision.DENY);
    assertThat(denied.exitCode()).isNull();
    assertThat(denied.approverId()).isEqualTo("user-reviewer");
    assertThat(denied.approvalNote()).isEqualTo("风险太高");
    assertThat(service.recentTasks("demo").getFirst().taskId()).isEqualTo(pending.taskId());
    assertThat(service.recentTasks("demo").getFirst().status()).isEqualTo(ExecutionTaskStatus.DENIED);
    assertThat(audit.records()).extracting("decision").containsExactly(ApprovalDecision.ASK, ApprovalDecision.DENY);
}

@Test
void 待审批本地命令批准后执行原始命令() {
    var registry = new WorkspaceRegistry();
    var audit = new AuditService();
    var service = new LocalCommandService(registry, new ApprovalPolicy(), audit);
    var authorized = registry.authorize("demo", "当前项目", workspace.toString());
    var pending = service.submit("demo", authorized.id(), "user-dev", "git status");

    var executed = service.decide("demo", pending.taskId(), "user-reviewer", ApprovalDecision.ALLOW, "允许查看状态");

    assertThat(executed.taskId()).isEqualTo(pending.taskId());
    assertThat(executed.command()).isEqualTo("git status");
    assertThat(executed.approvalDecision()).isEqualTo(ApprovalDecision.ALLOW);
    assertThat(executed.status()).isIn(ExecutionTaskStatus.SUCCESS, ExecutionTaskStatus.FAILED);
    assertThat(executed.exitCode()).isNotNull();
    assertThat(executed.approverId()).isEqualTo("user-reviewer");
    assertThat(executed.decidedAt()).isNotNull();
    assertThat(service.recentTasks("demo").getFirst().taskId()).isEqualTo(pending.taskId());
    assertThat(audit.records()).extracting("decision").containsExactly(ApprovalDecision.ASK, ApprovalDecision.ALLOW);
}

@Test
void 已处理任务不能重复审批() {
    var registry = new WorkspaceRegistry();
    var service = new LocalCommandService(registry, new ApprovalPolicy(), new AuditService());
    var authorized = registry.authorize("demo", "当前项目", workspace.toString());
    var pending = service.submit("demo", authorized.id(), "user-dev", "git status");
    service.decide("demo", pending.taskId(), "user-reviewer", ApprovalDecision.DENY, "拒绝");

    assertThatThrownBy(() -> service.decide("demo", pending.taskId(), "user-reviewer", ApprovalDecision.ALLOW, "再次批准"))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("任务已完成审批，不能重复处理");
}

@Test
void 高风险命令即使批准也不会执行() {
    var registry = new WorkspaceRegistry();
    var audit = new AuditService();
    var service = new LocalCommandService(registry, new ApprovalPolicy(), audit);
    var authorized = registry.authorize("demo", "当前项目", workspace.toString());
    var pending = service.submit("demo", authorized.id(), "user-ops", "ssh prod systemctl restart app");

    var rejected = service.decide("demo", pending.taskId(), "user-reviewer", ApprovalDecision.ALLOW, "不应执行远程命令");

    assertThat(rejected.status()).isEqualTo(ExecutionTaskStatus.DENIED);
    assertThat(rejected.approvalDecision()).isEqualTo(ApprovalDecision.DENY);
    assertThat(rejected.exitCode()).isNull();
    assertThat(rejected.stderrSummary()).contains("该命令不在第五阶段可批准执行范围内");
    assertThat(rejected.safetyRejectionReason()).contains("该命令不在第五阶段可批准执行范围内");
    assertThat(audit.records()).extracting("decision").containsExactly(ApprovalDecision.ASK, ApprovalDecision.DENY);
}

@Test
void 工作区撤销后待审批任务不能被批准执行() {
    var registry = new WorkspaceRegistry();
    var service = new LocalCommandService(registry, new ApprovalPolicy(), new AuditService());
    var authorized = registry.authorize("demo", "当前项目", workspace.toString());
    var pending = service.submit("demo", authorized.id(), "user-dev", "git status");
    registry.revoke("demo", authorized.id());

    assertThatThrownBy(() -> service.decide("demo", pending.taskId(), "user-reviewer", ApprovalDecision.ALLOW, "批准"))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("工作区未授权");
}
```

- [x] **步骤 2：运行测试验证失败**

运行：

```bash
/Users/Masons/Ai/Maven/bin/mvn -Dmaven.repo.local=/Users/Masons/Ai/Maven_Ai_Store -q -pl server -Dtest=LocalCommandServiceTest test
```

预期：编译失败，错误包含 `decide`、`approverId`、`approvalNote`、`decidedAt` 或 `safetyRejectionReason` 不存在。

- [x] **步骤 3：扩展 `ExecutionTask` 审批字段**

修改 `ExecutionTask` record，新增字段：

```java
String approverId,
String approvalNote,
Instant decidedAt,
String safetyRejectionReason
```

保留旧构造函数，避免第四阶段已有调用点大面积改动：

```java
public ExecutionTask(
        String taskId,
        String projectId,
        String workspaceId,
        String actorId,
        String toolType,
        String command,
        ApprovalDecision approvalDecision,
        ExecutionTaskStatus status,
        Integer exitCode,
        String stdoutSummary,
        String stderrSummary,
        long durationMillis,
        Instant createdAt
) {
    this(taskId, projectId, workspaceId, actorId, toolType, command, approvalDecision, status,
            exitCode, stdoutSummary, stderrSummary, durationMillis, createdAt, "", "", null, "");
}
```

在紧凑构造器中补默认值：

```java
approverId = approverId == null ? "" : approverId.trim();
approvalNote = approvalNote == null ? "" : approvalNote.trim();
safetyRejectionReason = safetyRejectionReason == null ? "" : safetyRejectionReason.trim();
```

- [x] **步骤 4：实现审批决策核心**

在 `LocalCommandService` 中新增公开方法：

```java
public ExecutionTask decide(String projectId, String taskId, String actorId, ApprovalDecision decision, String note) {
    taskId = requireText(taskId, "任务编号不能为空");
    actorId = requireText(actorId, "审批人不能为空");
    if (decision != ApprovalDecision.ALLOW && decision != ApprovalDecision.DENY) {
        throw new IllegalArgumentException("审批决策只能是批准或拒绝");
    }
    var task = findTask(projectId, taskId);
    if (task.status() != ExecutionTaskStatus.APPROVAL_PENDING) {
        throw new IllegalArgumentException("任务已完成审批，不能重复处理");
    }

    if (decision == ApprovalDecision.DENY) {
        var denied = withDecision(task, ApprovalDecision.DENY, ExecutionTaskStatus.DENIED,
                null, "", "", 0, actorId, note, "");
        audit(task, actorId, ApprovalDecision.DENY);
        return replace(denied);
    }

    var workspace = workspaces.requireAuthorized(projectId, task.workspaceId());
    var rejectionReason = manualApprovalRejectionReason(task.command());
    if (!rejectionReason.isBlank()) {
        var rejected = withDecision(task, ApprovalDecision.DENY, ExecutionTaskStatus.DENIED,
                null, "", rejectionReason, 0, actorId, note, rejectionReason);
        audit(task, actorId, ApprovalDecision.DENY);
        return replace(rejected);
    }

    audit(task, actorId, ApprovalDecision.ALLOW);
    var executed = execute(task.taskId(), projectId, task.workspaceId(), task.actorId(),
            task.command(), workspace.rootPath(), ApprovalDecision.ALLOW);
    executed = new ExecutionTask(executed.taskId(), executed.projectId(), executed.workspaceId(), executed.actorId(),
            executed.toolType(), executed.command(), executed.approvalDecision(), executed.status(), executed.exitCode(),
            executed.stdoutSummary(), executed.stderrSummary(), executed.durationMillis(), executed.createdAt(),
            actorId, normalizeNote(note), Instant.now(), "");
    return replace(executed);
}
```

新增辅助方法，保持任务列表只保留最新状态：

```java
private ExecutionTask findTask(String projectId, String taskId) {
    return tasks.getOrDefault(projectId, new ArrayDeque<>()).stream()
            .filter(task -> task.taskId().equals(taskId))
            .findFirst()
            .orElseThrow(() -> new IllegalArgumentException("待审批任务不存在"));
}

private ExecutionTask replace(ExecutionTask updated) {
    var records = tasks.computeIfAbsent(updated.projectId(), ignored -> new ArrayDeque<>());
    records.removeIf(task -> task.taskId().equals(updated.taskId()));
    records.addFirst(updated);
    while (records.size() > HISTORY_LIMIT) {
        records.removeLast();
    }
    return updated;
}

private void audit(ExecutionTask task, String actorId, ApprovalDecision decision) {
    auditService.record(new ToolAction(task.taskId(), actorId, task.toolType(), task.command(),
            workspacePathForAudit(task), false), decision);
}

private String workspacePathForAudit(ExecutionTask task) {
    try {
        return workspaces.requireAuthorized(task.projectId(), task.workspaceId()).rootPath();
    } catch (IllegalArgumentException exception) {
        return "工作区未授权：" + task.workspaceId();
    }
}
```

新增人工批准安全检查：

```java
private String manualApprovalRejectionReason(String command) {
    var tokens = tokensOf(command);
    var executable = tokens.isEmpty() ? "" : tokens.getFirst().toLowerCase();
    if (containsUnsafeShellSyntax(command) || containsSensitiveArgument(command)) {
        return "该命令不在第五阶段可批准执行范围内";
    }
    if (List.of("ssh", "scp", "rsync", "deploy", "rollback", "rm", "rmdir", "chmod", "chown", "sudo").contains(executable)) {
        return "该命令不在第五阶段可批准执行范围内";
    }
    return "";
}
```

敏感参数检查可以使用小范围正则：

```java
private boolean containsSensitiveArgument(String command) {
    return Pattern.compile("(?i)(token|password|secret|api-key|private-key|key-file|credential|passphrase)").matcher(command).find();
}
```

- [x] **步骤 5：运行服务层测试验证通过**

运行：

```bash
/Users/Masons/Ai/Maven/bin/mvn -Dmaven.repo.local=/Users/Masons/Ai/Maven_Ai_Store -q -pl server -Dtest=LocalCommandServiceTest test
```

预期：`LocalCommandServiceTest` 通过。

- [x] **步骤 6：提交**

```bash
git add server/src/main/java/com/matrixcode/localexecution/domain/ExecutionTask.java \
  server/src/main/java/com/matrixcode/localexecution/application/LocalCommandService.java \
  server/src/test/java/com/matrixcode/localexecution/LocalCommandServiceTest.java
git commit -m "feat: 添加本地命令人工审批核心"
```

---

### 任务 2：接入 REST 审批接口和工作台摘要

**文件：**

- 修改：`server/src/main/java/com/matrixcode/localexecution/api/LocalExecutionController.java`
- 修改：`server/src/test/java/com/matrixcode/localexecution/LocalExecutionControllerTest.java`

- [x] **步骤 1：编写接口失败测试**

修改 `LocalExecutionControllerTest`，新增审批接口测试：

```java
@Test
void 可以通过接口拒绝待审批任务并在摘要中看到结果() throws Exception {
    var workspaceResponse = mockMvc.perform(post("/api/projects/demo/local-execution/workspaces")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""
                            {"name":"当前项目","rootPath":"%s"}
                            """.formatted(escapePath(workspace))))
            .andExpect(status().isOk())
            .andReturn();
    var workspaceId = JsonPath.read(workspaceResponse.getResponse().getContentAsString(), "$.id").toString();
    var commandResponse = mockMvc.perform(post("/api/projects/demo/local-execution/commands")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""
                            {"workspaceId":"%s","actorId":"user-dev","command":"git status"}
                            """.formatted(workspaceId)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.status").value("APPROVAL_PENDING"))
            .andReturn();
    var taskId = JsonPath.read(commandResponse.getResponse().getContentAsString(), "$.taskId").toString();

    mockMvc.perform(post("/api/projects/demo/local-execution/commands/%s/approval".formatted(taskId))
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""
                            {"actorId":"user-reviewer","decision":"DENY","note":"本轮不执行"}
                            """))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.status").value("DENIED"))
            .andExpect(jsonPath("$.approvalDecision").value("DENY"))
            .andExpect(jsonPath("$.approverId").value("user-reviewer"))
            .andExpect(jsonPath("$.approvalNote").value("本轮不执行"));

    mockMvc.perform(get("/api/projects/demo/local-execution/summary"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.recentTasks[0].taskId").value(taskId))
            .andExpect(jsonPath("$.recentTasks[0].status").value("DENIED"))
            .andExpect(jsonPath("$.recentAuditRecords[0].decision").value("DENY"));
}

@Test
void 审批接口会拒绝重复处理() throws Exception {
    var workspaceResponse = mockMvc.perform(post("/api/projects/demo/local-execution/workspaces")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""
                            {"name":"当前项目","rootPath":"%s"}
                            """.formatted(escapePath(workspace))))
            .andExpect(status().isOk())
            .andReturn();
    var workspaceId = JsonPath.read(workspaceResponse.getResponse().getContentAsString(), "$.id").toString();
    var commandResponse = mockMvc.perform(post("/api/projects/demo/local-execution/commands")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""
                            {"workspaceId":"%s","actorId":"user-dev","command":"git status"}
                            """.formatted(workspaceId)))
            .andExpect(status().isOk())
            .andReturn();
    var taskId = JsonPath.read(commandResponse.getResponse().getContentAsString(), "$.taskId").toString();
    var decisionBody = """
            {"actorId":"user-reviewer","decision":"DENY","note":"拒绝"}
            """;

    mockMvc.perform(post("/api/projects/demo/local-execution/commands/%s/approval".formatted(taskId))
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(decisionBody))
            .andExpect(status().isOk());

    mockMvc.perform(post("/api/projects/demo/local-execution/commands/%s/approval".formatted(taskId))
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(decisionBody))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.message").value(containsString("任务已完成审批，不能重复处理")));
}
```

- [x] **步骤 2：运行接口测试验证失败**

运行：

```bash
/Users/Masons/Ai/Maven/bin/mvn -Dmaven.repo.local=/Users/Masons/Ai/Maven_Ai_Store -q -pl server -Dtest=LocalExecutionControllerTest test
```

预期：接口返回 404 或编译失败，错误指向 `/approval` 端点不存在。

- [x] **步骤 3：实现审批接口**

在 `LocalExecutionController` 中新增方法：

```java
@PostMapping("/commands/{taskId}/approval")
public ExecutionTask decideCommandApproval(
        @PathVariable String projectId,
        @PathVariable String taskId,
        @RequestBody ApprovalCommand command
) {
    return commandService.decide(projectId, taskId, command.actorId(), command.decision(), command.note());
}
```

新增请求 record：

```java
public record ApprovalCommand(String actorId, ApprovalDecision decision, String note) {
}
```

同时在文件顶部导入：

```java
import com.matrixcode.approval.domain.ApprovalDecision;
```

- [x] **步骤 4：运行接口和工作台回归测试验证通过**

运行：

```bash
/Users/Masons/Ai/Maven/bin/mvn -Dmaven.repo.local=/Users/Masons/Ai/Maven_Ai_Store -q -pl server -Dtest=LocalExecutionControllerTest,WorkbenchLocalExecutionTest test
```

预期：本地执行控制器和工作台本地执行摘要测试通过。

- [x] **步骤 5：提交**

```bash
git add server/src/main/java/com/matrixcode/localexecution/api/LocalExecutionController.java \
  server/src/test/java/com/matrixcode/localexecution/LocalExecutionControllerTest.java
git commit -m "feat: 接入本地命令审批接口"
```

---

### 任务 3：扩展桌面 API 类型和审批客户端

**文件：**

- 修改：`desktop/src/api/client.ts`
- 修改：`desktop/src/api/client.test.ts`

- [x] **步骤 1：编写桌面 API 失败测试**

修改 `desktop/src/api/client.test.ts` 的 import，加入：

```ts
decideLocalCommandApproval,
```

在“角色工作台 API 客户端”测试组中新增：

```ts
it('审批本地命令时调用任务审批地址', async () => {
  const fetchMock = vi.fn().mockResolvedValue({
    ok: true,
    json: async () => ({ taskId: 'task-1', status: 'DENIED', approvalDecision: 'DENY' })
  });
  vi.stubGlobal('fetch', fetchMock);
  const input = { actorId: 'user-reviewer', decision: 'DENY' as const, note: '本轮不执行' };

  await decideLocalCommandApproval('demo', 'task-1', input, 'http://localhost:8080');

  expect(fetchMock).toHaveBeenCalledWith(
    'http://localhost:8080/api/projects/demo/local-execution/commands/task-1/approval',
    {
      method: 'POST',
      headers: { Accept: 'application/json', 'Content-Type': 'application/json' },
      body: JSON.stringify(input)
    }
  );
});
```

- [x] **步骤 2：运行桌面 API 测试验证失败**

运行：

```bash
cd desktop && npm test -- src/api/client.test.ts
```

预期：Vitest 失败，错误包含 `decideLocalCommandApproval` 未导出。

- [x] **步骤 3：扩展类型和客户端函数**

修改 `ExecutionTask` 类型，新增服务端字段：

```ts
approverId: string;
approvalNote: string;
decidedAt: string | null;
safetyRejectionReason: string;
```

新增审批输入类型：

```ts
export type LocalCommandApprovalInput = {
  actorId: string;
  decision: 'ALLOW' | 'DENY';
  note?: string;
};
```

新增客户端函数：

```ts
export function decideLocalCommandApproval(
  projectId: string,
  taskId: string,
  input: LocalCommandApprovalInput,
  serverUrl = matrixCodeServerUrl()
): Promise<ExecutionTask> {
  return requestJson<ExecutionTask>(
    projectUrl(serverUrl, projectId, `/local-execution/commands/${encodeURIComponent(taskId)}/approval`),
    {
      method: 'POST',
      body: JSON.stringify(input)
    }
  );
}
```

- [x] **步骤 4：运行桌面 API 测试验证通过**

运行：

```bash
cd desktop && npm test -- src/api/client.test.ts
```

预期：`client.test.ts` 通过。

- [x] **步骤 5：提交**

```bash
git add desktop/src/api/client.ts desktop/src/api/client.test.ts
git commit -m "feat: 添加桌面本地命令审批客户端"
```

---

### 任务 4：在右侧指标栏实现内联审批交互

**文件：**

- 修改：`desktop/src/components/InspectorPanel.tsx`
- 修改：`desktop/src/App.tsx`
- 修改：`desktop/src/App.css`
- 修改：`desktop/src/test/App.test.tsx`

- [x] **步骤 1：编写审批 UI 失败测试**

修改 `desktop/src/test/App.test.tsx`：

1. 在 import 中加入：

```ts
decideLocalCommandApproval,
```

2. 在 `vi.mock('../api/client', () => ({ ... }))` 中加入：

```ts
decideLocalCommandApproval: vi.fn(),
```

3. 在 mock 变量区新增：

```ts
const 审批本地命令 = vi.mocked(decideLocalCommandApproval);
```

4. 在 `beforeEach` 中新增默认返回：

```ts
审批本地命令.mockResolvedValue({
  ...基础工作台.localExecution.recentTasks[0],
  approvalDecision: 'DENY',
  status: 'DENIED',
  approverId: 'user-reviewer',
  approvalNote: '本轮不执行',
  decidedAt: '2026-06-24T10:05:00Z',
  safetyRejectionReason: ''
});
```

5. 补齐 `基础工作台.localExecution.recentTasks[0]` 的新增字段：

```ts
approverId: '',
approvalNote: '',
decidedAt: null,
safetyRejectionReason: ''
```

6. 新增交互测试：

```ts
it('可以在右侧本地执行代理卡片拒绝待审批命令', async () => {
  加载项目工作台.mockResolvedValueOnce(基础工作台).mockResolvedValueOnce({
    ...基础工作台,
    localExecution: {
      ...基础工作台.localExecution,
      recentTasks: [
        {
          ...基础工作台.localExecution.recentTasks[0],
          approvalDecision: 'DENY',
          status: 'DENIED',
          approverId: 'user-reviewer',
          approvalNote: '本轮不执行',
          decidedAt: '2026-06-24T10:05:00Z',
          safetyRejectionReason: ''
        }
      ]
    }
  });

  render(<App />);

  const 本地执行代理 = within(await screen.findByLabelText('本地执行代理'));
  fireEvent.click(本地执行代理.getByRole('button', { name: '拒绝' }));

  expect(审批本地命令).toHaveBeenCalledWith('demo', 'task-1', {
    actorId: 'user-reviewer',
    decision: 'DENY',
    note: '用户在工作台右侧指标栏拒绝执行'
  });
  await waitFor(() => expect(加载项目工作台).toHaveBeenCalledTimes(2));
});

it('可以在右侧本地执行代理卡片批准执行待审批命令', async () => {
  加载项目工作台.mockResolvedValueOnce(基础工作台).mockResolvedValueOnce({
    ...基础工作台,
    localExecution: {
      ...基础工作台.localExecution,
      recentTasks: [
        {
          ...基础工作台.localExecution.recentTasks[0],
          command: 'git status',
          approvalDecision: 'ALLOW',
          status: 'FAILED',
          exitCode: 128,
          approverId: 'user-reviewer',
          approvalNote: '用户在工作台右侧指标栏批准执行',
          decidedAt: '2026-06-24T10:05:00Z',
          safetyRejectionReason: ''
        }
      ]
    }
  });

  render(<App />);

  const 本地执行代理 = within(await screen.findByLabelText('本地执行代理'));
  fireEvent.click(本地执行代理.getByRole('button', { name: '批准执行' }));

  expect(审批本地命令).toHaveBeenCalledWith('demo', 'task-1', {
    actorId: 'user-reviewer',
    decision: 'ALLOW',
    note: '用户在工作台右侧指标栏批准执行'
  });
  await waitFor(() => expect(加载项目工作台).toHaveBeenCalledTimes(2));
});
```

- [x] **步骤 2：运行 UI 测试验证失败**

运行：

```bash
cd desktop && npm test -- src/test/App.test.tsx
```

预期：测试失败，错误包含 `decideLocalCommandApproval` 未 mock、按钮不存在或 `ExecutionTask` 类型缺字段。

- [x] **步骤 3：扩展 `InspectorPanel` props 和审批按钮**

修改 `InspectorPanelProps`：

```ts
  approvalBusyTaskId?: string | null;
  onDecideLocalCommandApproval: (taskId: string, decision: 'ALLOW' | 'DENY') => Promise<void>;
```

在组件内筛选待审批任务：

```ts
const pendingTask = localExecution.recentTasks.find((task) => task.status === 'APPROVAL_PENDING');
const taskForDisplay = pendingTask ?? recentTask;
const approvalBusy = taskForDisplay ? approvalBusyTaskId === taskForDisplay.taskId : false;
```

将原来的“最近命令”展示改成更清晰的审批区：

```tsx
{taskForDisplay ? (
  <li>
    <span>
      最近命令 {taskForDisplay.status} · {taskForDisplay.approvalDecision} · {taskForDisplay.command}
    </span>
  </li>
) : null}
{pendingTask ? (
  <div className="approval-actions" aria-label="本地命令审批操作">
    <button
      className="primary-button approval-actions__button"
      disabled={approvalBusy}
      onClick={() => void onDecideLocalCommandApproval(pendingTask.taskId, 'ALLOW')}
      type="button"
    >
      批准执行
    </button>
    <button
      className="secondary-button approval-actions__button"
      disabled={approvalBusy}
      onClick={() => void onDecideLocalCommandApproval(pendingTask.taskId, 'DENY')}
      type="button"
    >
      拒绝
    </button>
  </div>
) : null}
```

在非待审批任务下展示审批人和安全拒绝原因：

```tsx
{taskForDisplay?.approverId ? <p className="form-hint">审批人：{taskForDisplay.approverId}</p> : null}
{taskForDisplay?.safetyRejectionReason ? (
  <p className="form-hint">安全拒绝：{taskForDisplay.safetyRejectionReason}</p>
) : null}
```

- [x] **步骤 4：在 `App.tsx` 接入审批调用**

修改 import：

```ts
  decideLocalCommandApproval,
```

新增固定审批人常量：

```ts
const localApprovalActorId = 'user-reviewer';
```

新增状态：

```ts
const [approvalBusyTaskId, setApprovalBusyTaskId] = useState<string | null>(null);
```

新增处理函数：

```ts
async function handleDecideLocalCommandApproval(taskId: string, decision: 'ALLOW' | 'DENY') {
  if (workbenchState.type !== 'ready') {
    return;
  }
  setApprovalBusyTaskId(taskId);
  try {
    await decideLocalCommandApproval(workbenchState.workbench.projectId, taskId, {
      actorId: localApprovalActorId,
      decision,
      note: decision === 'ALLOW' ? '用户在工作台右侧指标栏批准执行' : '用户在工作台右侧指标栏拒绝执行'
    });
    await refreshWorkbench({ keepCurrent: true });
  } finally {
    setApprovalBusyTaskId(null);
  }
}
```

传给 `InspectorPanel`：

```tsx
approvalBusyTaskId={approvalBusyTaskId}
onDecideLocalCommandApproval={handleDecideLocalCommandApproval}
```

- [x] **步骤 5：补充紧凑按钮样式**

在 `App.css` 增加：

```css
.approval-actions {
  display: flex;
  gap: 8px;
  margin-top: 10px;
}

.approval-actions__button {
  flex: 1;
  min-height: 34px;
  padding: 8px 10px;
  font-size: 0.84rem;
}
```

- [x] **步骤 6：运行桌面测试验证通过**

运行：

```bash
cd desktop && npm test -- src/test/App.test.tsx
```

预期：`App.test.tsx` 通过。

- [x] **步骤 7：提交**

```bash
git add desktop/src/components/InspectorPanel.tsx desktop/src/App.tsx desktop/src/App.css desktop/src/test/App.test.tsx
git commit -m "feat: 在右侧指标栏接入本地命令审批"
```

---

### 任务 5：整体验证和文档更新

**文件：**

- 修改：`docs/development/local-run.md`
- 修改：`docs/superpowers/plans/2026-06-24-matrixcode-approval-execution-loop.md`

- [x] **步骤 1：运行服务端全量测试**

运行：

```bash
/Users/Masons/Ai/Maven/bin/mvn -Dmaven.repo.local=/Users/Masons/Ai/Maven_Ai_Store -q -pl server test
```

预期：服务端全部测试通过。运行后统计 Surefire 汇总：

```bash
awk '/Tests run:/ {split($3,a,","); split($5,b,","); split($7,c,","); split($9,d,","); tests+=a[1]; failures+=b[1]; errors+=c[1]; skipped+=d[1]} END {printf "Tests run: %d, Failures: %d, Errors: %d, Skipped: %d\n", tests, failures, errors, skipped}' server/target/surefire-reports/*.txt
```

- [x] **步骤 2：运行桌面端测试和构建**

运行：

```bash
cd desktop
npm test
npm run build
npm run tauri:build -- --help
```

预期：Vitest、TypeScript、Vite 构建和 Tauri 命令入口通过。

- [x] **步骤 3：启动服务端做运行态审批验证**

启动服务端：

```bash
/Users/Masons/Ai/Maven/bin/mvn -Dmaven.repo.local=/Users/Masons/Ai/Maven_Ai_Store -pl server spring-boot:run
```

另一个终端运行：

```bash
curl -sS -X POST http://localhost:8080/api/projects/demo/local-execution/workspaces \
  -H 'Content-Type: application/json' \
  -d '{"name":"MatrixCode 工作区","rootPath":"/Users/Masons/Ai/Codex/MatrixCode/.worktrees/mvp-vertical-slice"}' \
  | tee /tmp/matrixcode-workspace.json
WORKSPACE_ID=$(node -e 'const fs=require("fs"); console.log(JSON.parse(fs.readFileSync("/tmp/matrixcode-workspace.json","utf8")).id)')
curl -sS -X POST http://localhost:8080/api/projects/demo/local-execution/commands \
  -H 'Content-Type: application/json' \
  -d "{\"workspaceId\":\"${WORKSPACE_ID}\",\"actorId\":\"user-dev\",\"command\":\"git status\"}" \
  | tee /tmp/matrixcode-approval-task.json
TASK_ID=$(node -e 'const fs=require("fs"); console.log(JSON.parse(fs.readFileSync("/tmp/matrixcode-approval-task.json","utf8")).taskId)')
curl -sS -X POST "http://localhost:8080/api/projects/demo/local-execution/commands/${TASK_ID}/approval" \
  -H 'Content-Type: application/json' \
  -d '{"actorId":"user-reviewer","decision":"DENY","note":"运行态验证拒绝"}'
curl -sS -i -X POST "http://localhost:8080/api/projects/demo/local-execution/commands/${TASK_ID}/approval" \
  -H 'Content-Type: application/json' \
  -d '{"actorId":"user-reviewer","decision":"ALLOW","note":"重复处理验证"}'
curl -sS http://localhost:8080/api/projects/demo/workbench
```

预期：

- 授权工作区返回 `AUTHORIZED`。
- `git status` 命令提交后返回 `APPROVAL_PENDING`。
- 第一次审批 `DENY` 后返回 `DENIED`。
- 第二次审批同一任务返回 HTTP 400，错误包含 `任务已完成审批，不能重复处理`。
- 工作台 `localExecution.recentTasks[0].status` 为 `DENIED`，最近审计包含 `DENY`。

再提交一个 SSH 命令并尝试批准：

```bash
curl -sS -X POST http://localhost:8080/api/projects/demo/local-execution/commands \
  -H 'Content-Type: application/json' \
  -d "{\"workspaceId\":\"${WORKSPACE_ID}\",\"actorId\":\"user-ops\",\"command\":\"ssh prod systemctl restart app\"}" \
  | tee /tmp/matrixcode-ssh-task.json
SSH_TASK_ID=$(node -e 'const fs=require("fs"); console.log(JSON.parse(fs.readFileSync("/tmp/matrixcode-ssh-task.json","utf8")).taskId)')
curl -sS -X POST "http://localhost:8080/api/projects/demo/local-execution/commands/${SSH_TASK_ID}/approval" \
  -H 'Content-Type: application/json' \
  -d '{"actorId":"user-reviewer","decision":"ALLOW","note":"验证 SSH 不执行"}'
```

预期：返回 `DENIED`，错误摘要包含 `该命令不在第五阶段可批准执行范围内`，不会触发真实远程动作。

- [x] **步骤 4：更新本地运行文档**

在 `docs/development/local-run.md` 的第四阶段验证后新增第五阶段验证说明，包括：

- 提交 `git status` 待审批命令。
- 使用审批接口拒绝任务。
- 重复审批返回中文错误。
- 提交 SSH 命令并尝试批准后仍不会执行。
- 在工作台查看 `localExecution` 最新任务和审计记录。

- [x] **步骤 5：勾选计划状态并追加验证记录**

在本计划末尾新增“第五阶段验证记录”，记录服务端测试、桌面测试构建、运行态审批、重复审批和 SSH 安全拒绝结果。把任务 1 到任务 5 的完成步骤勾选为 `[x]`。

- [x] **步骤 6：检查文档和 diff**

运行：

```bash
rg -n "T(O)DO|T[B]D|F[I]XME|\\bplace(holder)\\b|\\bS[u]mmary\\b|\\bG[o]als\\b|\\bAcceptance C[r]iteria\\b" docs
rg --pcre2 -n "(?<!bin/)\\bm[v]n\\b" docs
git diff --check
```

预期：未发现占位内容和未按本机路径书写的 Maven 命令；`git diff --check` 没有输出。

- [x] **步骤 7：提交**

```bash
git add docs/development/local-run.md docs/superpowers/plans/2026-06-24-matrixcode-approval-execution-loop.md
git commit -m "docs: 记录第五阶段人工审批执行验证"
```

---

## 第五阶段验证记录

验证时间：2026-06-24。

- 服务端全量测试：`/Users/Masons/Ai/Maven/bin/mvn -Dmaven.repo.local=/Users/Masons/Ai/Maven_Ai_Store -q -pl server test`，退出码 0；Surefire 汇总为 `Tests run: 145, Failures: 0, Errors: 0, Skipped: 0`。
- 桌面端测试：`npm test`，2 个测试文件通过，37 个用例通过。
- 桌面端构建：`npm run build`，`tsc --noEmit` 和 `vite build` 通过。
- Tauri 命令入口：`npm run tauri:build -- --help`，退出码 0，CLI 帮助正常输出。
- 运行态授权：`POST /api/projects/demo/local-execution/workspaces` 返回 `AUTHORIZED`，工作区 ID 为 `e82b674d-22fa-479e-9ced-77c21f07046b`。
- 运行态拒绝路径：`git status` 任务 `52ecabad-6f24-41d5-9bb4-61ba8a786c34` 先返回 `APPROVAL_PENDING` 和 `ASK`，拒绝后返回 `DENIED`、`DENY`、`approverId: user-reviewer`。
- 运行态重复审批：同一 `git status` 任务再次提交 `ALLOW` 返回 HTTP 400，响应为 `{"message":"任务已完成审批，不能重复处理"}`。
- 运行态允许路径：`pwd` 任务 `58b0f27c-832a-4a1a-9399-0ed1e8d33a63` 批准后返回 `approvalDecision: ALLOW`、`status: SUCCESS`、`exitCode: 0`，stdout 为当前工作树路径。
- 运行态安全拒绝：`ssh prod systemctl restart app` 任务 `cadc9793-dd90-4668-b679-424664e1fc2e` 人工批准后仍返回 `DENIED` 和 `safetyRejectionReason: 该命令不在第五阶段可批准执行范围内`，未触发远程执行。
- 工作台聚合：`GET /api/projects/demo/workbench` 的 `localExecution.recentTasks` 同时包含 `pwd` 成功、SSH 安全拒绝和 `git status` 拒绝任务，`recentAuditRecords` 包含对应 `ASK`、`ALLOW`、`DENY` 审计记录。
- 服务端进程清理：运行态验证后停止 Spring Boot，`lsof -nP -iTCP:8080 -sTCP:LISTEN` 无监听输出。
- 审查过程中已补强：并发审批、审批包装器绕过、绝对路径/危险子命令、删除类命令、凭证参数和审计脱敏边界均已通过对应单元测试覆盖。

---

## 自检记录

- 规格覆盖：人工批准、拒绝、重复审批保护、安全拒绝、工作台摘要、桌面右侧栏操作和运行态验证均有对应任务。
- 范围控制：真实 SSH、远程部署、多人审批、登录鉴权、批量审批、任务取消、流式日志和数据库持久化不进入本计划。
- 安全边界：审批接口不接收命令正文，只处理已有 `taskId`；人工批准前仍执行服务端安全复核。
- 类型一致性：服务端新增字段 `approverId`、`approvalNote`、`decidedAt`、`safetyRejectionReason`；桌面端同名字段保持一致。
- 验证命令：服务端命令均使用 `/Users/Masons/Ai/Maven/bin/mvn -Dmaven.repo.local=/Users/Masons/Ai/Maven_Ai_Store`。
