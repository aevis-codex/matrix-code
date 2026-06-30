# 编码智能体交付回溯实现计划

> **面向 AI 代理的工作者：** 必需子技能：使用 superpowers:subagent-driven-development（推荐）或 superpowers:executing-plans 逐任务实现此计划。步骤使用复选框（`- [ ]`）语法来跟踪进度。

**目标：** 支持开发角色把受控 patch、diff、测试任务和交付结论记录为文档中心中的编码智能体交付回溯。

**架构：** 新增 `CODING_AGENT_HANDOFF` 文档类型和 `CodingAgentHandoffService`，服务创建回溯文档并发布工作台事件；`CodingAgentController` 暴露 handoff API；桌面端 API 和开发面板增加交付回溯表单。

**技术栈：** Java 21、Spring Boot、JUnit 5、React、TypeScript、Vitest。

---

## 文件结构

- 修改 `server/src/main/java/com/matrixcode/document/domain/DocumentType.java`
- 创建 `server/src/main/java/com/matrixcode/codingagent/application/CodingAgentHandoffService.java`
- 修改 `server/src/main/java/com/matrixcode/codingagent/api/CodingAgentController.java`
- 修改 `server/src/test/java/com/matrixcode/codingagent/CodingAgentTaskServiceTest.java`
- 修改 `server/src/test/java/com/matrixcode/codingagent/CodingAgentControllerTest.java`
- 修改 `desktop/src/api/client.ts`
- 修改 `desktop/src/api/client.test.ts`
- 修改 `desktop/src/components/DeveloperPanel.tsx`
- 修改 `desktop/src/App.tsx`
- 修改 `desktop/src/test/App.test.tsx`

## 任务 1：后端交付回溯服务

**文件：**
- 测试：`server/src/test/java/com/matrixcode/codingagent/CodingAgentTaskServiceTest.java`
- 修改：`server/src/main/java/com/matrixcode/document/domain/DocumentType.java`
- 创建：`server/src/main/java/com/matrixcode/codingagent/application/CodingAgentHandoffService.java`

- [x] **步骤 1：编写失败的服务测试**

新增测试：调用 handoff 服务后，返回文档类型为 `CODING_AGENT_HANDOFF`、标题为“编码智能体交付回溯”、内容包含目标、文件、diff、测试任务、测试命令、测试状态和交付结论，并发布 `CODING_AGENT_HANDOFF_RECORDED` 事件。另加一个核心字段为空的拒绝测试。

- [x] **步骤 2：运行服务测试验证失败**

运行：`/Users/Masons/Ai/Maven/bin/mvn -Dmaven.repo.local=/Users/Masons/Ai/Maven_Ai_Store -q -pl server -Dtest=CodingAgentTaskServiceTest test`

预期：FAIL，缺少 `CodingAgentHandoffService` 或 `CODING_AGENT_HANDOFF`。

- [x] **步骤 3：实现最少服务代码**

实现 `CodingAgentHandoffService.record(...)`，复用 `DocumentService.createDraft(...)` 和 `ProjectEventBus.publish(...)`。

- [x] **步骤 4：运行服务测试验证通过**

运行同上命令，预期 PASS。

## 任务 2：后端 handoff API

**文件：**
- 测试：`server/src/test/java/com/matrixcode/codingagent/CodingAgentControllerTest.java`
- 修改：`server/src/main/java/com/matrixcode/codingagent/api/CodingAgentController.java`

- [x] **步骤 1：编写失败的控制器测试**

新增测试调用 `POST /api/projects/demo/roles/developer/coding-agent/handoffs`，断言返回 `type=CODING_AGENT_HANDOFF`、标题为“编码智能体交付回溯”，内容包含 patch 和测试信息。

- [x] **步骤 2：运行控制器测试验证失败**

运行：`/Users/Masons/Ai/Maven/bin/mvn -Dmaven.repo.local=/Users/Masons/Ai/Maven_Ai_Store -q -pl server -Dtest=CodingAgentControllerTest test`

预期：FAIL，接口不存在或依赖未注入。

- [x] **步骤 3：实现最少控制器代码**

给 `CodingAgentController` 注入 `CodingAgentHandoffService`，新增 `CodingAgentHandoffCommand` 和 `/handoffs` endpoint。

- [x] **步骤 4：运行控制器测试验证通过**

运行同上命令，预期 PASS。

## 任务 3：桌面端交付回溯入口

**文件：**
- 修改：`desktop/src/api/client.ts`
- 修改：`desktop/src/api/client.test.ts`
- 修改：`desktop/src/components/DeveloperPanel.tsx`
- 修改：`desktop/src/App.tsx`
- 修改：`desktop/src/test/App.test.tsx`

- [x] **步骤 1：编写失败的 API 和 UI 测试**

API 测试验证 `recordCodingAgentHandoff` 请求路径和 body。UI 测试验证开发角色生成执行准备、应用 patch、填写交付结论并记录回溯后，展示“交付回溯已记录”。

- [x] **步骤 2：运行测试验证失败**

运行：`cd desktop && npm test -- src/api/client.test.ts -t 交付回溯 && npm test -- src/test/App.test.tsx -t 交付回溯`

预期：FAIL，函数和 UI 不存在。

- [x] **步骤 3：实现最少前端代码**

新增 API 类型/函数；`DeveloperPanel` 记录 patch 后的 handoff 表单；`App.tsx` 注入 `user-dev` 并刷新工作台。

- [x] **步骤 4：运行桌面端精确测试验证通过**

运行同上命令，预期 PASS。

## 任务 4：全量验证和图谱记录

- [x] **步骤 1：运行服务端测试**

运行：`/Users/Masons/Ai/Maven/bin/mvn -Dmaven.repo.local=/Users/Masons/Ai/Maven_Ai_Store -q -pl server test`

- [x] **步骤 2：运行桌面端测试和构建**

运行：`cd desktop && npm test && npm run build`

- [x] **步骤 3：提交前检查**

运行：`git diff --check`，并对真实密钥片段做精确扫描，确认无命中。

- [x] **步骤 4：更新 Obsidian**

新增第 27 阶段成果页，并更新首页、总览、阶段索引、模块地图、验证风险、角色工作台和本地执行安全页。

- [x] **步骤 5：提交**

运行：`git commit -m "feat: 增加编码智能体交付回溯"`

---

## 执行记录

- 服务层红灯：缺少 `CodingAgentHandoffService` 和 `DocumentType.CODING_AGENT_HANDOFF` 时，`CodingAgentTaskServiceTest` 编译失败。
- 服务层绿灯：`/Users/Masons/Ai/Maven/bin/mvn -Dmaven.repo.local=/Users/Masons/Ai/Maven_Ai_Store -q -pl server -Dtest=CodingAgentTaskServiceTest test` 通过。
- 控制器红灯：`/handoffs` endpoint 不存在时，`CodingAgentControllerTest` 返回 404。
- 控制器绿灯：`/Users/Masons/Ai/Maven/bin/mvn -Dmaven.repo.local=/Users/Masons/Ai/Maven_Ai_Store -q -pl server -Dtest=CodingAgentControllerTest test` 通过。
- 桌面端 API 红灯：`recordCodingAgentHandoff is not a function`。
- 桌面端 API 绿灯：`npm test -- src/api/client.test.ts -t 交付回溯` 通过。
- 桌面端 UI 红灯：找不到 `交付结论` 字段。
- 桌面端 UI 绿灯：`npm test -- src/test/App.test.tsx -t 交付回溯` 通过。
- 全量验证：服务端 `mvn -q -pl server test` 退出码 0，Surefire 汇总 288 条测试；桌面端 `npm test` 为 3 files / 83 tests passed；`npm run build` 退出码 0。
- 提交前门禁：`git diff --check` 无输出；精确密钥扫描未命中真实密钥或数据库密码。
- Obsidian 图谱：已新增 `MatrixCode/阶段成果/27 编码智能体交付回溯.md`，并更新首页、总览、阶段索引、模块地图、验证风险、工作流与文档中心、角色工作台和本地执行安全页。
