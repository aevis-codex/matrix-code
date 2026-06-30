# MatrixCode 编码智能体写接口鉴权实现计划

> **面向 AI 代理的工作者：** 必需子技能：使用 test-driven-development 执行红绿循环。步骤使用复选框（`- [ ]`）语法来跟踪进度。每项完成后必须运行目标测试；阶段结束后必须完整验证、更新 Obsidian 第二大脑、提交并推送。

**目标：** 将可信身份校验扩展到编码智能体任务、执行准备、受控 Patch 和交付回溯写接口。

**架构：** `CodingAgentController` 复用 `RequestActorResolver`，在进入任务服务、执行准备服务、Patch 服务和交付回溯服务前校验请求身份与请求体 `actorId` 一致。桌面端 API client 复用现有 `actorHeaders(input.actorId)`，不新增 UI 或服务层行为。

**技术栈：** Java 21、Spring Boot MockMvc、React/TypeScript、Vitest。

---

## 文件结构

- 修改：`server/src/main/java/com/matrixcode/codingagent/api/CodingAgentController.java`
  - 注入 `RequestActorResolver`。
  - 为四个写接口增加 `HttpServletRequest` 参数。
  - 增加本地私有 `assertRequestActor(...)` 校验方法。
- 修改：`server/src/test/java/com/matrixcode/codingagent/CodingAgentControllerTest.java`
  - 新增 401/403 控制器测试。
  - 为既有成功路径补齐当前用户身份头。
- 修改：`desktop/src/api/client.ts`
  - `prepareCodingAgentExecution(...)`、`applyCodingAgentPatch(...)`、`recordCodingAgentHandoff(...)` 增加 actor headers。
- 修改：`desktop/src/api/client.test.ts`
  - 覆盖三类桌面端编码智能体写接口身份头。
  - 覆盖本地 actor token 透传。

## 任务 1：后端红灯测试

- [x] **步骤 1：补控制器鉴权测试**

新增用例：

- 缺少身份头创建编码任务返回 401。
- 身份头和任务规划 `actorId` 不一致返回 403。
- 身份头和执行准备 `actorId` 不一致返回 403。
- 身份头和受控 Patch `actorId` 不一致返回 403。
- 身份头和交付回溯 `actorId` 不一致返回 403。

- [x] **步骤 2：运行红灯**

```bash
/Users/Masons/Ai/Maven/bin/mvn -Dmaven.repo.local=/Users/Masons/Ai/Maven_Ai_Store -q -pl server -Dtest=CodingAgentControllerTest test
```

预期：新增用例在实现前失败，失败原因是接口尚未校验请求身份。

结果：已运行，新增 5 个用例失败，实际状态码为 200，证明写接口尚未校验请求身份。

## 任务 2：后端控制器实现

- [x] **步骤 1：注入 `RequestActorResolver`**

`CodingAgentController` 构造器新增 resolver 依赖，保持 Spring 注入。

- [x] **步骤 2：写接口前置校验 actor**

任务规划、执行准备、受控 Patch、交付回溯接口在进入服务层前调用 `assertRequestActor(request, command.actorId())`。

- [x] **步骤 3：运行后端目标测试**

```bash
/Users/Masons/Ai/Maven/bin/mvn -Dmaven.repo.local=/Users/Masons/Ai/Maven_Ai_Store -q -pl server -Dtest=CodingAgentControllerTest test
```

预期：目标测试通过。

结果：已通过，`CodingAgentControllerTest` 退出码 0。

## 任务 3：桌面端编码智能体写接口身份头

- [x] **步骤 1：补 API client 测试**

覆盖：

- `prepareCodingAgentExecution(...)` 携带 `X-MatrixCode-User-Id`。
- `applyCodingAgentPatch(...)` 携带 `X-MatrixCode-User-Id`。
- `recordCodingAgentHandoff(...)` 携带 `X-MatrixCode-User-Id`。
- 本地存在 `matrixcode.actorToken` 时携带 `Authorization: Bearer`。

- [x] **步骤 2：运行红灯**

```bash
npm --prefix desktop test -- src/api/client.test.ts
```

预期：相关用例在实现前失败，失败原因是请求缺少 actor headers。

结果：已运行，`client.test.ts` 4 个用例失败，失败原因均为缺少 `X-MatrixCode-User-Id` 或 `Authorization`。

- [x] **步骤 3：实现 headers**

三处 API client 复用 `actorHeaders(input.actorId)`。

- [x] **步骤 4：运行桌面目标测试**

```bash
npm --prefix desktop test -- src/api/client.test.ts
```

预期：目标测试通过。

结果：已通过，`client.test.ts` 52/52 通过。

## 任务 4：完整验证、第二大脑和提交

- [x] **步骤 1：完整验证**

```bash
npm --prefix desktop test
npm --prefix desktop run build
/Users/Masons/Ai/Maven/bin/mvn -Dmaven.repo.local=/Users/Masons/Ai/Maven_Ai_Store -q -pl server test
./scripts/check-real-runtime.sh .env.local
git diff --check
```

- [x] **步骤 2：安全扫描**

```bash
rg -n "<项目敏感信息模式>"
```

- [x] **步骤 3：更新 Obsidian**

新增 `阶段成果/80 编码智能体写接口鉴权.md`，并更新首页、项目总览、阶段索引、模块地图、技术栈与运行约定、验证与风险、角色工作台与桌面端、本地执行与审批安全、模型网关与上下文门禁。

- [x] **步骤 4：提交并推送**

```bash
git add ...
git commit -m "feat(auth): 收紧编码智能体写接口鉴权"
git push origin HEAD:master
```

验证结果：

- `npm --prefix desktop test`：3 个测试文件、112 条测试通过。
- `npm --prefix desktop run build`：退出码 0。
- `/Users/Masons/Ai/Maven/bin/mvn -Dmaven.repo.local=/Users/Masons/Ai/Maven_Ai_Store -q -pl server test`：退出码 0，Surefire 汇总 `files=88 tests=438 failures=0 errors=0 skipped=7`。
- `./scripts/check-real-runtime.sh .env.local`：MySQL、Milvus、Redis、RocketMQ 连通性正常。
- `git diff --check`：通过。
- 全仓敏感扫描和新增行敏感扫描：通过。
- Obsidian 第二大脑已新增第 80 阶段成果页，并更新项目首页、项目总览、阶段索引、模块地图、技术栈与运行约定、验证与风险、角色工作台与桌面端、模型网关与上下文门禁、本地执行与审批安全；Obsidian 敏感扫描通过。
- 主提交 `b8bbffe` 已推送到远程 `master`。
