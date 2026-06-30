# MatrixCode 第九阶段 Compose 运行态实现计划

> **面向 AI 代理的工作者：** 必需子技能：使用 superpowers:subagent-driven-development（推荐）或 superpowers:executing-plans 逐任务实现此计划。步骤使用复选框（`- [x]`）语法来跟踪进度。

**目标：** 为部署目标增加受控 Docker Compose 演示环境配置、生命周期动作、日志采样和工作台展示。

**架构：** 在 `deployment` 模块内新增 Compose 运行态领域对象、应用服务和可替换运行时客户端；`WorkbenchService` 聚合 Compose 环境摘要；桌面端在运维面板和右侧指标栏展示并触发动作。真实 Docker Compose 只通过参数化 `ProcessBuilder` 执行，不复用本地命令 shell 通道。

**技术栈：** Java 21、Spring Boot、JUnit 5、AssertJ、MockMvc、React、TypeScript、Vitest、Testing Library。

---

## 文件结构

- 创建：`server/src/main/java/com/matrixcode/deployment/domain/ComposeEnvironment.java`
  - Compose 演示环境配置记录。
- 创建：`server/src/main/java/com/matrixcode/deployment/domain/ComposeEnvironmentStatus.java`
  - 环境状态枚举。
- 创建：`server/src/main/java/com/matrixcode/deployment/domain/ComposeOperationRecord.java`
  - Compose 动作记录。
- 创建：`server/src/main/java/com/matrixcode/deployment/domain/ComposeOperationStatus.java`
  - 动作结果枚举。
- 创建：`server/src/main/java/com/matrixcode/deployment/domain/ComposeOperationType.java`
  - 动作类型枚举。
- 创建：`server/src/main/java/com/matrixcode/deployment/application/ComposeRuntimeClient.java`
  - 可替换 Compose 运行时客户端接口。
- 创建：`server/src/main/java/com/matrixcode/deployment/application/ComposeRuntimeRequest.java`
  - 运行时请求值对象。
- 创建：`server/src/main/java/com/matrixcode/deployment/application/ComposeRuntimeResult.java`
  - 运行时结果值对象。
- 创建：`server/src/main/java/com/matrixcode/deployment/application/DockerComposeRuntimeClient.java`
  - 默认 Docker Compose CLI 适配器。
- 创建：`server/src/main/java/com/matrixcode/deployment/application/ComposeEnvironmentService.java`
  - 环境配置、动作执行、状态更新和操作历史。
- 创建：`server/src/main/java/com/matrixcode/workbench/domain/ComposeRuntimeView.java`
  - 工作台 Compose 运行摘要。
- 修改：`server/src/main/java/com/matrixcode/workbench/domain/ProjectWorkbench.java`
  - 增加 `composeEnvironments` 和 `composeRuntimeViews`。
- 修改：`server/src/main/java/com/matrixcode/workbench/application/WorkbenchService.java`
  - 注入 Compose 服务，聚合摘要并提供动作入口。
- 修改：`server/src/main/java/com/matrixcode/workbench/api/WorkbenchController.java`
  - 新增 Compose 配置和动作接口。
- 创建：`server/src/test/java/com/matrixcode/deployment/ComposeEnvironmentServiceTest.java`
  - 服务级 TDD 覆盖。
- 创建：`server/src/test/java/com/matrixcode/deployment/DockerComposeRuntimeClientTest.java`
  - CLI 不可用失败记录测试。
- 修改：`server/src/test/java/com/matrixcode/workbench/WorkbenchServiceTest.java`
  - 聚合测试增加 Compose 运行态。
- 修改：`server/src/test/java/com/matrixcode/workbench/WorkbenchControllerTest.java`
  - 接口测试增加 Compose 配置。
- 修改：`server/src/test/java/com/matrixcode/localexecution/WorkbenchLocalExecutionTest.java`
  - 更新 `WorkbenchService` 构造参数。
- 修改：`desktop/src/api/client.ts`
  - 新增 Compose 类型和 API 函数。
- 修改：`desktop/src/api/client.test.ts`
  - 新增 Compose API 路径测试。
- 修改：`desktop/src/App.tsx`
  - 接入 Compose 处理函数和 props。
- 修改：`desktop/src/components/OpsPanel.tsx`
  - 新增 Compose 演示环境表单和动作区。
- 修改：`desktop/src/components/InspectorPanel.tsx`
  - 新增 Compose 运行态指标卡。
- 修改：`desktop/src/App.css`
  - 复用现有表单和日志样式，补充 Compose 状态摘录样式。
- 修改：`desktop/src/test/App.test.tsx`
  - 新增桌面交互测试和基础工作台字段。
- 修改：`docs/development/local-run.md`
  - 补充第九阶段验证步骤。
- 修改：`docs/superpowers/plans/2026-06-25-matrixcode-compose-runtime.md`
  - 记录红灯、绿灯、全量验证和浏览器验证结果。

## 任务 1：服务端 Compose 服务测试红灯

**文件：**
- 创建：`server/src/test/java/com/matrixcode/deployment/ComposeEnvironmentServiceTest.java`
- 创建：`server/src/test/java/com/matrixcode/deployment/DockerComposeRuntimeClientTest.java`

- [x] **步骤 1：编写失败的服务测试**

新增测试类，核心断言如下：

```java
@Test
void 配置Compose环境时要求授权工作区内Yaml文件并关联部署目标() throws Exception {
    Files.writeString(workspace.resolve("compose.yml"), "services:\n  web:\n    image: nginx:alpine\n");
    var workspaceAuth = workspaces.authorize("demo", "演示工作区", workspace.toString());
    var target = targets.configure("demo", "演示环境", "http://127.0.0.1:8080",
            "deploy@local", "本地 Compose 演示", "http://127.0.0.1:8080/health", "停止演示服务");

    var environment = service.configure("demo", target.id(), workspaceAuth.id(), "compose.yml", "matrixcode-demo", "web");

    assertThat(environment.status()).isEqualTo(ComposeEnvironmentStatus.CONFIGURED);
    assertThat(environment.composeFilePath()).isEqualTo("compose.yml");
    assertThat(environment.projectName()).isEqualTo("matrixcode-demo");
    assertThat(environment.serviceName()).isEqualTo("web");
    assertThat(service.listByProject("demo")).containsExactly(environment);
}
```

同一测试类继续覆盖：

```java
@Test
void 越界路径和非Yaml文件会被拒绝() throws Exception {
    Files.writeString(workspace.resolve("README.md"), "不是 Compose 文件");
    var workspaceAuth = workspaces.authorize("demo", "演示工作区", workspace.toString());
    var target = targets.configure("demo", "演示环境", "http://127.0.0.1:8080",
            "deploy@local", "本地 Compose 演示", "http://127.0.0.1:8080/health", "停止演示服务");

    assertThatThrownBy(() -> service.configure("demo", target.id(), workspaceAuth.id(), "../compose.yml", "matrixcode-demo", "web"))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("路径");
    assertThatThrownBy(() -> service.configure("demo", target.id(), workspaceAuth.id(), "README.md", "matrixcode-demo", "web"))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("Compose 文件必须使用 .yml 或 .yaml 后缀");
}
```

动作测试使用 fake 客户端：

```java
@Test
void 校验启动停止和日志采样会更新状态并记录最近操作() throws Exception {
    var environment = prepareEnvironment();
    runtime.nextResult = ComposeRuntimeResult.succeeded("Compose 配置有效", "services:\n  web:");

    var validation = service.validate("demo", environment.id(), "user-ops");
    var start = service.start("demo", environment.id(), "user-ops");
    var logs = service.captureLogs("demo", environment.id(), "user-ops");
    var stop = service.stop("demo", environment.id(), "user-ops");

    assertThat(validation.type()).isEqualTo(ComposeOperationType.VALIDATE);
    assertThat(start.type()).isEqualTo(ComposeOperationType.START);
    assertThat(logs.type()).isEqualTo(ComposeOperationType.LOGS);
    assertThat(stop.type()).isEqualTo(ComposeOperationType.STOP);
    assertThat(service.requireByProject("demo", environment.id()).status()).isEqualTo(ComposeEnvironmentStatus.STOPPED);
    assertThat(service.latestOperationForEnvironment("demo", environment.id())).isEqualTo(stop);
}
```

`DockerComposeRuntimeClientTest` 验证传入不存在的 Docker 可执行文件时返回失败：

```java
@Test
void Docker命令不可用时返回失败结果而不是抛出异常() {
    var client = new DockerComposeRuntimeClient("definitely-missing-docker-binary");
    var request = new ComposeRuntimeRequest(Path.of("compose.yml"), "matrixcode-demo", "web");

    var result = client.validate(request);

    assertThat(result.status()).isEqualTo(ComposeOperationStatus.FAILED);
    assertThat(result.summary()).contains("Docker Compose 不可用");
}
```

- [x] **步骤 2：运行测试验证红灯**

运行：

```bash
/Users/Masons/Ai/Maven/bin/mvn -Dmaven.repo.local=/Users/Masons/Ai/Maven_Ai_Store -q -pl server -Dtest=ComposeEnvironmentServiceTest,DockerComposeRuntimeClientTest test
```

预期：编译失败，提示 `ComposeEnvironmentService`、`DockerComposeRuntimeClient` 或相关领域类型不存在。

## 任务 2：实现服务端 Compose 领域和运行时客户端

**文件：**
- 创建：`server/src/main/java/com/matrixcode/deployment/domain/ComposeEnvironment.java`
- 创建：`server/src/main/java/com/matrixcode/deployment/domain/ComposeEnvironmentStatus.java`
- 创建：`server/src/main/java/com/matrixcode/deployment/domain/ComposeOperationRecord.java`
- 创建：`server/src/main/java/com/matrixcode/deployment/domain/ComposeOperationStatus.java`
- 创建：`server/src/main/java/com/matrixcode/deployment/domain/ComposeOperationType.java`
- 创建：`server/src/main/java/com/matrixcode/deployment/application/ComposeRuntimeClient.java`
- 创建：`server/src/main/java/com/matrixcode/deployment/application/ComposeRuntimeRequest.java`
- 创建：`server/src/main/java/com/matrixcode/deployment/application/ComposeRuntimeResult.java`
- 创建：`server/src/main/java/com/matrixcode/deployment/application/DockerComposeRuntimeClient.java`
- 创建：`server/src/main/java/com/matrixcode/deployment/application/ComposeEnvironmentService.java`

- [x] **步骤 1：新增领域类型**

实现状态枚举：

```java
public enum ComposeEnvironmentStatus {
    CONFIGURED,
    VALIDATED,
    RUNNING,
    STOPPED,
    FAILED
}
```

实现动作枚举和记录：

```java
public enum ComposeOperationType {
    VALIDATE,
    START,
    STOP,
    LOGS
}

public enum ComposeOperationStatus {
    SUCCEEDED,
    FAILED
}
```

- [x] **步骤 2：新增运行时客户端接口和结果对象**

核心签名：

```java
public interface ComposeRuntimeClient {
    ComposeRuntimeResult validate(ComposeRuntimeRequest request);
    ComposeRuntimeResult start(ComposeRuntimeRequest request);
    ComposeRuntimeResult stop(ComposeRuntimeRequest request);
    ComposeRuntimeResult logs(ComposeRuntimeRequest request);
}
```

结果对象提供静态工厂：

```java
public static ComposeRuntimeResult succeeded(String summary, String logExcerpt)
public static ComposeRuntimeResult failed(String summary, String logExcerpt)
```

- [x] **步骤 3：实现 DockerComposeRuntimeClient**

用 `ProcessBuilder` 参数数组执行，不使用 shell：

```java
private ComposeRuntimeResult run(List<String> command) {
    Process process = null;
    Thread outputReader = null;
    var output = new StringBuilder();
    try {
        process = new ProcessBuilder(command).redirectErrorStream(true).start();
        outputReader = captureOutput(process, output);
        var completed = process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS);
        if (!completed) {
            destroyProcessTree(process);
            waitForOutput(outputReader);
            return ComposeRuntimeResult.failed("Docker Compose 命令超时", truncate(output(output)));
        }
        waitForOutput(outputReader);
        return process.exitValue() == 0
                ? ComposeRuntimeResult.succeeded("Docker Compose 命令执行成功", truncate(output(output)))
                : ComposeRuntimeResult.failed("Docker Compose 命令执行失败，退出码：" + process.exitValue(), truncate(output(output)));
    } catch (IOException exception) {
        return ComposeRuntimeResult.failed("Docker Compose 不可用", exception.getMessage());
    } catch (InterruptedException exception) {
        Thread.currentThread().interrupt();
        if (process != null) {
            destroyProcessTreeQuietly(process);
        }
        waitForOutputQuietly(outputReader);
        return ComposeRuntimeResult.failed("Docker Compose 命令被中断", exception.getMessage());
    }
}
```

- [x] **步骤 4：实现 ComposeEnvironmentService**

配置时校验：

```java
var target = targets.requireByProject(projectId, targetId);
var workspace = workspaces.requireAuthorized(projectId, workspaceId);
var composeFile = pathGuard.resolveExisting(workspace.rootPath(), composeFilePath);
requireYaml(composeFilePath);
projectName = requirePattern(projectName, PROJECT_NAME_PATTERN, "Compose 项目名不合法");
serviceName = requirePattern(serviceName, SERVICE_NAME_PATTERN, "Compose 服务名不合法");
```

动作执行时更新状态：

```java
var result = runtimeClient.start(request(environment));
var status = result.status() == ComposeOperationStatus.SUCCEEDED
        ? ComposeEnvironmentStatus.RUNNING
        : ComposeEnvironmentStatus.FAILED;
var updated = environment.withStatus(status);
```

- [x] **步骤 5：运行服务测试验证绿灯**

运行：

```bash
/Users/Masons/Ai/Maven/bin/mvn -Dmaven.repo.local=/Users/Masons/Ai/Maven_Ai_Store -q -pl server -Dtest=ComposeEnvironmentServiceTest,DockerComposeRuntimeClientTest test
```

预期：测试通过。

- [x] **步骤 6：提交服务端领域实现**

运行：

```bash
git add server/src/main/java/com/matrixcode/deployment server/src/test/java/com/matrixcode/deployment
git commit -m "feat(部署): 添加 Compose 演示环境运行态服务"
```

## 任务 3：工作台聚合和接口测试红灯

**文件：**
- 修改：`server/src/test/java/com/matrixcode/workbench/WorkbenchServiceTest.java`
- 修改：`server/src/test/java/com/matrixcode/workbench/WorkbenchControllerTest.java`
- 修改：`server/src/test/java/com/matrixcode/localexecution/WorkbenchLocalExecutionTest.java`

- [x] **步骤 1：编写工作台聚合失败测试**

在 `WorkbenchServiceTest` 构造 `ComposeEnvironmentService`，新增断言：

```java
var environment = composeEnvironmentService.configure("demo", target.id(), workspace.id(),
        "compose.yml", "matrixcode-demo", "web");
var operation = composeEnvironmentService.validate("demo", environment.id(), "user-ops");

var workbench = service.get("demo");

assertThat(workbench.composeEnvironments()).containsExactly(composeEnvironmentService.requireByProject("demo", environment.id()));
assertThat(workbench.composeRuntimeViews()).singleElement().satisfies(summary -> {
    assertThat(summary.environmentId()).isEqualTo(environment.id());
    assertThat(summary.latestOperation()).isEqualTo(operation);
});
```

- [x] **步骤 2：编写接口失败测试**

在 `WorkbenchControllerTest` 中新增配置接口测试：

```java
mockMvc.perform(post("/api/projects/demo/deployments/targets/" + targetId + "/compose-environments")
        .contentType(MediaType.APPLICATION_JSON)
        .content("""
                {"workspaceId":"%s","composeFilePath":"compose.yml","projectName":"matrixcode-demo","serviceName":"web"}
                """.formatted(workspaceId)))
    .andExpect(status().isOk())
    .andExpect(jsonPath("$.targetId").value(targetId))
    .andExpect(jsonPath("$.status").value("CONFIGURED"));
```

- [x] **步骤 3：运行测试验证红灯**

运行：

```bash
/Users/Masons/Ai/Maven/bin/mvn -Dmaven.repo.local=/Users/Masons/Ai/Maven_Ai_Store -q -pl server -Dtest=WorkbenchServiceTest,WorkbenchControllerTest,WorkbenchLocalExecutionTest test
```

预期：编译失败，提示 `ProjectWorkbench` 缺少 Compose 字段或控制器缺少接口。

## 任务 4：实现工作台聚合和接口

**文件：**
- 创建：`server/src/main/java/com/matrixcode/workbench/domain/ComposeRuntimeView.java`
- 修改：`server/src/main/java/com/matrixcode/workbench/domain/ProjectWorkbench.java`
- 修改：`server/src/main/java/com/matrixcode/workbench/application/WorkbenchService.java`
- 修改：`server/src/main/java/com/matrixcode/workbench/api/WorkbenchController.java`
- 修改：`server/src/test/java/com/matrixcode/localexecution/WorkbenchLocalExecutionTest.java`

- [x] **步骤 1：扩展 ProjectWorkbench**

增加字段并复制列表：

```java
List<ComposeEnvironment> composeEnvironments,
List<ComposeRuntimeView> composeRuntimeViews,
```

- [x] **步骤 2：扩展 WorkbenchService**

注入 `ComposeEnvironmentService`，在 `get` 中聚合：

```java
var composeEnvironments = composeEnvironmentService.listByProject(projectId);
var composeRuntimeViews = composeEnvironments.stream()
        .map(environment -> new ComposeRuntimeView(
                environment.id(),
                environment.targetId(),
                environment.status(),
                environment.composeFilePath(),
                environment.projectName(),
                environment.serviceName(),
                composeEnvironmentService.latestOperationForEnvironment(projectId, environment.id())
        ))
        .toList();
```

新增动作方法：

```java
public ComposeEnvironment configureComposeEnvironment(...)
public ComposeOperationRecord validateComposeEnvironment(...)
public ComposeOperationRecord startComposeEnvironment(...)
public ComposeOperationRecord stopComposeEnvironment(...)
public ComposeOperationRecord captureComposeLogs(...)
```

- [x] **步骤 3：扩展 WorkbenchController**

新增命令 record：

```java
public record ComposeEnvironmentCommand(String workspaceId, String composeFilePath, String projectName, String serviceName) {
}

public record ComposeOperationCommand(String actorId) {
}
```

新增接口映射。

- [x] **步骤 4：运行工作台测试验证绿灯**

运行：

```bash
/Users/Masons/Ai/Maven/bin/mvn -Dmaven.repo.local=/Users/Masons/Ai/Maven_Ai_Store -q -pl server -Dtest=WorkbenchServiceTest,WorkbenchControllerTest,WorkbenchLocalExecutionTest test
```

预期：测试通过。

- [x] **步骤 5：提交工作台接口实现**

运行：

```bash
git add server/src/main/java/com/matrixcode/workbench server/src/test/java/com/matrixcode/workbench server/src/test/java/com/matrixcode/localexecution/WorkbenchLocalExecutionTest.java
git commit -m "feat(工作台): 聚合 Compose 演示环境运行态"
```

## 任务 5：桌面端 API 和 UI 测试红灯

**文件：**
- 修改：`desktop/src/api/client.test.ts`
- 修改：`desktop/src/test/App.test.tsx`

- [x] **步骤 1：编写 API 客户端失败测试**

新增断言：

```ts
await configureComposeEnvironment(
  'demo',
  'target/1',
  { workspaceId: 'workspace-1', composeFilePath: 'compose.yml', projectName: 'matrixcode-demo', serviceName: 'web' },
  'http://localhost:8080'
);

expect(fetchMock).toHaveBeenCalledWith(
  'http://localhost:8080/api/projects/demo/deployments/targets/target%2F1/compose-environments',
  {
    method: 'POST',
    headers: { Accept: 'application/json', 'Content-Type': 'application/json' },
    body: JSON.stringify({ workspaceId: 'workspace-1', composeFilePath: 'compose.yml', projectName: 'matrixcode-demo', serviceName: 'web' })
  }
);
```

动作接口测试覆盖 `/validate`、`/start`、`/stop` 和 `/logs`。

- [x] **步骤 2：编写 App 交互失败测试**

在基础工作台增加 `composeEnvironments: []` 和 `composeRuntimeViews: []`，新增测试：

```ts
it('运维可以配置 Compose 演示环境并触发运行态动作', async () => {
  加载项目工作台.mockResolvedValueOnce({
    ...部署运行工作台,
    localExecution: { ...部署运行工作台.localExecution, workspaces: 基础工作台.localExecution.workspaces }
  }).mockResolvedValue(Compose运行工作台);

  render(<App />);
  fireEvent.click(await screen.findByRole('button', { name: '运维' }));
  fireEvent.change(screen.getByLabelText('Compose 文件'), { target: { value: 'compose.yml' } });
  fireEvent.change(screen.getByLabelText('Compose 项目名'), { target: { value: 'matrixcode-demo' } });
  fireEvent.change(screen.getByLabelText('服务名'), { target: { value: 'web' } });
  fireEvent.click(screen.getByRole('button', { name: '保存 Compose 环境' }));

  await waitFor(() => expect(配置Compose环境).toHaveBeenCalled());
  fireEvent.click(await screen.findByRole('button', { name: '校验配置' }));
  fireEvent.click(await screen.findByRole('button', { name: '启动演示' }));
  fireEvent.click(await screen.findByRole('button', { name: '采集日志' }));
  fireEvent.click(await screen.findByRole('button', { name: '停止演示' }));
});
```

- [x] **步骤 3：运行桌面测试验证红灯**

运行：

```bash
cd desktop && npm test -- src/api/client.test.ts src/test/App.test.tsx
```

预期：编译失败，提示 Compose API 函数或 props 不存在。

## 任务 6：实现桌面端 Compose API 和交互

**文件：**
- 修改：`desktop/src/api/client.ts`
- 修改：`desktop/src/App.tsx`
- 修改：`desktop/src/components/OpsPanel.tsx`
- 修改：`desktop/src/components/InspectorPanel.tsx`
- 修改：`desktop/src/App.css`

- [x] **步骤 1：新增客户端类型和函数**

新增类型：

```ts
export type ComposeEnvironmentStatus = 'CONFIGURED' | 'VALIDATED' | 'RUNNING' | 'STOPPED' | 'FAILED';
export type ComposeOperationType = 'VALIDATE' | 'START' | 'STOP' | 'LOGS';
export type ComposeOperationStatus = 'SUCCEEDED' | 'FAILED';
```

新增函数：

```ts
export function configureComposeEnvironment(projectId: string, targetId: string, input: ComposeEnvironmentInput, serverUrl = matrixCodeServerUrl()): Promise<ComposeEnvironment>
export function validateComposeEnvironment(projectId: string, environmentId: string, input: ComposeOperationInput, serverUrl = matrixCodeServerUrl()): Promise<ComposeOperationRecord>
export function startComposeEnvironment(...)
export function stopComposeEnvironment(...)
export function captureComposeLogs(...)
```

- [x] **步骤 2：扩展 App**

导入新增 API，增加处理函数：

```ts
async function handleConfigureComposeEnvironment(targetId: string, input: ComposeEnvironmentInput) {
  await configureComposeEnvironment(workbenchState.workbench.projectId, targetId, input);
  await refreshWorkbench({ keepCurrent: true });
}
```

动作处理函数统一使用 `operationsActorId`。

- [x] **步骤 3：扩展 OpsPanel**

新增 props：

```ts
workspaces: WorkspaceAuthorization[];
composeEnvironments: ComposeEnvironment[];
composeRuntimeViews: ComposeRuntimeView[];
onConfigureComposeEnvironment: (targetId: string, input: ComposeEnvironmentInput) => Promise<void>;
onValidateComposeEnvironment: (environmentId: string) => Promise<void>;
onStartComposeEnvironment: (environmentId: string) => Promise<void>;
onStopComposeEnvironment: (environmentId: string) => Promise<void>;
onCaptureComposeLogs: (environmentId: string) => Promise<void>;
```

新增表单和动作区，使用现有 `.role-form`、`.field-grid`、`.form-actions`。

- [x] **步骤 4：扩展 InspectorPanel**

新增 `Compose 运行态` 卡片，状态标签：

```ts
const composeStatusLabels = {
  CONFIGURED: '已配置',
  VALIDATED: '已校验',
  RUNNING: '运行中',
  STOPPED: '已停止',
  FAILED: '失败'
};
```

- [x] **步骤 5：运行桌面测试验证绿灯**

运行：

```bash
cd desktop && npm test -- src/api/client.test.ts src/test/App.test.tsx
```

预期：测试通过。

- [x] **步骤 6：提交桌面实现**

运行：

```bash
git add desktop/src/api/client.ts desktop/src/api/client.test.ts desktop/src/App.tsx desktop/src/components/OpsPanel.tsx desktop/src/components/InspectorPanel.tsx desktop/src/App.css desktop/src/test/App.test.tsx
git commit -m "feat(桌面端): 接入 Compose 演示环境运行态"
```

## 任务 7：文档、全量验证和阶段记录

**文件：**
- 修改：`docs/development/local-run.md`
- 修改：`docs/superpowers/plans/2026-06-25-matrixcode-compose-runtime.md`

- [x] **步骤 1：补充本地运行文档**

在 `docs/development/local-run.md` 增加第九阶段验证说明，包含：

```bash
mkdir -p /tmp/matrixcode-compose-demo
printf '%s\n' \
  'services:' \
  '  web:' \
  '    image: nginx:alpine' \
  '    ports:' \
  '      - "18080:80"' \
  > /tmp/matrixcode-compose-demo/compose.yml
```

文档说明：如果本机 Docker 不可用，校验或启动动作会记录失败摘要；如果 Docker CLI、镜像拉取或凭证助手长时间无响应，动作会受控超时并清理子进程。这些失败路径仍可验证服务端不会崩溃、工作台会展示失败记录。

- [x] **步骤 2：运行服务端全量测试**

运行：

```bash
/Users/Masons/Ai/Maven/bin/mvn -Dmaven.repo.local=/Users/Masons/Ai/Maven_Ai_Store -q -pl server test
```

预期：退出码 0，Surefire 汇总无失败。

- [x] **步骤 3：运行桌面端全量测试**

运行：

```bash
cd desktop && npm test
```

预期：全部测试通过。

- [x] **步骤 4：运行桌面构建**

运行：

```bash
cd desktop && npm run build
```

预期：TypeScript 和 Vite build 通过。

- [x] **步骤 5：运行差异和文档检查**

运行：

```bash
rg -n "T(O)DO|T[B]D|F[I]XME|待[定]|占[位]|place(holder)|S[u]mmary|G[o]als|Acceptance C[r]iteria" docs
rg --pcre2 -n "(?<!bin/)\\bm[v]n\\b" docs
git diff --check
```

预期：无模板残留；Maven 命令均使用 `/Users/Masons/Ai/Maven/bin/mvn`；差异检查通过。

- [x] **步骤 6：浏览器运行态验证**

启动服务端和桌面端：

```bash
/Users/Masons/Ai/Maven/bin/mvn -Dmaven.repo.local=/Users/Masons/Ai/Maven_Ai_Store -pl server spring-boot:run
cd desktop && npm run dev -- --host 127.0.0.1
```

使用浏览器打开 `http://127.0.0.1:5173/`，验证：

- 运维面板显示「Compose 演示环境」。
- 可以保存 Compose 配置。
- 右侧指标栏显示「Compose 运行态」。
- 触发校验或日志采样后，页面展示成功或失败摘要。
- 浏览器控制台无错误。

- [x] **步骤 7：记录验证结果并提交**

在本计划末尾新增「第九阶段验证记录」，记录红灯、绿灯、全量测试、构建、文档检查和浏览器验证结果。

提交：

```bash
git add docs/development/local-run.md docs/superpowers/plans/2026-06-25-matrixcode-compose-runtime.md
git commit -m "docs: 记录第九阶段 Compose 运行态验证"
```

## 自检记录

- 规格覆盖：Compose 配置、路径约束、生命周期动作、日志采样、工作台聚合、桌面展示、安全边界和验证路径均有对应任务。
- 类型一致性：计划中的服务端类型统一使用 `ComposeEnvironment`、`ComposeOperationRecord`、`ComposeRuntimeView`；前端类型与服务端 JSON 字段保持一致。
- 范围控制：真实 SSH、远程部署、破坏性 Docker 清理、日志流、持久化和组织权限均未纳入第九阶段。
- TDD 顺序：服务端和桌面端均先写红灯测试，再写实现，再跑绿灯。

## 第九阶段验证记录

- 规格提交：`19254b8 docs: 规划第九阶段 Compose 运行态`。
- 计划提交：`c9fc655 docs: 编写第九阶段 Compose 运行态计划`。
- 服务端实现提交：`5b895ca feat(部署): 添加 Compose 演示环境运行态`。
- 桌面端实现提交：`65cfdd9 feat(桌面端): 接入 Compose 演示环境运行态`。
- 运行态加固提交：`cde065b fix(部署): 加固 Compose 命令超时清理`。
- 服务端初始红灯：运行 `/Users/Masons/Ai/Maven/bin/mvn -Dmaven.repo.local=/Users/Masons/Ai/Maven_Ai_Store -q -pl server -Dtest=ComposeEnvironmentServiceTest,DockerComposeRuntimeClientTest test`，编译失败，缺少 `ComposeEnvironmentService`、`ComposeRuntimeClient` 等新类型。
- 工作台初始红灯：运行 `/Users/Masons/Ai/Maven/bin/mvn -Dmaven.repo.local=/Users/Masons/Ai/Maven_Ai_Store -q -pl server -Dtest=WorkbenchServiceTest,WorkbenchControllerTest,WorkbenchLocalExecutionTest test`，失败点为 `WorkbenchService` 构造参数和 `ProjectWorkbench` 新字段尚未接入。
- 桌面端初始红灯：运行 `cd desktop && npm test -- src/api/client.test.ts src/test/App.test.tsx`，4 个断言失败，缺少 Compose API 函数、运维面板和右侧运行态展示。
- 服务端局部绿灯：`ComposeEnvironmentServiceTest,DockerComposeRuntimeClientTest` 通过；`WorkbenchServiceTest,WorkbenchControllerTest,WorkbenchLocalExecutionTest` 通过。
- 桌面端局部绿灯：`cd desktop && npm test -- src/api/client.test.ts src/test/App.test.tsx` 通过，2 个测试文件、48 个测试全绿。
- 真实运行补充红灯：浏览器验证前，真实 Docker 路径卡在 `docker-credential-desktop get`；新增 `DockerComposeRuntimeClientTest.Compose命令超过超时时间会终止并返回失败结果`，修复前 2 秒内未返回，堆栈停在 `readAllBytes`。
- 子进程补充红灯：新增 `DockerComposeRuntimeClientTest.Compose命令超时时会一并终止子进程`，修复前父进程返回超时后子进程仍存活。
- 运行态加固绿灯：`DockerComposeRuntimeClient` 改为后台消费输出、超时终止进程树、等待输出线程收束；上述两个补充测试通过。
- 最终服务端全量：`/Users/Masons/Ai/Maven/bin/mvn -Dmaven.repo.local=/Users/Masons/Ai/Maven_Ai_Store -q -pl server test` 退出码 0；Surefire 汇总 `Tests run: 200, Failures: 0, Errors: 0, Skipped: 0`。
- 最终桌面端全量：`cd desktop && npm test` 通过，2 个测试文件、48 个测试全绿。
- 桌面构建：`cd desktop && npm run build` 通过，`tsc --noEmit` 和 `vite build` 均成功。
- Tauri 入口：`cd desktop && npm run tauri:build -- --help` 退出码 0。
- 真实 API 验证：启动服务端和 Vite 后，授权 `/tmp/matrixcode-compose-demo`、配置部署目标、登记 Compose 环境并触发启动；当前 Docker 环境返回 `FAILED · Docker Compose 命令超时`，工作台返回 1 个 `composeEnvironments` 和 1 个 `composeRuntimeViews`，没有残留 `docker compose` 或 `docker-credential-desktop` 子进程。
- 浏览器验证：打开 `http://127.0.0.1:5173/`，右侧显示「Compose 运行态」和超时摘要；切换运维角色后，运维面板显示「Compose 演示环境」、「保存 Compose 环境」、「校验配置」、「启动演示」、「采集日志」、「停止演示」；控制台 error 数为 0。
- 服务收尾：浏览器自动化标签已清理，服务端和 Vite 开发服务器已停止，端口 8080 与 5173 均已释放。
