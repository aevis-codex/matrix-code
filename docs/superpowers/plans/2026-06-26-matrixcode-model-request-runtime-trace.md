# MatrixCode 模型请求运行 Trace 实现计划

> **面向 AI 代理的工作者：** 必需子技能：使用 superpowers:subagent-driven-development（推荐）或 superpowers:executing-plans 逐任务实现此计划。步骤使用复选框（`- [x]`）语法来跟踪进度。

**目标：** 模型网关完成带 `agentRunId` 的请求后，自动把低敏模型请求 trace 写入对应 Agent Runtime 时间线。

**架构：** `AgentRuntimeService` 提供领域化模型 trace 入口，内部复用现有 `appendToolTrace`。`ModelGatewayService` 持有可选 Runtime 服务，只有请求命令显式携带运行 ID 时才写 trace；无 Runtime 仓储时不阻断主流程。

**技术栈：** Java 21、Spring Boot、JUnit 5、AssertJ、MyBatis-Plus、Flyway。

---

## 文件结构

- 修改：`server/src/main/java/com/matrixcode/agentruntime/application/AgentRuntimeService.java`，新增模型请求 trace 便捷入口。
- 修改：`server/src/main/java/com/matrixcode/modelgateway/application/ModelGatewayService.java`，注入可选 Runtime 服务并在请求成功后写 trace。
- 修改：`server/src/test/java/com/matrixcode/agentruntime/AgentRuntimeServiceTest.java`，覆盖模型 trace payload。
- 修改：`server/src/test/java/com/matrixcode/modelgateway/ModelGatewayServiceTest.java`，覆盖模型网关自动追加 Runtime trace。
- 新增：`docs/superpowers/specs/2026-06-26-matrixcode-model-request-runtime-trace-design.md`。
- 新增：`docs/superpowers/plans/2026-06-26-matrixcode-model-request-runtime-trace.md`。
- 新增：`/Users/Masons/Documents/Obsidian/Aevis/MatrixCode/阶段成果/61 模型请求进入Agent运行时间线.md`。

### 任务 1：红灯测试

- [x] **步骤 1：Runtime 模型 trace 测试**

在 `AgentRuntimeServiceTest` 增加 `追加模型请求Trace会写入供应商和缓存摘要`，调用预期新方法：

```java
var event = service.appendModelRequestTrace(
        "run-1",
        "demo",
        "request-1",
        "deepseek",
        "deepseek-chat",
        "PROVIDER",
        "fp-cache-001",
        "stable-platform-prefix-v1",
        "role-prompt-and-dynamic-context"
);
```

断言 `eventType` 是 `TOOL_TRACE`，payload 包含 `model-gateway.model-requests`、`complete-model-request`、`request-1`、`deepseek`、`deepseek-chat`、`PROVIDER`、`fp-cache-001`。

- [x] **步骤 2：模型网关自动 trace 测试**

在 `ModelGatewayServiceTest` 增加 `模型请求携带Agent运行ID时会追加运行时间线Trace`。构造 `RecordingAgentRuntimeRepository` 和 `AgentRuntimeService`，使用带 `run-1` 的 `ModelRequestCommand` 发起请求，断言 Runtime 事件引用 `response.requestId()` 并包含模型与缓存摘要。

- [x] **步骤 3：运行目标测试验证失败**

```bash
/Users/Masons/Ai/Maven/bin/mvn -Dmaven.repo.local=/Users/Masons/Ai/Maven_Ai_Store -q -pl server -Dtest=AgentRuntimeServiceTest,ModelGatewayServiceTest test
```

预期：编译失败，提示 `appendModelRequestTrace` 或新构造器不存在。

### 任务 2：最小后端实现

- [x] **步骤 1：实现 Runtime 便捷入口**

`AgentRuntimeService.appendModelRequestTrace(...)` 调用 `appendToolTrace(...)`，固定：

```java
toolName = "model-gateway.model-requests"
action = "complete-model-request"
status = "COMPLETED"
referenceId = requestId
summary = "模型请求已完成"
```

metadata 保存 `providerId`、`modelName`、`cacheSource`、`stablePrefixHash`、`cachePolicyId`、`volatileSuffixStrategy`。

- [x] **步骤 2：模型网关注入 Runtime 服务**

`ModelGatewayService` 新增 `Optional<AgentRuntimeService> agentRuntimeService` 字段；Spring 构造器接收 `Optional<AgentRuntimeService>`，已有测试构造器继续传 `Optional.empty()`。

- [x] **步骤 3：请求成功后追加 trace**

`request(...)` 成功构造 `ModelResponse`、记录 `ModelRequestRecord` 并保存后，若 `command.agentRunId()` 非空，调用 `appendModelRequestTrace(...)`，传入 response requestId、binding、usage 的缓存字段。

### 任务 3：验证、回溯、提交

- [x] **步骤 1：目标测试到绿灯**

```bash
/Users/Masons/Ai/Maven/bin/mvn -Dmaven.repo.local=/Users/Masons/Ai/Maven_Ai_Store -q -pl server -Dtest=AgentRuntimeServiceTest,ModelGatewayServiceTest test
```

- [x] **步骤 2：全量验证**

```bash
npm --prefix desktop test
npm --prefix desktop run build
/Users/Masons/Ai/Maven/bin/mvn -Dmaven.repo.local=/Users/Masons/Ai/Maven_Ai_Store -q -pl server test
set -a; source .env.local; set +a
/Users/Masons/Ai/Maven/bin/mvn -Dmaven.repo.local=/Users/Masons/Ai/Maven_Ai_Store -q -pl server -Dmatrixcode.real-runtime-test=true -Dtest=RealRuntimeIntegrationTest test
git diff --check
```

- [x] **步骤 3：安全扫描**

扫描 key/password/token/secret 类敏感值，排除 `.env.local`、`.git`、`node_modules`、`target`、`dist`，确认 `secret_matches=0`。

- [x] **步骤 4：第二大脑回溯**

更新项目首页、阶段索引、总览、模块地图、验证与风险、模型网关专题和 Agent Runtime 专题，记录第 61 阶段偏差检查：最初目标是多人实时协作智能体控制台，本阶段增强的是可复盘、可审计、可优化 token 成本的运行链路。

- [x] **步骤 5：提交并推送**

```bash
git add .
git commit -m "feat(Agent运行): 写入模型请求时间线"
git push origin HEAD:master
```

## 2026-06-26 计划状态回填

- 本次回溯确认：该计划对应能力已在后续阶段完成，并已进入 Obsidian 阶段成果、验证与风险、模块地图和真实验证记录。
- 原未勾选项属于历史计划状态未回填，不代表当前功能缺失；已统一回填为完成，后续验收以对应阶段成果页和最新验证命令为准。
