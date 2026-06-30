# 身份成员 API 与配置入口实现计划

> **面向 AI 代理的工作者：** 必需子技能：使用 superpowers:subagent-driven-development（推荐）或 superpowers:executing-plans 逐任务实现此计划。步骤使用复选框（`- [ ]`）语法来跟踪进度。

**目标：** 将第 38 阶段的身份、成员和用户级审计正式仓储暴露为 API，并接入桌面端配置入口。

**架构：** 后端新增 `ProjectIdentityController` 调用 `ProjectIdentityService`，保持仓储实现不变。前端在现有 `RoleAgentConfigDialog` 中新增顶层配置标签和成员配置页，通过 `desktop/src/api/client.ts` 调用新增身份 API。

**技术栈：** Java 21、Spring Boot MockMvc、React、TypeScript、Vitest、Testing Library、MySQL `matrix_code`、Milvus `matrix_code`。

---

## 文件结构

- 创建：`server/src/main/java/com/matrixcode/identity/api/ProjectIdentityController.java`，身份成员 REST API。
- 创建：`server/src/test/java/com/matrixcode/identity/ProjectIdentityControllerTest.java`，API 行为测试。
- 修改：`server/src/main/java/com/matrixcode/identity/application/ProjectIdentityService.java`，补输入归一化和返回值。
- 修改：`desktop/src/api/client.ts`，新增身份成员类型和请求函数。
- 修改：`desktop/src/api/client.test.ts`，新增 API client 测试。
- 修改：`desktop/src/components/RoleAgentConfigDialog.tsx`，新增成员配置页。
- 修改：`desktop/src/App.tsx`，加载成员与审计、处理添加成员。
- 修改：`desktop/src/test/App.test.tsx`，覆盖成员配置交互。
- 修改：`desktop/src/App.css`，补配置页布局样式。

## 任务 1：后端身份 API

- [x] 步骤 1：编写失败的 MockMvc 测试，断言 `GET /api/projects/demo/identity/members` 返回已有成员，`POST /identity/members` 可添加成员，`GET /identity/users/{userId}/audit-records` 返回审计记录。
- [x] 步骤 2：运行 `mvn -pl server -Dtest=ProjectIdentityControllerTest test`，确认缺少 controller 导致失败。
- [x] 步骤 3：新增 `ProjectIdentityController` 和请求记录类型，调用 `ProjectIdentityService`。
- [x] 步骤 4：运行同一测试确认通过。

## 任务 2：桌面端 API client

- [x] 步骤 1：在 `desktop/src/api/client.test.ts` 编写失败测试，覆盖 `loadProjectMembers`、`addProjectMember`、`loadUserAuditRecords`。
- [x] 步骤 2：运行 `npm --prefix desktop test -- --run src/api/client.test.ts`，确认函数缺失失败。
- [x] 步骤 3：在 `desktop/src/api/client.ts` 新增类型和请求函数。
- [x] 步骤 4：运行同一测试确认通过。

## 任务 3：配置弹窗成员页

- [x] 步骤 1：在 `desktop/src/test/App.test.tsx` 编写失败测试，打开「配置」后切到「成员」，添加开发成员，并显示该用户审计记录。
- [x] 步骤 2：运行 `npm --prefix desktop test -- --run src/test/App.test.tsx`，确认 UI 缺失失败。
- [x] 步骤 3：修改 `RoleAgentConfigDialog.tsx`、`App.tsx` 和 `App.css`，实现成员页、数据加载、添加成员、审计查看。
- [x] 步骤 4：运行同一测试确认通过。

## 任务 4：真实运行与回溯

- [x] 步骤 1：运行服务端相关测试、桌面端相关测试、`git diff --check`、旧 schema 扫描。
- [x] 步骤 2：运行 `./scripts/check-real-runtime.sh` 和 `RealRuntimeIntegrationTest`，确认 MySQL/Milvus 均使用 `matrix_code`。
- [x] 步骤 3：如服务端正在运行，调用真实 API 验证成员写入和审计读取。
- [x] 步骤 4：更新 Obsidian `MatrixCode` 项目图谱和第 39 阶段成果页。
