# MatrixCode Agent Runtime Worker 执行状态机实现计划

> **面向 AI 代理的工作者：** 必需子技能：使用 superpowers:subagent-driven-development（推荐）或 superpowers:executing-plans 逐任务实现此计划。步骤使用复选框（`- [ ]`）语法来跟踪进度。

**目标：** 为已认领的 Agent 运行生成受控 Worker 执行计划，并写入低敏审计事件。

**架构：** `AgentRuntimeWorkerService` 负责校验运行归属、租约和状态，并生成执行计划。计划对象位于 Agent Runtime domain，不持久化新表；审计继续复用 `matrixcode_agent_run_events`。HTTP 控制器只暴露计划入口，不执行模型或工具。

**技术栈：** Java 21、Spring Boot、JUnit 5、MockMvc、MyBatis-Plus 既有 Agent Runtime 仓储。

---

## 文件结构

- 创建：`server/src/main/java/com/matrixcode/agentruntime/domain/AgentRuntimeWorkerExecutionStep.java`，表示 Worker 执行计划单步。
- 创建：`server/src/main/java/com/matrixcode/agentruntime/domain/AgentRuntimeWorkerExecutionPlan.java`，表示一次运行的执行计划或阻塞结果。
- 修改：`server/src/main/java/com/matrixcode/agentruntime/application/AgentRuntimeService.java`，增加按 ID 读取运行的公开只读入口。
- 修改：`server/src/main/java/com/matrixcode/agentruntime/application/AgentRuntimeWorkerService.java`，增加 `prepareExecution(...)`。
- 修改：`server/src/main/java/com/matrixcode/agentruntime/api/AgentRuntimeController.java`，新增 `worker-execution-plan` 端点。
- 修改：`server/src/test/java/com/matrixcode/agentruntime/AgentRuntimeControllerTest.java`，覆盖 HTTP 行为。
- 修改：`server/src/test/java/com/matrixcode/persistence/application/RealRuntimeIntegrationTest.java`，覆盖真实 MySQL Worker 执行计划事件。
- 创建：`server/src/main/java/com/matrixcode/persistence/application/JdbcConnectionFactory.java`，真实集成暴露 MySQL 握手 EOF 后，集中为 raw JDBC 仓储增加通信异常短暂重试。
- 创建：`server/src/test/java/com/matrixcode/persistence/application/JdbcConnectionFactoryTest.java`，覆盖通信异常重试和配置错误不重试。
- 修改：Obsidian `MatrixCode` 项目图谱，记录第 71 阶段和回溯结论。

## 任务 1：Worker 执行计划领域对象

- [x] **步骤 1：编写失败测试**

在 `AgentRuntimeControllerTest` 新增测试：当前认领人调用 `worker-execution-plan` 返回 `executable=true`、7 个步骤、第一步 `CONTEXT_RECALL`，事件包含 `WORKER_EXECUTION_PREPARED`。

- [x] **步骤 2：运行测试验证失败**

运行：

```bash
/Users/Masons/Ai/Maven/bin/mvn -Dmaven.repo.local=/Users/Masons/Ai/Maven_Ai_Store -q -pl server -Dtest=AgentRuntimeControllerTest test
```

结果：FAIL，3 个新增用例均返回 `404`，原因是端点和计划类型尚不存在。

- [x] **步骤 3：实现最少对象和端点**

新增两个 record，并让控制器调用 Worker 服务。

- [x] **步骤 4：运行测试通过**

运行同上命令，结果 PASS。

## 任务 2：阻塞结果和审计边界

- [x] **步骤 1：编写失败测试**

在 `AgentRuntimeControllerTest` 新增非认领人和过期租约测试：返回 `executable=false`，`blockedReason` 非空，且不写 `WORKER_EXECUTION_PREPARED`。

- [x] **步骤 2：运行测试验证失败**

运行同上命令，结果 FAIL。

- [x] **步骤 3：实现阻塞判断**

`prepareExecution(...)` 校验项目、状态、认领人、租约存在和租约未过期。

- [x] **步骤 4：运行测试通过**

运行同上命令，结果 PASS。

## 任务 3：真实集成和完整验证

- [x] **步骤 1：补真实集成**

在 `RealRuntimeIntegrationTest` 中，在真实 MySQL Agent Runtime 段调用 `prepareExecution(...)` 并断言事件包含 `WORKER_EXECUTION_PREPARED`。

- [x] **步骤 2：运行完整验证**

运行：

```bash
/Users/Masons/Ai/Maven/bin/mvn -Dmaven.repo.local=/Users/Masons/Ai/Maven_Ai_Store -q -pl server test
npm --prefix desktop test
npm --prefix desktop run build
./scripts/check-real-runtime.sh .env.local
set -a; source .env.local; set +a; /Users/Masons/Ai/Maven/bin/mvn -Dmaven.repo.local=/Users/Masons/Ai/Maven_Ai_Store -Dmatrixcode.real-runtime-test=true -q -pl server -Dtest=RealRuntimeIntegrationTest test
git diff --check
```

预期：测试、构建、真实运行检查、真实集成和静态检查通过。

实际结果：

- 服务端全量：`files=80 tests=401 failures=0 errors=0 skipped=7`。
- 桌面端测试：3 个测试文件，102 条测试通过。
- 桌面端构建：`tsc --noEmit && vite build` 通过。
- 真实配置检查：MySQL、Milvus、Redis、RocketMQ 连通。
- 真实集成：`RealRuntimeIntegrationTest` 在 `matrixcode.real-runtime-test=true` 下通过，MySQL schema `matrix_code` 迁移确认在 v69.1，Milvus 写入召回、Redis/RocketMQ、千问 embedding、DeepSeek chat 和 Worker 执行计划事件通过。
- 真实集成第一次暴露远程 MySQL 握手 EOF，已通过 `JdbcConnectionFactory` 对 SQLState 08 通信异常做最多 3 次短暂重试；新增单测验证通信异常重试和非通信异常不重试。

- [x] **步骤 3：安全扫描**

运行旧地址、旧 collection 和真实密钥扫描，预期无输出。

实际结果：主动运行路径旧地址/旧 collection 扫描无输出；差异敏感信息扫描无输出；`git diff --check` 通过。历史计划文档保留过往阶段旧地址记录，不作为当前运行配置来源。

- [x] **步骤 4：更新第二大脑并提交**

新增 `阶段成果/71 Agent Runtime Worker 执行状态机.md`，更新首页、阶段索引、模块地图、验证与风险、模型网关与上下文门禁、状态持久化与数据库迁移页。

提交：`feat(agent-runtime): 增加 Worker 执行状态机`

## 自检

- 规格覆盖度：当前认领人计划、阻塞计划、HTTP、真实集成和第二大脑均有任务。
- 占位符扫描：计划无“待定”“TODO”“后续实现”占位。
- 类型一致性：统一使用 `AgentRuntimeWorkerExecutionPlan`、`AgentRuntimeWorkerExecutionStep`、`prepareExecution(...)` 和 `WORKER_EXECUTION_PREPARED`。
