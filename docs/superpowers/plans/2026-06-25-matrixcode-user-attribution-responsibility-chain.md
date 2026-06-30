# 用户归属与责任链增强实现计划

> **面向 AI 代理的工作者：** 必需子技能：使用 superpowers:subagent-driven-development（推荐）或 superpowers:executing-plans 逐任务实现此计划。步骤使用复选框（`- [ ]`）语法来跟踪进度。

**目标：** 为模型请求和运行态提醒已读补齐用户归属字段，继续推进多人协作责任链。

**架构：** 后端在现有模型请求与运行态提醒领域模型中增加可选用户归属字段，JDBC 正式仓储负责持久化。桌面端 API client 增加可选字段透传，旧调用保持兼容。

**技术栈：** Java 21、Spring Boot MockMvc、Flyway、MySQL `matrix_code`、React、TypeScript、Vitest。

---

## 文件结构

- 修改：`server/src/main/java/com/matrixcode/modelgateway/domain/ModelRequestCommand.java`
- 修改：`server/src/main/java/com/matrixcode/modelgateway/domain/ModelRequestRecord.java`
- 修改：`server/src/main/java/com/matrixcode/modelgateway/api/ModelGatewayController.java`
- 修改：`server/src/main/java/com/matrixcode/modelgateway/application/ModelGatewayService.java`
- 修改：`server/src/main/java/com/matrixcode/persistence/application/JdbcProjectActivityRepository.java`
- 新增：`server/src/main/resources/db/migration/V40_1__extend_user_attribution.sql`
- 修改：`server/src/main/java/com/matrixcode/runtime/domain/RuntimeNotification.java`
- 修改：`server/src/main/java/com/matrixcode/runtime/application/RuntimeNotificationService.java`
- 修改：`server/src/main/java/com/matrixcode/persistence/application/JdbcRuntimeNotificationStore.java`
- 修改：`server/src/main/java/com/matrixcode/workbench/api/WorkbenchController.java`
- 修改：`server/src/main/java/com/matrixcode/workbench/application/WorkbenchService.java`
- 修改：`desktop/src/api/client.ts`
- 修改：`desktop/src/api/client.test.ts`
- 修改测试：`ModelGatewayControllerTest`、`JdbcProjectActivityRepositoryTest`、`RuntimeNotificationServiceTest`、`WorkbenchControllerTest`

## 任务 1：模型请求用户归属

- [x] 步骤 1：在 `ModelGatewayControllerTest` 和 `JdbcProjectActivityRepositoryTest` 写失败测试，断言 `actorUserId` 可通过 API 和 JDBC 正式表读写。
- [x] 步骤 2：运行目标测试，确认字段缺失导致失败。
- [x] 步骤 3：新增 `actorUserId` 到模型请求命令、记录、controller body、服务保存逻辑、JDBC 读写和 Flyway v40.1 迁移。
- [x] 步骤 4：运行目标测试确认通过。

## 任务 2：运行态提醒已读用户归属

- [x] 步骤 1：在 `RuntimeNotificationServiceTest` 和 `WorkbenchControllerTest` 写失败测试，断言单条/批量已读返回 `readByUserId`。
- [x] 步骤 2：运行目标测试，确认字段或请求体缺失导致失败。
- [x] 步骤 3：新增 `readByUserId` 到提醒领域模型、服务已读方法、JDBC store 和 Workbench API。
- [x] 步骤 4：运行目标测试确认通过。

## 任务 3：桌面端 API 透传

- [x] 步骤 1：在 `desktop/src/api/client.test.ts` 写失败测试，断言模型请求、单条提醒已读和批量提醒已读可发送 `actorUserId`。
- [x] 步骤 2：运行目标测试，确认请求体缺失导致失败。
- [x] 步骤 3：修改 `desktop/src/api/client.ts` 的类型和请求函数。
- [x] 步骤 4：运行目标测试确认通过。

## 任务 4：真实运行与图谱回溯

- [x] 步骤 1：运行服务端相关测试、桌面端全量测试、桌面构建、`git diff --check` 和旧 schema/密钥扫描。
- [x] 步骤 2：运行真实预检和 `RealRuntimeIntegrationTest`，确认 MySQL `matrix_code` 可迁移到 v40.1。
- [x] 步骤 3：在真实服务端调用模型请求和提醒已读 API，确认返回用户归属字段。
- [x] 步骤 4：更新 Obsidian `MatrixCode` 项目图谱和第 40 阶段成果页。
