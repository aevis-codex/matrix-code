# MatrixCode Agent 运行与模型请求强关联实现计划

> **面向 AI 代理的工作者：** 必需子技能：使用 superpowers:subagent-driven-development（推荐）或 superpowers:executing-plans 逐任务实现此计划。步骤使用复选框（`- [x]`）语法来跟踪进度。

**目标：** 让模型请求记录可选关联到具体 Agent 运行，并在运行中心只对显式匹配的请求展示强关联审计。

**架构：** 在模型请求领域对象和正式表中增加 `agentRunId`，保留旧构造器兼容历史调用。后端持久化负责写入/恢复低敏关联 ID；前端基于 `agentRunId` 与最新运行 ID 匹配，区分强关联与项目级线索。

**技术栈：** Java 21、Spring Boot、Flyway、MyBatis-Plus、JDBC、React、TypeScript、Vitest。

---

## 文件结构

- 修改：`server/src/main/java/com/matrixcode/modelgateway/domain/ModelRequestCommand.java`，新增可选 `agentRunId`。
- 修改：`server/src/main/java/com/matrixcode/modelgateway/domain/ModelRequestRecord.java`，新增可选 `agentRunId`。
- 修改：`server/src/main/java/com/matrixcode/modelgateway/application/ModelGatewayService.java`，写入命令携带的运行 ID。
- 修改：`server/src/main/java/com/matrixcode/modelgateway/api/ModelGatewayController.java`，请求体透传运行 ID。
- 修改：`server/src/main/java/com/matrixcode/persistence/mybatis/entity/ModelRequestEntity.java`，MyBatis-Plus 实体映射新字段。
- 修改：`server/src/main/java/com/matrixcode/persistence/application/JdbcProjectActivityRepository.java`，JDBC 读写新字段。
- 新增：`server/src/main/resources/db/migration/V60_1__extend_model_request_agent_run_link.sql`。
- 修改：`server/src/test/java/com/matrixcode/modelgateway/ModelGatewayServiceTest.java`。
- 修改：`server/src/test/java/com/matrixcode/persistence/JdbcProjectActivityRepositoryTest.java`。
- 修改：`server/src/test/java/com/matrixcode/persistence/MybatisPlusProjectActivityRepositoryTest.java`。
- 修改：`desktop/src/api/client.ts`。
- 修改：`desktop/src/test/App.test.tsx`。
- 修改：`desktop/src/components/InspectorPanel.tsx`。
- 新增：`docs/superpowers/specs/2026-06-26-matrixcode-agent-model-request-link-design.md`。
- 新增：`docs/superpowers/plans/2026-06-26-matrixcode-agent-model-request-link.md`。
- 新增：`/Users/Masons/Documents/Obsidian/Aevis/MatrixCode/阶段成果/60 Agent 运行与模型请求强关联.md`。

### 任务 1：红灯测试

- [x] **步骤 1：网关记录测试**

在 `ModelGatewayServiceTest` 增加测试：使用带 `agentRunId` 的 `ModelRequestCommand` 发起请求，断言 `service.recentRequests("demo").getLast().agentRunId()` 等于 `run-1`。

- [x] **步骤 2：JDBC 持久化测试**

在 `JdbcProjectActivityRepositoryTest` 的 fixture 中写入 `agentRunId`，断言保存恢复后的记录完全相等。

- [x] **步骤 3：MyBatis-Plus 持久化测试**

在 `MybatisPlusProjectActivityRepositoryTest` 的 fixture 中写入 `agentRunId`，断言保存恢复后的记录完全相等。

- [x] **步骤 4：前端审计测试**

在 `App.test.tsx` 的模型请求 fixture 中加入 `agentRunId: 'run-1'`，断言审计详情显示 `模型请求 request-1 · 已关联 run-1 · PROVIDER · prefix fp-cache-001`。

- [x] **步骤 5：运行目标测试验证失败**

```bash
/Users/Masons/Ai/Maven/bin/mvn -Dmaven.repo.local=/Users/Masons/Ai/Maven_Ai_Store -q -pl server -Dtest=ModelGatewayServiceTest,JdbcProjectActivityRepositoryTest,MybatisPlusProjectActivityRepositoryTest test
npm --prefix desktop test -- --run src/test/App.test.tsx
```

预期：后端因缺少 `agentRunId` 构造器/访问器失败；前端因未识别强关联文案失败。

### 任务 2：后端实现

- [x] **步骤 1：扩展领域命令和记录**

为 `ModelRequestCommand` 和 `ModelRequestRecord` 增加 `agentRunId` 字段、兼容构造器和空值规范化。

- [x] **步骤 2：模型网关写入运行 ID**

`ModelGatewayService` 创建 `ModelRequestRecord` 时传入 `command.agentRunId()`。

- [x] **步骤 3：API 透传**

`ModelGatewayController.ModelRequestCommandBody` 增加 `agentRunId`，创建命令时透传。

- [x] **步骤 4：正式表迁移**

新增 `V60_1__extend_model_request_agent_run_link.sql`，为 `matrixcode_model_requests` 增加带注释的 `agent_run_id` 和索引。

- [x] **步骤 5：持久化映射**

更新 `ModelRequestEntity`、`JdbcProjectActivityRepository` 的 select/insert/toDomain/fromDomain。

### 任务 3：前端实现

- [x] **步骤 1：类型扩展**

`desktop/src/api/client.ts` 的 `ModelRequestRecord` 增加可选 `agentRunId`。

- [x] **步骤 2：审计详情区分强关联和线索**

`OperationsDetailPanel` 派生 `linkedAgentRequest`，审计详情匹配时展示“已关联 run-1”，时间线仍展示最近请求线索。

### 任务 4：验证、文档、提交

- [x] **步骤 1：运行目标测试到绿灯**

```bash
/Users/Masons/Ai/Maven/bin/mvn -Dmaven.repo.local=/Users/Masons/Ai/Maven_Ai_Store -q -pl server -Dtest=ModelGatewayServiceTest,JdbcProjectActivityRepositoryTest,MybatisPlusProjectActivityRepositoryTest test
npm --prefix desktop test -- --run src/test/App.test.tsx
```

- [x] **步骤 2：运行全量验证**

```bash
npm --prefix desktop test
npm --prefix desktop run build
/Users/Masons/Ai/Maven/bin/mvn -Dmaven.repo.local=/Users/Masons/Ai/Maven_Ai_Store -q -pl server test
set -a; source .env.local; set +a
/Users/Masons/Ai/Maven/bin/mvn -Dmaven.repo.local=/Users/Masons/Ai/Maven_Ai_Store -q -pl server -Dmatrixcode.real-runtime-test=true -Dtest=RealRuntimeIntegrationTest test
git diff --check
```

- [x] **步骤 3：安全扫描**

精确扫描真实 API Key、数据库密码和中间件地址，排除 `.env.local` 自身，确认入仓文件 `secret_matches=0`。

- [x] **步骤 4：第二大脑回溯**

更新 Obsidian 首页、阶段索引、总览、模块地图、验证与风险、模型网关专题，并记录第 60 阶段偏差检查。

- [x] **步骤 5：提交并推送**

```bash
git add .
git commit -m "feat(Agent运行): 关联模型请求"
git push origin HEAD:master
```

## 2026-06-26 计划状态回填

- 本次回溯确认：该计划对应能力已在后续阶段完成，并已进入 Obsidian 阶段成果、验证与风险、模块地图和真实验证记录。
- 原未勾选项属于历史计划状态未回填，不代表当前功能缺失；已统一回填为完成，后续验收以对应阶段成果页和最新验证命令为准。
