# MatrixCode Agent Runtime 写接口鉴权实现计划

> **面向 AI 代理的工作者：** 本计划按 TDD 执行。每项完成后必须运行目标测试；阶段结束后必须完整验证、更新 Obsidian 第二大脑、提交并推送。

**目标：** 将签名身份令牌能力扩展到 Agent Runtime 写接口，避免仅信任 query 参数中的操作者。

**架构：** 控制器保留 query 参数，但通过 `RequestActorResolver` 解析请求身份，并要求请求身份与 `actorUserId` / `workerId` 一致；桌面端写操作同步发送身份头和可选 Bearer token。

**技术栈：** Java 21、Spring MVC、JUnit 5、MockMvc、React/Vitest。

---

## 文件结构

- 修改：`server/src/main/java/com/matrixcode/agentruntime/api/AgentRuntimeController.java`
- 修改：`server/src/main/java/com/matrixcode/agentruntime/api/AgentRuntimeWorkerModelExecutionController.java`
- 修改：`server/src/test/java/com/matrixcode/agentruntime/AgentRuntimeControllerTest.java`
- 修改：`server/src/test/java/com/matrixcode/agentruntime/AgentRuntimeWorkerModelExecutionControllerTest.java`
- 修改：`desktop/src/api/client.ts`
- 修改：`desktop/src/api/client.test.ts`

## 任务 1：后端红灯测试

- [x] **步骤 1：补控制器鉴权测试**

新增用例：

- `AgentRuntimeControllerTest`：缺少身份头调用 `POST /retry` 返回 401。
- `AgentRuntimeControllerTest`：身份头和 `actorUserId` 不一致调用 `POST /retry` 返回 403。
- `AgentRuntimeWorkerModelExecutionControllerTest`：身份头和 `workerId` 不一致调用 `POST /worker-model-request` 返回 403。

- [x] **步骤 2：运行红灯**

```bash
/Users/Masons/Ai/Maven/bin/mvn -Dmaven.repo.local=/Users/Masons/Ai/Maven_Ai_Store -q -pl server -Dtest=AgentRuntimeControllerTest,AgentRuntimeWorkerModelExecutionControllerTest test
```

预期：新增测试失败，因为控制器尚未解析请求身份。

## 任务 2：后端控制器实现

- [x] **步骤 1：注入 `RequestActorResolver`**

两个控制器新增 resolver 依赖，保留测试用构造器兼容。

- [x] **步骤 2：写操作前校验 actor**

所有写操作先调用 resolver，解析身份后与 query 参数一致才进入服务层，否则返回 403。

- [x] **步骤 3：运行后端目标测试**

```bash
/Users/Masons/Ai/Maven/bin/mvn -Dmaven.repo.local=/Users/Masons/Ai/Maven_Ai_Store -q -pl server -Dtest=AgentRuntimeControllerTest,AgentRuntimeWorkerModelExecutionControllerTest test
```

## 任务 3：桌面端写操作身份头

- [x] **步骤 1：补 API client 测试**

覆盖 `retryAgentRun(...)`、`claimAgentRun(...)` 和 `claimNextAgentRun(...)` 请求携带当前 actor headers。

- [x] **步骤 2：运行红灯**

```bash
npm --prefix desktop test -- src/api/client.test.ts
```

- [x] **步骤 3：实现 headers**

复用第 76 阶段 `actorHeaders(userId)`。

- [x] **步骤 4：运行桌面目标测试**

```bash
npm --prefix desktop test -- src/api/client.test.ts
```

## 任务 4：完整验证、第二大脑和提交

- [x] **步骤 1：完整验证**

```bash
npm --prefix desktop test
npm --prefix desktop run build
/Users/Masons/Ai/Maven/bin/mvn -Dmaven.repo.local=/Users/Masons/Ai/Maven_Ai_Store -q -pl server -Dtest=AgentRuntimeControllerTest,AgentRuntimeWorkerModelExecutionControllerTest test
git diff --check
```

结果：

- `npm --prefix desktop test`：3 个测试文件、110 条测试通过。
- `npm --prefix desktop run build`：TypeScript 和 Vite 构建通过。
- `/Users/Masons/Ai/Maven/bin/mvn -Dmaven.repo.local=/Users/Masons/Ai/Maven_Ai_Store -q -pl server test`：Surefire 汇总 `tests=429 failures=0 errors=0 skipped=7`。
- `./scripts/check-real-runtime.sh .env.local`：MySQL、Milvus、Redis、RocketMQ 连通通过。
- `git diff --check`：通过。

- [x] **步骤 2：安全扫描**

```bash
git diff -- . ':!desktop/package-lock.json' | rg -n "<项目敏感信息模式>"
```

结果：

- 本阶段新增行敏感扫描通过。
- 全仓敏感扫描通过。
- 扫描过程中发现并清理了历史阶段计划中的旧内网地址、旧向量集合名和真实凭证片段，避免历史文档继续污染上线口径。

- [x] **步骤 3：更新 Obsidian**

新增 `阶段成果/78 Agent Runtime 写接口鉴权.md`，并更新首页、阶段索引、模块地图、验证与风险、模型网关专题和桌面端专题。

- [x] **步骤 4：提交并推送**

```bash
git add ...
git commit -m "feat(auth): 收紧 Agent Runtime 写接口鉴权"
git push origin HEAD:master
```

结果：

- 提交：`749e796 feat(auth): 收紧 Agent Runtime 写接口鉴权`
- 推送：`git push origin HEAD:master` 成功。
