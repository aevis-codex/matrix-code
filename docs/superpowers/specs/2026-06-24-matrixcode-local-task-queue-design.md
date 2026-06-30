# MatrixCode 第七阶段本地长任务队列设计规格

## 背景

前六个阶段已经完成角色工作台、模型网关、本地执行代理、人工审批闭环，以及部署健康检查与运维记录。当前本地执行代理可以登记工作区、读取文件、采集 Git diff、提交命令、审批命令，并在工作台展示最近任务和审计记录。

现有限制是：可执行命令仍以同步方式运行，提交接口会等待命令结束后才返回。这个模型适合短命令，但不适合后续的测试套件、构建任务、Docker Compose 演示环境、日志采集和远程部署审批。第七阶段需要把本地命令执行升级为可审计的长任务运行态，让任务可以入队、运行、追加日志、取消，并通过工作台与桌面端持续可见。

本阶段继续保持安全边界：不执行真实 SSH，不运行远程部署脚本，不读取凭证，不接入数据库持久化，也不引入多成员权限。第七阶段只把“已允许执行的本地命令”放入长任务队列。

## 范围

第七阶段实现以下能力：

- 本地命令提交后创建任务，先进入 `QUEUED`，再由队列执行器切换到 `RUNNING`。
- 自动允许的安全命令和人工审批通过的本地命令都进入同一条队列。
- 命令运行期间持续采集标准输出和标准错误，并追加为任务日志。
- 任务完成后更新为 `SUCCESS` 或 `FAILED`，保留退出码、耗时和输出摘要。
- 用户可以取消 `QUEUED` 或 `RUNNING` 任务，取消后任务进入 `CANCELED`。
- 工作台 `localExecution` 摘要展示运行中任务、可取消任务和最近日志。
- 桌面端右侧“本地执行代理”卡片展示任务生命周期、最近日志和取消按钮。
- 服务端和桌面端补齐测试，文档补充第七阶段验证命令。

第七阶段不实现以下内容：

- 真实 SSH、SCP、rsync、远程服务重启、远程部署或远程回滚。
- Docker Compose 生命周期管理和容器日志采集。
- 任务重试、任务优先级、并发池配置、队列持久化。
- 多成员审批、登录鉴权、角色权限和组织策略。
- WebSocket 或 SSE 日志流直连。桌面端本阶段通过工作台刷新读取最新日志。
- PostgreSQL、Redis 或审计报表。

## 用户体验

开发或运维提交安全命令后，界面不再等待命令跑完。右侧“本地执行代理”卡片立即显示新任务状态：

- `QUEUED`：任务已进入队列。
- `RUNNING`：任务正在运行，显示最近日志片段。
- `SUCCESS`：任务成功结束，显示退出码和输出摘要。
- `FAILED`：任务失败结束，显示退出码和错误摘要。
- `CANCELED`：用户取消任务，显示取消人和取消说明。

待审批命令仍保持现有审批体验。用户点击“批准执行”后，服务端只把符合安全边界的本地命令放入队列；SSH、部署、凭证和危险命令即使被人工批准，也继续被安全策略拒绝，不会进入队列。

运行中的任务提供“取消任务”按钮。取消请求成功后，工作台刷新，卡片展示 `CANCELED` 和最近日志。没有运行中任务时，卡片继续展示最近任务、Git diff 文件数和审计摘要。

## 状态模型

扩展 `ExecutionTaskStatus`：

```java
public enum ExecutionTaskStatus {
    APPROVAL_PENDING,
    DENIED,
    QUEUED,
    RUNNING,
    SUCCESS,
    FAILED,
    CANCELED
}
```

状态流转规则：

```text
ASK -> APPROVAL_PENDING -> DENIED
ASK -> APPROVAL_PENDING -> QUEUED -> RUNNING -> SUCCESS
ASK -> APPROVAL_PENDING -> QUEUED -> RUNNING -> FAILED
ASK -> APPROVAL_PENDING -> DENIED（安全策略拒绝人工批准）

ALLOW -> QUEUED -> RUNNING -> SUCCESS
ALLOW -> QUEUED -> RUNNING -> FAILED

QUEUED -> CANCELED
RUNNING -> CANCELED
```

已完成状态包括 `DENIED`、`SUCCESS`、`FAILED` 和 `CANCELED`。已完成任务不能重复审批，也不能再次取消。

## 领域模型

`ExecutionTask` 保留现有字段，并新增取消相关元数据：

```java
public record ExecutionTask(
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
        Instant createdAt,
        String approverId,
        String approvalNote,
        Instant decidedAt,
        String safetyRejectionReason,
        String canceledBy,
        String cancelNote,
        Instant canceledAt
) {
}
```

新增任务日志 record：

```java
public record LocalTaskLog(
        String id,
        String projectId,
        String taskId,
        LocalTaskLogStream stream,
        String content,
        Instant createdAt
) {
}
```

日志流类型：

```java
public enum LocalTaskLogStream {
    STDOUT,
    STDERR,
    SYSTEM
}
```

日志内容单条最多保留 4 KB。每个任务最多保留最近 200 条日志；每个项目最多保留最近 20 个任务。超过上限时丢弃最旧记录。第七阶段继续使用内存存储。

`LocalExecutionSummary` 新增摘要字段：

```java
public record LocalExecutionSummary(
        List<WorkspaceAuthorization> workspaces,
        List<FileOperationRecord> recentFileOperations,
        List<ExecutionTask> recentTasks,
        List<ExecutionTask> activeTasks,
        List<LocalTaskLog> recentTaskLogs,
        GitDiffSummary recentGitDiff,
        List<AuditRecord> recentAuditRecords
) {
}
```

`activeTasks` 包含 `QUEUED` 和 `RUNNING`。`recentTaskLogs` 返回项目内最近 20 条任务日志。

## 服务端架构

新增 `LocalTaskQueueService`，职责如下：

- 接收已允许执行的本地命令，创建 `QUEUED` 任务。
- 使用单线程执行器顺序消费队列，避免第七阶段引入并发调度复杂度。
- 执行开始时把任务状态切换为 `RUNNING`。
- 分别读取标准输出和标准错误，追加 `STDOUT`、`STDERR` 日志。
- 在任务开始、完成、失败、超时和取消时追加 `SYSTEM` 日志。
- 任务完成后更新退出码、输出摘要、错误摘要和耗时。
- 维护 `Process` 引用，允许取消运行中的任务。
- 支持取消还未运行的 `QUEUED` 任务。

`LocalCommandService` 继续负责安全策略、审批和审计，但不再直接同步执行命令：

- `submit(...)` 遇到 `ALLOW` 时调用 `LocalTaskQueueService.enqueue(...)`。
- `submit(...)` 遇到 `ASK` 或 `DENY` 时保持现有行为。
- `decide(...)` 遇到人工 `ALLOW` 且通过安全边界时调用 `enqueueApproved(...)`。
- `decide(...)` 遇到人工 `DENY` 或安全拒绝时保持现有行为。
- `cancel(projectId, taskId, actorId, note)` 委托队列服务处理，并记录审计或系统日志。

`LocalExecutionSummaryService` 聚合：

- `recentTasks(projectId)`
- `activeTasks(projectId)`
- `recentLogs(projectId)`
- 最近审计、文件操作、Git diff。

## 命令执行规则

第七阶段仍沿用第五阶段安全边界。只有审批策略直接返回 `ALLOW` 的命令，或人工批准后再次通过安全边界的本地命令，才允许进入队列。

命令执行继续使用 `ProcessBuilder`，不打开通用 Shell。工作目录固定为授权工作区根路径。

超时规则：

- 默认运行超时 60 秒。
- 超时后强制销毁进程，任务状态为 `FAILED`。
- 错误摘要包含 `命令执行超时`。
- 系统日志追加 `命令执行超时，已终止进程`。

取消规则：

- `QUEUED` 任务取消后不会启动进程。
- `RUNNING` 任务取消时先调用 `destroy()`，短暂等待后仍未退出则调用 `destroyForcibly()`。
- 取消后的状态为 `CANCELED`。
- 取消操作记录取消人、说明和时间。
- 已完成任务取消返回中文错误。

## REST API

保留现有接口：

```http
POST /api/projects/{projectId}/local-execution/commands
POST /api/projects/{projectId}/local-execution/commands/{taskId}/approval
GET /api/projects/{projectId}/local-execution/summary
GET /api/projects/{projectId}/workbench
```

新增取消接口：

```http
POST /api/projects/{projectId}/local-execution/commands/{taskId}/cancel
```

请求体：

```json
{
  "actorId": "user-dev",
  "note": "本轮验证不再需要继续运行。"
}
```

响应为更新后的 `ExecutionTask`。

新增日志查询接口：

```http
GET /api/projects/{projectId}/local-execution/commands/{taskId}/logs
```

响应为该任务最近日志：

```json
[
  {
    "id": "log-1",
    "projectId": "demo",
    "taskId": "task-1",
    "stream": "SYSTEM",
    "content": "任务已进入队列",
    "createdAt": "2026-06-24T12:00:00Z"
  }
]
```

错误处理：

- 任务不存在：HTTP 400，`执行任务不存在`。
- 执行人为空：HTTP 400，`执行人不能为空`。
- 任务已完成：HTTP 400，`任务已结束，不能取消`。
- 工作区未授权：沿用现有工作区错误。

## 事件与审计

新增或复用以下项目事件：

- `LOCAL_COMMAND_QUEUED`：命令进入队列。
- `LOCAL_COMMAND_STARTED`：命令开始运行。
- `LOCAL_COMMAND_LOGGED`：命令产生新日志。事件消息只展示摘要，不包含完整日志。
- `LOCAL_COMMAND_CANCELED`：命令被取消。
- `LOCAL_COMMAND_COMPLETED`：命令完成。
- `LOCAL_COMMAND_FAILED`：命令失败。

审计继续记录命令提交、人工审批和安全拒绝。取消操作追加系统日志，并在审计记录中使用 `DENY` 表示人为终止执行意图，摘要中包含脱敏后的命令。

事件消息必须使用中文，不展示未脱敏凭证。

## 桌面端设计

桌面 API 客户端新增类型：

- `LocalTaskLog`
- `LocalTaskLogStream`

`ExecutionTaskStatus` 增加 `QUEUED` 和 `CANCELED`。

API 客户端新增函数：

- `cancelLocalExecutionTask(projectId, taskId, input, serverUrl?)`
- `loadLocalExecutionTaskLogs(projectId, taskId, serverUrl?)`

右侧 `InspectorPanel` 的“本地执行代理”卡片升级：

- 优先展示 `activeTasks[0]`，没有运行中任务时展示最近任务。
- 状态显示使用中文标签，例如 `排队中`、`运行中`、`已取消`。
- `QUEUED` 和 `RUNNING` 任务显示“取消任务”按钮。
- 展示最近 3 条日志，包含流类型和内容摘要。
- 取消操作中按钮禁用，避免重复点击。
- 取消成功后刷新工作台，失败时沿用现有同步错误提示。

本阶段不新增独立日志页面，不做实时滚动终端，也不把桌面端变成通用命令行。

## 安全边界

- 队列只接收已通过现有审批策略的本地命令。
- `ASK` 任务不会自动入队，必须先人工审批。
- 人工审批通过后仍需再次执行安全边界校验。
- SSH、部署、凭证、危险 Shell 语法、删除、回滚、远程服务重启等命令继续拒绝入队。
- 日志进入工作台前需要截断，单条日志不超过 4 KB。
- 日志和审计摘要不得展示明文 Token、密码、密钥、用户名参数或私钥路径。
- 取消只能影响当前任务进程，不能杀死任意系统进程。

## 测试策略

### 服务端

- 队列服务测试：安全命令提交后先返回 `QUEUED` 或 `RUNNING`，最终进入 `SUCCESS` 或 `FAILED`。
- 日志测试：标准输出、标准错误和系统日志会被追加，并按任务查询。
- 取消测试：`QUEUED` 任务取消后不会启动进程；`RUNNING` 任务取消后进入 `CANCELED`。
- 审批测试：人工批准的本地命令进入队列；人工拒绝和安全拒绝不入队。
- 工作台测试：`LocalExecutionSummary` 包含 `activeTasks` 和 `recentTaskLogs`。
- 接口测试：取消接口、日志接口和工作台摘要可以串起来。
- 安全测试：SSH、部署和凭证命令即使人工批准，也不会进入队列。

### 桌面端

- API 客户端测试：取消接口和日志接口路径、方法和请求体正确。
- 类型测试：`ExecutionTaskStatus`、`LocalTaskLog` 和 `LocalExecutionSummary` 与服务端字段一致。
- 右侧卡片测试：展示排队中、运行中、已取消和最近日志。
- 交互测试：点击“取消任务”调用取消接口并刷新工作台。
- 回归测试：审批按钮、部署状态卡片和模型指标不受影响。

## 运行态验证

本地验证路径：

1. 启动服务端。
2. 授权当前项目工作区。
3. 提交安全命令，确认响应进入 `QUEUED` 或 `RUNNING`。
4. 查询工作台，确认 `activeTasks` 和 `recentTaskLogs` 有内容。
5. 提交一个可取消的长命令，调用取消接口，确认任务进入 `CANCELED`。
6. 查询任务日志，确认包含入队、开始运行、取消等系统日志。
7. 提交 SSH 命令并尝试批准，确认仍被安全拒绝且不会入队。
8. 启动桌面端，确认右侧卡片展示任务状态、最近日志和取消入口。

## 验收标准

- 服务端全量测试通过，命令使用 `/Users/Masons/Ai/Maven/bin/mvn -Dmaven.repo.local=/Users/Masons/Ai/Maven_Ai_Store -q -pl server test`。
- 桌面端测试、构建和 Tauri 命令入口通过。
- 自动允许的本地命令不再同步阻塞接口，而是进入长任务队列。
- 人工审批通过的安全本地命令进入同一队列。
- 任务运行期间可以查询最近日志。
- `QUEUED` 和 `RUNNING` 任务可以取消，完成状态不能重复取消。
- 工作台 `localExecution` 返回运行中任务和最近任务日志。
- 桌面端右侧“本地执行代理”卡片展示任务状态、最近日志和取消按钮。
- SSH、部署、凭证和危险命令仍不会进入队列。
- 文档中没有占位符、英文模板标题或不符合本地 Maven 路径的命令。

## 后续阶段入口

第八阶段可以继续扩展以下方向：

- Docker Compose 演示环境生命周期和容器日志采集。
- 真实 SSH 执行与远程部署审批。
- WebSocket 或 SSE 直连日志流。
- 任务重试、并发池、优先级和持久化队列。
- 登录鉴权、多成员权限和组织策略。
