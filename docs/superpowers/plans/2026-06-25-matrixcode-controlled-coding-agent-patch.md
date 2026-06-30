# 受控编码智能体 Patch 应用实现计划

> **面向 AI 代理的工作者：** 必需子技能：使用 superpowers:subagent-driven-development（推荐）或 superpowers:executing-plans 逐任务实现此计划。步骤使用复选框（`- [ ]`）语法来跟踪进度。

**目标：** 支持开发角色在显式确认后应用小范围整文件文本 patch，并返回 Git diff 摘要。

**架构：** 新增后端 `CodingAgentPatchService` 复用 `LocalFileService` 和 `LocalGitDiffService`；`CodingAgentController` 暴露 patch API；桌面端 API client 和开发面板增加受控 patch 表单。

**技术栈：** Java 21、Spring Boot、JUnit 5、React、TypeScript、Vitest。

---

## 文件结构

- 创建 `server/src/main/java/com/matrixcode/codingagent/domain/CodingAgentPatchResult.java`
- 创建 `server/src/main/java/com/matrixcode/codingagent/application/CodingAgentPatchService.java`
- 修改 `server/src/main/java/com/matrixcode/codingagent/api/CodingAgentController.java`
- 修改 `server/src/test/java/com/matrixcode/codingagent/CodingAgentTaskServiceTest.java`
- 修改 `server/src/test/java/com/matrixcode/codingagent/CodingAgentControllerTest.java`
- 修改 `desktop/src/api/client.ts`
- 修改 `desktop/src/api/client.test.ts`
- 修改 `desktop/src/components/DeveloperPanel.tsx`
- 修改 `desktop/src/test/App.test.tsx`
- 修改 `desktop/src/App.css`

## 任务 1：后端受控 patch 服务

**文件：**
- 测试：`server/src/test/java/com/matrixcode/codingagent/CodingAgentTaskServiceTest.java`
- 创建：`server/src/main/java/com/matrixcode/codingagent/domain/CodingAgentPatchResult.java`
- 创建：`server/src/main/java/com/matrixcode/codingagent/application/CodingAgentPatchService.java`

- [x] **步骤 1：编写失败的服务测试**

新增测试覆盖：未审批拒绝；当前内容不匹配拒绝；审批后写入目标文件并返回 Git diff。

- [x] **步骤 2：运行测试验证失败**

运行：`/Users/Masons/Ai/Maven/bin/mvn -Dmaven.repo.local=/Users/Masons/Ai/Maven_Ai_Store -q -pl server -Dtest=CodingAgentTaskServiceTest test`

预期：FAIL，缺少 `CodingAgentPatchService`。

- [x] **步骤 3：实现最少服务代码**

实现 `apply` 方法，复用 `LocalFileService.read/write` 和 `LocalGitDiffService.capture`。

- [x] **步骤 4：运行服务测试验证通过**

运行同上命令，预期 PASS。

## 任务 2：后端 patch API

**文件：**
- 测试：`server/src/test/java/com/matrixcode/codingagent/CodingAgentControllerTest.java`
- 修改：`server/src/main/java/com/matrixcode/codingagent/api/CodingAgentController.java`

- [x] **步骤 1：编写失败的控制器测试**

新增测试调用 `POST /api/projects/demo/roles/developer/coding-agent/patches`，断言返回 `relativePath`、`bytesWritten` 和 `gitDiffSummary.repository`。

- [x] **步骤 2：运行测试验证失败**

运行：`/Users/Masons/Ai/Maven/bin/mvn -Dmaven.repo.local=/Users/Masons/Ai/Maven_Ai_Store -q -pl server -Dtest=CodingAgentControllerTest test`

预期：FAIL，接口不存在或依赖未注入。

- [x] **步骤 3：实现最少控制器代码**

给 `CodingAgentController` 注入 `CodingAgentPatchService`，新增 `CodingAgentPatchCommand` 和 `/patches` endpoint。

- [x] **步骤 4：运行控制器测试验证通过**

运行同上命令，预期 PASS。

## 任务 3：桌面端 patch 表单

**文件：**
- 修改：`desktop/src/api/client.ts`
- 修改：`desktop/src/api/client.test.ts`
- 修改：`desktop/src/components/DeveloperPanel.tsx`
- 修改：`desktop/src/test/App.test.tsx`
- 修改：`desktop/src/App.css`

- [x] **步骤 1：编写失败的 API 和 UI 测试**

API 测试验证 `applyCodingAgentPatch` 请求路径和 body；UI 测试验证开发角色填写 patch 表单、勾选确认、提交后展示写入结果。

- [x] **步骤 2：运行测试验证失败**

运行：`cd desktop && npm test -- src/api/client.test.ts -t 受控 && npm test -- src/test/App.test.tsx -t Patch`

预期：FAIL，函数和 UI 不存在。

- [x] **步骤 3：实现最少前端代码**

新增 API 类型/函数；`DeveloperPanel` 增加受控 patch 表单；`App.tsx` 注入 `user-dev`。

- [x] **步骤 4：运行桌面端精确测试验证通过**

运行同上命令，预期 PASS。

## 任务 4：全量验证和记录

- [x] **步骤 1：运行服务端测试**

运行：`/Users/Masons/Ai/Maven/bin/mvn -Dmaven.repo.local=/Users/Masons/Ai/Maven_Ai_Store -q -pl server test`

- [x] **步骤 2：运行桌面端测试和构建**

运行：`cd desktop && npm test && npm run build`

- [x] **步骤 3：更新 Obsidian**

新增阶段成果页并更新首页、阶段索引、模块地图、验证风险、桌面端和本地执行安全页。

- [x] **步骤 4：提交**

运行：`git commit -m "feat: 增加受控编码智能体 patch 应用"`

---

## 执行记录

- 服务层红灯：`CodingAgentPatchService` 未实现时，`CodingAgentTaskServiceTest` 无法编译。
- 服务层绿灯：`/Users/Masons/Ai/Maven/bin/mvn -Dmaven.repo.local=/Users/Masons/Ai/Maven_Ai_Store -q -pl server -Dtest=CodingAgentTaskServiceTest test` 通过。
- 控制器红灯：`/patches` endpoint 不存在时，控制器测试返回 404。
- 控制器绿灯：`/Users/Masons/Ai/Maven/bin/mvn -Dmaven.repo.local=/Users/Masons/Ai/Maven_Ai_Store -q -pl server -Dtest=CodingAgentControllerTest test` 通过。
- 桌面端 API 红灯：`applyCodingAgentPatch` 未实现时，API 测试失败。
- 桌面端 UI 红灯：开发面板缺少 `Patch 相对路径` 字段时，UI 测试失败。
- 桌面端绿灯：`npm test -- src/api/client.test.ts -t 受控` 与 `npm test -- src/test/App.test.tsx -t Patch` 通过。
- 全量验证：服务端 `mvn -q -pl server test` 退出码 0；桌面端 `npm test` 为 3 files / 81 tests passed；`npm run build` 退出码 0。
- 提交前门禁：`git diff --check` 无输出；精确密钥扫描未命中真实密钥或数据库密码。
