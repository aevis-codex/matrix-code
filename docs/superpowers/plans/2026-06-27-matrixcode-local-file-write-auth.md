# MatrixCode 本地文件写接口鉴权实现计划

> **面向 AI 代理的工作者：** 必需子技能：使用 test-driven-development 执行红绿循环。步骤使用复选框（`- [ ]`）语法来跟踪进度。每项完成后必须运行目标测试；阶段结束后必须完整验证、更新 Obsidian 第二大脑、提交并推送。

**目标：** 将可信身份校验扩展到本地文件写接口。

**架构：** `LocalExecutionController` 已经注入 `RequestActorResolver`，本阶段复用同一个私有 `assertRequestActor(...)`。只在 `writeFile(...)` 前校验请求身份与请求体 `actorId` 一致；桌面端 `writeLocalFile(...)` 复用 `actorHeaders(input.actorId)`。

**技术栈：** Java 21、Spring Boot MockMvc、React/TypeScript、Vitest。

---

## 文件结构

- 修改：`server/src/main/java/com/matrixcode/localexecution/api/LocalExecutionController.java`
  - `writeFile(...)` 增加 `HttpServletRequest` 参数。
  - `FileWriteCommand` 增加 `actorId`。
  - 写入服务前调用 `assertRequestActor(request, command.actorId())`。
- 修改：`server/src/test/java/com/matrixcode/localexecution/LocalExecutionControllerTest.java`
  - 新增写文件缺少身份 401、身份不一致 403、缺 `actorId` 400 测试。
  - 新增或调整成功写文件路径，补齐当前用户身份头。
- 修改：`desktop/src/api/client.ts`
  - `writeLocalFile(...)` 入参增加 `actorId`。
  - 请求 init 增加 `headers: actorHeaders(input.actorId)`。
- 修改：`desktop/src/api/client.test.ts`
  - 覆盖 `writeLocalFile(...)` 携带身份头。
  - 覆盖本地 actor token 透传。

## 任务 1：后端红灯测试

- [x] **步骤 1：补控制器鉴权测试**

新增用例：

- 缺少身份头写文件返回 401。
- 身份头和写文件 `actorId` 不一致返回 403。
- 缺少 `actorId` 写文件返回 400。
- 正常写文件携带身份头和 `actorId` 后返回 200。

- [x] **步骤 2：运行红灯**

```bash
/Users/Masons/Ai/Maven/bin/mvn -Dmaven.repo.local=/Users/Masons/Ai/Maven_Ai_Store -q -pl server -Dtest=LocalExecutionControllerTest test
```

预期：新增鉴权用例在实现前失败，失败原因是写文件接口尚未校验请求身份。

结果：已运行，3 个新增鉴权用例失败，实际状态码为 200，证明写文件接口尚未校验请求身份和 `actorId`。

## 任务 2：后端控制器实现

- [x] **步骤 1：写接口前置校验 actor**

`writeFile(...)` 在进入 `fileService.write(...)` 前调用 `assertRequestActor(request, command.actorId())`。

- [x] **步骤 2：运行后端目标测试**

```bash
/Users/Masons/Ai/Maven/bin/mvn -Dmaven.repo.local=/Users/Masons/Ai/Maven_Ai_Store -q -pl server -Dtest=LocalExecutionControllerTest test
```

预期：目标测试通过。

结果：已通过，`LocalExecutionControllerTest` 退出码 0。

## 任务 3：桌面端写文件身份头

- [x] **步骤 1：补 API client 测试**

覆盖：

- `writeLocalFile(...)` 携带 `X-MatrixCode-User-Id`。
- 本地存在 `matrixcode.actorToken` 时携带 `Authorization: Bearer`。

- [x] **步骤 2：运行红灯**

```bash
npm --prefix desktop test -- src/api/client.test.ts
```

预期：相关用例在实现前失败，失败原因是请求缺少 actor headers。

结果：已运行，`client.test.ts` 2 个用例失败，失败原因均为缺少 `X-MatrixCode-User-Id` 或 `Authorization`。

- [x] **步骤 3：实现 headers**

`writeLocalFile(...)` 复用 `actorHeaders(input.actorId)`。

- [x] **步骤 4：运行桌面目标测试**

```bash
npm --prefix desktop test -- src/api/client.test.ts
```

预期：目标测试通过。

结果：已通过，`client.test.ts` 54/54 通过。

## 任务 4：完整验证、第二大脑和提交

- [x] **步骤 1：完整验证**

```bash
npm --prefix desktop test
npm --prefix desktop run build
/Users/Masons/Ai/Maven/bin/mvn -Dmaven.repo.local=/Users/Masons/Ai/Maven_Ai_Store -q -pl server test
./scripts/check-real-runtime.sh .env.local
git diff --check
```

结果：桌面端全量 114 条测试通过，桌面构建通过；服务端全量 88 个测试文件、442 条测试、0 failures、0 errors、7 skipped；真实 MySQL、Milvus、Redis、RocketMQ 连通性检查通过；`git diff --check` 通过。

- [x] **步骤 2：安全扫描**

```bash
rg -n "<项目敏感信息模式>"
```

结果：全仓敏感扫描和新增行敏感扫描均通过，未发现真实密钥、数据库密码、旧内网地址或旧向量集合名入仓。

- [x] **步骤 3：更新 Obsidian**

新增 `阶段成果/81 本地文件写接口鉴权.md`，并更新首页、项目总览、阶段索引、模块地图、技术栈与运行约定、验证与风险、角色工作台与桌面端、本地执行与审批安全。

- [x] **步骤 4：提交并推送**

```bash
git add ...
git commit -m "feat(auth): 收紧本地文件写接口鉴权"
git push origin HEAD:master
```

结果：主提交 `1a9d680 feat(auth): 收紧本地文件写接口鉴权` 已推送到远程 `master`。
