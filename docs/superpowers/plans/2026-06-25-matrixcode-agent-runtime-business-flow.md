# MatrixCode Agent 运行记录业务接入实现计划

> **面向 AI 代理的工作者：** 必需子技能：使用 superpowers:subagent-driven-development（推荐）或 superpowers:executing-plans 逐任务实现此计划。步骤使用复选框（`- [ ]`）语法来跟踪进度。

**目标：** 将第 42 阶段的 Agent 运行正式仓储接入编码智能体真实业务流程，并提供项目级查询接口。

**架构：** 新增 `AgentRuntimeService` 作为可选仓储门面；编码智能体任务、执行准备、Patch、交接服务统一通过它记录运行和事件。新增 `AgentRuntimeController` 提供运行列表和事件时间线查询。

**技术栈：** Java 21、Spring Boot、MyBatis-Plus、JUnit 5、MockMvc、Flyway、MySQL。

---

## 文件结构

- 创建：`server/src/main/java/com/matrixcode/agentruntime/application/AgentRuntimeService.java`，封装运行写入、事件写入、查询和无仓储降级。
- 创建：`server/src/main/java/com/matrixcode/agentruntime/api/AgentRuntimeController.java`，提供项目级查询接口。
- 修改：`server/src/main/java/com/matrixcode/codingagent/application/CodingAgentTaskService.java`，任务规划后记录运行。
- 修改：`server/src/main/java/com/matrixcode/codingagent/application/CodingAgentExecutionService.java`，执行准备后更新运行状态并追加事件。
- 修改：`server/src/main/java/com/matrixcode/codingagent/application/CodingAgentPatchService.java`，Patch 成功后记录事件，并在结果中返回运行编号。
- 修改：`server/src/main/java/com/matrixcode/codingagent/application/CodingAgentHandoffService.java`，交接文档成功后记录事件。
- 修改：`server/src/main/java/com/matrixcode/codingagent/api/CodingAgentController.java`，命令对象支持可选 `actorId`、`runId`。
- 修改：`server/src/main/java/com/matrixcode/codingagent/domain/CodingAgentPatchResult.java`，新增 `runId` 字段。
- 测试：`server/src/test/java/com/matrixcode/agentruntime/AgentRuntimeServiceTest.java`。
- 测试：`server/src/test/java/com/matrixcode/codingagent/CodingAgentTaskServiceTest.java`。
- 测试：`server/src/test/java/com/matrixcode/codingagent/CodingAgentControllerTest.java`。

## 任务 1：AgentRuntimeService 门面

- [x] **步骤 1：编写失败测试**

创建 `AgentRuntimeServiceTest`，验证：

- 记录编码任务会写入 `QUEUED` 运行和 `TASK_PLANNED` 事件。
- 更新执行准备会保留同一个运行 ID 并追加 `EXECUTION_PREPARED`。
- 没有仓储时查询返回空列表，记录方法不抛错。

- [x] **步骤 2：运行测试验证失败**

运行：

```bash
/Users/Masons/Ai/Maven/bin/mvn -Dmaven.repo.local=/Users/Masons/Ai/Maven_Ai_Store -q -pl server -Dtest=AgentRuntimeServiceTest test
```

预期：编译失败或测试失败，原因是 `AgentRuntimeService` 尚不存在。

实际：编译失败，报错 `找不到符号：类 AgentRuntimeService`。

- [x] **步骤 3：实现最少代码**

创建 `AgentRuntimeService`，通过可选 `AgentRuntimeRepository` 记录运行和事件。公开方法写清楚职责、边界和副作用。

- [x] **步骤 4：运行测试验证通过**

运行同一条命令，预期通过。

实际：通过。

## 任务 2：编码智能体业务写入运行记录

- [x] **步骤 1：编写失败测试**

扩展 `CodingAgentTaskServiceTest`，验证任务规划、执行准备、Patch、交接会写入对应运行事件。

- [x] **步骤 2：运行测试验证失败**

运行：

```bash
/Users/Masons/Ai/Maven/bin/mvn -Dmaven.repo.local=/Users/Masons/Ai/Maven_Ai_Store -q -pl server -Dtest=CodingAgentTaskServiceTest test
```

预期：测试失败，原因是现有服务没有注入运行记录门面。

实际：编译失败，报错包括构造器参数不匹配、`CodingAgentPatchResult.runId()` 缺失、交接重载缺失。

- [x] **步骤 3：实现最少代码**

修改编码智能体服务构造函数和业务方法，成功完成业务动作后记录运行和事件。保留旧测试的构造便利方法，避免非数据库模式被强制绑定。

- [x] **步骤 4：运行测试验证通过**

运行同一条命令，预期通过。

实际：通过。

## 任务 3：项目级运行查询接口

- [x] **步骤 1：编写失败测试**

扩展 `CodingAgentControllerTest`，验证：

- 创建任务后 `GET /api/projects/demo/agent-runs` 返回运行。
- `GET /api/projects/demo/agent-runs/{runId}/events` 返回事件时间线。

- [x] **步骤 2：运行测试验证失败**

运行：

```bash
/Users/Masons/Ai/Maven/bin/mvn -Dmaven.repo.local=/Users/Masons/Ai/Maven_Ai_Store -q -pl server -Dtest=CodingAgentControllerTest test
```

预期：404 或断言失败。

实际：先暴露 Spring 构造器选择问题，修正后命中 404；接口实现后测试通过。

- [x] **步骤 3：实现最少代码**

新增 `AgentRuntimeController` 并复用 `AgentRuntimeService` 查询方法。

- [x] **步骤 4：运行测试验证通过**

运行同一条命令，预期通过。

实际：通过。

## 任务 4：回归验证和项目图谱更新

- [x] **步骤 1：运行后端聚焦测试**

```bash
/Users/Masons/Ai/Maven/bin/mvn -Dmaven.repo.local=/Users/Masons/Ai/Maven_Ai_Store -q -pl server -Dtest=AgentRuntimeServiceTest,CodingAgentTaskServiceTest,CodingAgentControllerTest test
```

- [x] **步骤 2：运行后端全量测试**

```bash
/Users/Masons/Ai/Maven/bin/mvn -Dmaven.repo.local=/Users/Masons/Ai/Maven_Ai_Store -q -pl server test
```

- [x] **步骤 3：运行真实运行时测试**

```bash
set -a; source .env.local; set +a; /Users/Masons/Ai/Maven/bin/mvn -Dmaven.repo.local=/Users/Masons/Ai/Maven_Ai_Store -q -pl server -Dmatrixcode.real-runtime-test=true -Dtest=RealRuntimeIntegrationTest test
```

- [x] **步骤 4：运行前端测试和构建**

```bash
npm --prefix desktop test -- --run
npm --prefix desktop run build
```

- [x] **步骤 5：运行静态检查**

```bash
git diff --check
rg -n 'MATRIXCODE_.*(API_KEY|PASSWORD)=.+|sk-[A-Za-z0-9_-]{8,}|[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}' .env.example docker-compose.yml docs scripts server desktop
```

- [x] **步骤 6：更新 Obsidian**

在 `MatrixCode` 项目图谱中新增第 43 阶段成果，并回溯更新首页、总览、阶段索引、模块地图、技术栈和验证风险。

## 验证结果

- 后端聚焦测试：`AgentRuntimeServiceTest,CodingAgentTaskServiceTest,CodingAgentControllerTest` 通过。
- 后端全量测试：`/Users/Masons/Ai/Maven/bin/mvn -Dmaven.repo.local=/Users/Masons/Ai/Maven_Ai_Store -q -pl server test` 通过。
- 真实运行时测试：`RealRuntimeIntegrationTest` 通过，MySQL `matrix_code` 版本为 `42.1`，Flyway 仅提示 MySQL 8.4 支持矩阵警告。
- 前端测试：`npm --prefix desktop test -- --run` 通过，3 个文件、89 个测试通过。
- 前端构建：`npm --prefix desktop run build` 通过。
- 静态检查：`git diff --check` 通过；真实密钥 denylist 扫描无命中；PostgreSQL 扫描无命中；H2 仅出现在测试依赖和测试代码中。
