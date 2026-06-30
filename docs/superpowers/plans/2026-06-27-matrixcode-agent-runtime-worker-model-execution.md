# MatrixCode Agent Runtime Worker 受控模型执行实现计划

> **面向 AI 代理的工作者：** 必需子技能：使用 superpowers:subagent-driven-development（推荐）或 superpowers:executing-plans 逐任务实现此计划。步骤使用复选框（`- [ ]`）语法来跟踪进度。

**目标：** 让已认领且租约有效的 Agent Run 可以通过 Worker 受控触发一次模型网关请求，并把请求强关联回运行时间线。

**架构：** 新增 `AgentRuntimeWorkerModelExecutionService` 作为 Agent Runtime 与模型网关之间的受控适配层。它先复用第 71 阶段执行计划守卫；通过后调用 `ModelGatewayService.request(...)`，并传入 `agentRunId`，复用模型请求正式表、缓存 trace 和 Agent Runtime `TOOL_TRACE`。HTTP 使用独立控制器，避免改动既有运行控制器构造器。

**技术栈：** Java 21、Spring Boot、JUnit 5、MockMvc、MyBatis-Plus、既有模型网关和 Agent Runtime 服务。

---

## 文件结构

- 创建：`server/src/main/java/com/matrixcode/agentruntime/domain/AgentRuntimeWorkerModelExecutionResult.java`，表示模型执行结果或阻塞结果。
- 创建：`server/src/main/java/com/matrixcode/agentruntime/application/AgentRuntimeWorkerModelExecutionService.java`，执行租约校验、模型请求调用和完成事件写入。
- 创建：`server/src/main/java/com/matrixcode/agentruntime/api/AgentRuntimeWorkerModelExecutionController.java`，暴露 `worker-model-request` HTTP 入口。
- 创建：`server/src/test/java/com/matrixcode/agentruntime/AgentRuntimeWorkerModelExecutionServiceTest.java`，覆盖服务行为。
- 创建：`server/src/test/java/com/matrixcode/agentruntime/AgentRuntimeWorkerModelExecutionControllerTest.java`，覆盖 HTTP 行为。
- 修改：`server/src/test/java/com/matrixcode/persistence/application/RealRuntimeIntegrationTest.java`，真实 MySQL/模型链路覆盖 Worker 模型请求事件。
- 修改：Obsidian `MatrixCode` 项目图谱，记录第 72 阶段和回溯结论。

## 任务 1：服务层受控模型执行

- [x] **步骤 1：编写失败测试**

在 `AgentRuntimeWorkerModelExecutionServiceTest` 新增测试：当前认领人且租约有效时，调用 `executeModelRequest(...)` 返回 `executed=true`，模型请求记录 `agentRunId=runId`，运行事件包含 `TOOL_TRACE` 和 `WORKER_MODEL_REQUEST_COMPLETED`。

- [x] **步骤 2：运行测试验证失败**

运行：

```bash
/Users/Masons/Ai/Maven/bin/mvn -Dmaven.repo.local=/Users/Masons/Ai/Maven_Ai_Store -q -pl server -Dtest=AgentRuntimeWorkerModelExecutionServiceTest test
```

预期：FAIL，缺少结果类型和服务类。

- [x] **步骤 3：实现最少领域对象和服务**

新增 `AgentRuntimeWorkerModelExecutionResult` 和 `AgentRuntimeWorkerModelExecutionService`。服务只调用模型网关，不执行命令、文件写入或 Patch。

- [x] **步骤 4：运行测试通过**

运行同上命令，预期 PASS。

## 任务 2：阻塞边界和 HTTP 入口

- [x] **步骤 1：编写失败测试**

在服务测试新增非认领人阻塞用例：返回 `executed=false`，模型请求数量不增加，事件不包含 `WORKER_MODEL_REQUEST_COMPLETED`。

在控制器测试新增 HTTP 用例：`POST /worker-model-request` 返回执行结果结构。

- [x] **步骤 2：运行测试验证失败**

运行：

```bash
/Users/Masons/Ai/Maven/bin/mvn -Dmaven.repo.local=/Users/Masons/Ai/Maven_Ai_Store -q -pl server -Dtest=AgentRuntimeWorkerModelExecutionServiceTest,AgentRuntimeWorkerModelExecutionControllerTest test
```

预期：FAIL，HTTP 入口尚不存在或阻塞逻辑尚未实现。

- [x] **步骤 3：实现阻塞和控制器**

新增 `AgentRuntimeWorkerModelExecutionController`；服务阻塞时直接返回阻塞结果，不调用模型网关。

- [x] **步骤 4：运行测试通过**

运行同上命令，预期 PASS。

## 任务 3：真实集成和完整验证

- [x] **步骤 1：补真实集成**

在 `RealRuntimeIntegrationTest` 中复用真实 Spring 上下文获取 `AgentRuntimeWorkerModelExecutionService`，对已认领的编码运行执行模型请求，并断言模型请求强关联和 `WORKER_MODEL_REQUEST_COMPLETED` 事件。

- [x] **步骤 2：运行完整验证**

运行：

```bash
/Users/Masons/Ai/Maven/bin/mvn -Dmaven.repo.local=/Users/Masons/Ai/Maven_Ai_Store -q -pl server test
npm --prefix desktop test
npm --prefix desktop run build
./scripts/check-real-runtime.sh .env.local
set -a; source .env.local; set +a; /Users/Masons/Ai/Maven/bin/mvn -Dmaven.repo.local=/Users/Masons/Ai/Maven_Ai_Store -q -pl server -Dmatrixcode.real-runtime-test=true -Dtest=RealRuntimeIntegrationTest test
git diff --check
```

预期：测试、构建、真实运行检查、真实集成和静态检查通过。

实际结果：服务端全量 `files=82 tests=404 failures=0 errors=0 skipped=7`；桌面端 `Tests 102 passed`；桌面端构建通过；真实运行检查通过 MySQL、Milvus、Redis、RocketMQ；启用真实配置的 `RealRuntimeIntegrationTest` 7 条通过；`git diff --check` 无输出。

- [x] **步骤 3：安全扫描**

运行主动运行路径旧地址、旧 collection 和真实密钥扫描，预期无输出。

实际结果：旧地址和旧 collection 扫描无输出；差异敏感信息扫描无输出。

- [x] **步骤 4：更新第二大脑并提交**

新增 `阶段成果/72 Agent Runtime Worker 受控模型执行.md`，更新首页、阶段索引、模块地图、验证与风险、模型网关与上下文门禁、状态持久化与数据库迁移页。

提交：`feat(agent-runtime): 增加 Worker 受控模型执行`

## 自检

- 规格覆盖度：当前认领人执行、非认领人阻塞、HTTP、真实集成、第二大脑和安全扫描均有任务。
- 占位符扫描：计划无“待定”“TODO”“后续实现”占位。
- 类型一致性：统一使用 `AgentRuntimeWorkerModelExecutionResult`、`AgentRuntimeWorkerModelExecutionService`、`executeModelRequest(...)` 和 `WORKER_MODEL_REQUEST_COMPLETED`。
