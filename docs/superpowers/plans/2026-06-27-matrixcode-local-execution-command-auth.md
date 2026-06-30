# MatrixCode 本地执行命令接口鉴权实现计划

> **面向 AI 代理的工作者：** 本计划按 TDD 执行。每项完成后必须运行目标测试；阶段结束后必须完整验证、更新 Obsidian 第二大脑、提交并推送。

**目标：** 将可信身份校验扩展到本地执行命令提交、审批和取消接口，避免仅信任请求体 `actorId`。

**范围：** 本阶段只处理已有 `actorId` 的本地执行命令链路；文件读写、工作区授权和 Git diff 后续单独补 actor 归属。

---

## 任务 1：后端红灯测试

- [x] **步骤 1：补控制器鉴权测试**

新增用例：

- 缺少身份头提交本地命令返回 401。
- 身份头和提交命令 `actorId` 不一致返回 403。
- 身份头和审批 `actorId` 不一致返回 403。
- 身份头和取消 `actorId` 不一致返回 403。

- [x] **步骤 2：运行红灯**

```bash
/Users/Masons/Ai/Maven/bin/mvn -Dmaven.repo.local=/Users/Masons/Ai/Maven_Ai_Store -q -pl server -Dtest=LocalExecutionControllerTest test
```

结果：修复前新增 4 条用例按预期失败，分别覆盖提交缺身份 401、提交身份不一致 403、审批身份不一致 403、取消身份不一致前置鉴权。

## 任务 2：后端控制器实现

- [x] **步骤 1：注入 `RequestActorResolver`**

`LocalExecutionController` 新增 resolver 依赖。

- [x] **步骤 2：命令写操作前校验 actor**

提交命令、审批和取消接口在进入服务层前校验请求身份与 `actorId` 一致。

- [x] **步骤 3：运行后端目标测试**

```bash
/Users/Masons/Ai/Maven/bin/mvn -Dmaven.repo.local=/Users/Masons/Ai/Maven_Ai_Store -q -pl server -Dtest=LocalExecutionControllerTest test
```

结果：修复后 `LocalExecutionControllerTest` 通过。

## 任务 3：桌面端命令接口身份头

- [x] **步骤 1：补 API client 测试**

覆盖 `submitLocalCommand(...)`、`decideLocalCommandApproval(...)`、`cancelLocalExecutionTask(...)` 请求携带 actor headers。

- [x] **步骤 2：运行红灯**

```bash
npm --prefix desktop test -- src/api/client.test.ts
```

结果：修复前 4 条用例按预期失败，均指向本地命令写接口缺少 `X-MatrixCode-User-Id` 或 Bearer。

- [x] **步骤 3：实现 headers**

复用 `actorHeaders(input.actorId)`。

- [x] **步骤 4：运行桌面目标测试**

```bash
npm --prefix desktop test -- src/api/client.test.ts
```

结果：修复后 `src/api/client.test.ts` 51 passed。

## 任务 4：完整验证、第二大脑和提交

- [x] **步骤 1：完整验证**

```bash
npm --prefix desktop test
npm --prefix desktop run build
/Users/Masons/Ai/Maven/bin/mvn -Dmaven.repo.local=/Users/Masons/Ai/Maven_Ai_Store -q -pl server test
./scripts/check-real-runtime.sh .env.local
git diff --check
```

结果：桌面端全量 111 passed；桌面端构建通过；服务端全量 `files=88 tests=433 failures=0 errors=0 skipped=7`；真实运行检查通过 MySQL、Milvus、Redis、RocketMQ；`git diff --check` 通过。

- [x] **步骤 2：安全扫描**

```bash
rg -n "<项目敏感信息模式>"
```

结果：全仓敏感扫描和新增行敏感扫描均通过。

- [x] **步骤 3：更新 Obsidian**

新增 `阶段成果/79 本地执行命令接口鉴权.md`，并更新首页、阶段索引、模块地图、验证与风险、本地执行专题和技术约定。

结果：已新增阶段成果，并更新项目首页、项目总览、阶段索引、模块地图、技术栈与运行约定、验证与风险、角色工作台与桌面端、本地执行与审批安全。

- [x] **步骤 4：提交并推送**

```bash
git add ...
git commit -m "feat(auth): 收紧本地执行命令接口鉴权"
git push origin HEAD:master
```

结果：功能提交 `4e1c0a4` 已推送到远程 `master`。
