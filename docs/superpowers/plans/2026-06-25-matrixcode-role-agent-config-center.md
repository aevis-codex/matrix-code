# MatrixCode 第十九阶段角色智能体配置中心实现计划

> **面向 AI 代理的工作者：** 使用 executing-plans 逐任务执行；每个实现任务先写失败测试，再写实现，再运行定向测试。步骤使用复选框（`- [ ]`）跟踪。

**目标：** 为每个角色智能体提供可读写、可持久化、可展示的配置中心，覆盖提示词、模型、工具契约、颜色和字体。

**架构：** 新增 `roleagent` 领域和应用服务，配置先写入 `WorkbenchStateSnapshot`，默认文件模式和 JDBC 快照模式都能恢复。后续阶段再切到第十八阶段创建的 `matrixcode_role_agent_configs` MySQL 正式表。

**技术栈：** Java 21、Spring Boot、JUnit 5、AssertJ、React、TypeScript、Vitest。

## 文件结构

- 创建：`server/src/main/java/com/matrixcode/roleagent/domain/RoleAgentConfig.java`
- 创建：`server/src/main/java/com/matrixcode/roleagent/application/RoleAgentConfigService.java`
- 创建：`server/src/main/java/com/matrixcode/roleagent/api/RoleAgentConfigController.java`
- 修改：`server/src/main/java/com/matrixcode/workbench/application/WorkbenchStateSnapshot.java`
- 修改：`server/src/main/java/com/matrixcode/workbench/application/WorkbenchStateStore.java`
- 修改：`server/src/main/java/com/matrixcode/workbench/application/InMemoryWorkbenchStateStore.java`
- 修改：`server/src/main/java/com/matrixcode/workbench/application/FileWorkbenchStateStore.java`
- 修改：`server/src/main/java/com/matrixcode/persistence/application/JdbcWorkbenchStateStore.java`
- 创建：`server/src/test/java/com/matrixcode/roleagent/RoleAgentConfigServiceTest.java`
- 创建：`server/src/test/java/com/matrixcode/roleagent/RoleAgentConfigControllerTest.java`
- 修改：`server/src/test/java/com/matrixcode/persistence/JdbcPersistenceSpringTest.java`
- 修改：`desktop/src/api/client.ts`
- 修改：`desktop/src/api/client.test.ts`
- 修改：`desktop/src/components/InspectorPanel.tsx`
- 修改：`desktop/src/App.tsx`
- 修改：`desktop/src/test/App.test.tsx`
- 修改：`desktop/src/App.css`
- 修改：`docs/development/local-run.md`
- 修改：`docs/superpowers/plans/2026-06-25-matrixcode-role-agent-config-center.md`

## 任务 1：角色智能体配置领域模型和快照持久化

- [x] **步骤 1：编写失败测试**

创建 `RoleAgentConfigServiceTest`：

- `默认返回四个角色智能体配置`。
- `更新开发智能体配置后可以从新服务实例恢复`。
- `拒绝空系统提示词和非法颜色`。

测试先引用不存在的 `RoleAgentConfigService` 和 `RoleAgentConfig`。

- [x] **步骤 2：运行测试验证失败**

```bash
/Users/Masons/Ai/Maven/bin/mvn -Dmaven.repo.local=/Users/Masons/Ai/Maven_Ai_Store -q -pl server -Dtest=RoleAgentConfigServiceTest test
```

预期：编译失败，提示角色智能体配置类不存在。

- [x] **步骤 3：实现领域模型和 Store 扩展**

实现：

- `RoleAgentConfig` record。
- `WorkbenchStateSnapshot.roleAgentConfigs`。
- `WorkbenchStateStore.saveRoleAgentConfigs(...)`。
- `InMemoryWorkbenchStateStore`、`FileWorkbenchStateStore`、`JdbcWorkbenchStateStore` 更新快照构造逻辑。
- `RoleAgentConfigService` 默认配置、列表、更新和校验。

- [x] **步骤 4：运行定向测试验证通过**

```bash
/Users/Masons/Ai/Maven/bin/mvn -Dmaven.repo.local=/Users/Masons/Ai/Maven_Ai_Store -q -pl server -Dtest=RoleAgentConfigServiceTest test
```

- [x] **步骤 5：Commit**

```bash
git add server/src/main/java/com/matrixcode/roleagent \
  server/src/main/java/com/matrixcode/workbench/application/WorkbenchStateSnapshot.java \
  server/src/main/java/com/matrixcode/workbench/application/WorkbenchStateStore.java \
  server/src/main/java/com/matrixcode/workbench/application/InMemoryWorkbenchStateStore.java \
  server/src/main/java/com/matrixcode/workbench/application/FileWorkbenchStateStore.java \
  server/src/main/java/com/matrixcode/persistence/application/JdbcWorkbenchStateStore.java \
  server/src/test/java/com/matrixcode/roleagent/RoleAgentConfigServiceTest.java
git commit -m "feat(服务端): 添加角色智能体配置服务"
```

## 任务 2：角色智能体配置 API 和 JDBC 快照回归

- [x] **步骤 1：编写失败测试**

创建 `RoleAgentConfigControllerTest`：

- `GET /api/projects/demo/role-agent-configs` 返回四个角色配置。
- `PUT /api/projects/demo/role-agent-configs/developer` 更新开发角色配置。
- 非法角色返回 400。

补充 `JdbcPersistenceSpringTest`：更新开发角色配置后，第二个 Spring 上下文应恢复更新后的系统提示词和主题色。

- [x] **步骤 2：运行测试验证失败**

```bash
/Users/Masons/Ai/Maven/bin/mvn -Dmaven.repo.local=/Users/Masons/Ai/Maven_Ai_Store -q -pl server -Dtest=RoleAgentConfigControllerTest,JdbcPersistenceSpringTest test
```

预期：编译失败或 API 404。

- [x] **步骤 3：实现 Controller 和回归恢复**

实现 `RoleAgentConfigController`，路径：

- `GET /api/projects/{projectId}/role-agent-configs`
- `PUT /api/projects/{projectId}/role-agent-configs/{role}`

- [x] **步骤 4：运行定向测试验证通过**

```bash
/Users/Masons/Ai/Maven/bin/mvn -Dmaven.repo.local=/Users/Masons/Ai/Maven_Ai_Store -q -pl server -Dtest=RoleAgentConfigControllerTest,JdbcPersistenceSpringTest test
```

- [x] **步骤 5：Commit**

```bash
git add server/src/main/java/com/matrixcode/roleagent/api \
  server/src/test/java/com/matrixcode/roleagent/RoleAgentConfigControllerTest.java \
  server/src/test/java/com/matrixcode/persistence/JdbcPersistenceSpringTest.java
git commit -m "feat(服务端): 暴露角色智能体配置接口"
```

## 任务 3：桌面端角色智能体配置入口

- [x] **步骤 1：编写失败测试**

修改 `desktop/src/api/client.test.ts` 和 `desktop/src/test/App.test.tsx`：

- API 客户端能加载和更新角色智能体配置。
- 页面展示“角色智能体配置”。
- 用户编辑开发角色系统提示词和主题色后调用更新接口。

- [x] **步骤 2：运行测试验证失败**

```bash
cd desktop
npm test
```

预期：缺少 API 或 UI 导致失败。

- [x] **步骤 3：实现客户端和 UI**

实现：

- `RoleAgentConfig` 类型、`fetchRoleAgentConfigs`、`updateRoleAgentConfig`。
- `App.tsx` 加载配置并传给检查器。
- 桌面端最初在 `InspectorPanel.tsx` 增加配置区；阶段验收后已回溯修正为页面级「配置」按钮和角色标签弹窗，右侧 Inspector 只保留运行指标与关键事件。
- `App.css` 增加紧凑表单样式。

- [x] **步骤 4：运行桌面测试验证通过**

```bash
cd desktop
npm test
```

- [x] **步骤 5：Commit**

```bash
git add desktop/src/api/client.ts desktop/src/api/client.test.ts \
  desktop/src/components/InspectorPanel.tsx desktop/src/App.tsx \
  desktop/src/test/App.test.tsx desktop/src/App.css
git commit -m "feat(桌面端): 添加角色智能体配置入口"
```

## 任务 4：全量验证、文档和 Obsidian 图谱

- [x] **步骤 1：运行服务端全量测试**

```bash
/Users/Masons/Ai/Maven/bin/mvn -Dmaven.repo.local=/Users/Masons/Ai/Maven_Ai_Store -q -pl server test
```

- [x] **步骤 2：运行桌面端验证**

```bash
cd desktop
npm test
npm run build
npm run tauri:build -- --help
```

- [x] **步骤 3：更新文档**

更新 `docs/development/local-run.md`，说明角色智能体配置中心接口和当前持久化边界。

- [x] **步骤 4：更新 Obsidian 图谱**

新增 `MatrixCode/阶段成果/19 角色智能体配置中心.md`，并更新项目首页、阶段索引、模块地图、技术栈、验证风险和模型网关模块页。

- [x] **步骤 5：检查并提交**

```bash
rg "mv[n] -q|mv[n] test|mv[n] spring-boot:run" docs/superpowers/plans/2026-06-25-matrixcode-role-agent-config-center.md docs/development/local-run.md -n
git diff --check
git add docs/development/local-run.md docs/superpowers/plans/2026-06-25-matrixcode-role-agent-config-center.md
git commit -m "docs: 记录第十九阶段角色智能体配置验证"
```

## 执行记录

- 2026-06-25：任务 1 红灯完成。执行 `/Users/Masons/Ai/Maven/bin/mvn -Dmaven.repo.local=/Users/Masons/Ai/Maven_Ai_Store -q -pl server -Dtest=RoleAgentConfigServiceTest test`，编译失败，缺少 `RoleAgentConfigService`、`RoleAgentConfigCommand`、`RoleAgentConfig`，失败原因符合预期。
- 2026-06-25：任务 1 绿灯完成。新增角色智能体配置领域模型、命令、服务，并扩展 `WorkbenchStateSnapshot`、`WorkbenchStateStore`、内存/文件/JDBC 快照存储；再次执行同一测试命令，退出码 0。
- 2026-06-25：任务 2 红灯完成。执行 `/Users/Masons/Ai/Maven/bin/mvn -Dmaven.repo.local=/Users/Masons/Ai/Maven_Ai_Store -q -pl server -Dtest=RoleAgentConfigControllerTest,JdbcPersistenceSpringTest test`，`RoleAgentConfigControllerTest` 3 条 API 场景均返回 404，失败原因符合缺少控制器预期。
- 2026-06-25：任务 2 绿灯完成。新增 `RoleAgentConfigController`，补充 `JdbcPersistenceSpringTest` 角色智能体配置重启恢复断言；再次执行同一测试命令，退出码 0。
- 2026-06-25：任务 3 红灯完成。执行 `cd desktop && npm test`，新增 API 客户端测试因 `loadRoleAgentConfigs`、`updateRoleAgentConfig` 未实现失败，新增 App 测试因缺少「角色智能体配置」入口失败。
- 2026-06-25：任务 3 绿灯完成。新增角色智能体配置 TypeScript 类型、加载/更新 API、App 配置加载和保存流程。初始实现为 Inspector 配置编辑区；阶段验收后已修正为页面级「配置」按钮和角色标签弹窗。再次执行 `cd desktop && npm test`，3 个测试文件、72 条测试通过。
- 2026-06-25：任务 4 服务端全量验证完成。执行 `/Users/Masons/Ai/Maven/bin/mvn -Dmaven.repo.local=/Users/Masons/Ai/Maven_Ai_Store -q -pl server test`，退出码 0；Surefire 汇总 48 个报告、263 条测试，0 失败，0 错误，0 跳过。
- 2026-06-25：任务 4 桌面端验证完成。执行 `cd desktop && npm test && npm run build && npm run tauri:build -- --help`，退出码 0；Vitest 3 个测试文件、72 条测试通过，`tsc --noEmit` 和 Vite production build 通过，Tauri build help 正常输出。
- 2026-06-25：清理测试残留。服务端全量测试后生成 `server/.matrixcode/*.json`，已删除并确认 `server/.matrixcode` 不存在。
- 2026-06-25：任务 4 文档和 Obsidian 更新完成。更新 `docs/development/local-run.md` 第十九阶段验证说明；新增 Obsidian `MatrixCode/阶段成果/19 角色智能体配置中心.md`，并更新项目首页、项目总览、阶段索引、模块地图、技术栈、验证风险、模型网关和持久化模块页。
- 2026-06-25：任务 4 文档检查完成。文档红旗词扫描无匹配，裸 Maven 命令扫描无匹配，`git diff --check` 退出码 0。
- 2026-06-25：阶段完成回溯完成。复核第 1-18 阶段索引和当前计划：第 11、13、14、15 阶段历史 checklist 已回填；第 19 阶段符合“多人实时协作智能体控制台、每个角色独立智能体配置”的初始需求；基础设施基线保持 MySQL、Milvus、Redis、RocketMQ 按需接入。本阶段剩余偏差已记录为下一阶段任务：模型网关尚未实际消费角色智能体配置，业务仓储尚未切到 `matrixcode_role_agent_configs` 正式表。
- 2026-06-25：HTTP 冒烟验证完成。启动后端和 Vite 后，`curl -I http://127.0.0.1:5173/` 返回 200；`GET /api/projects/demo/role-agent-configs` 返回 4 个角色；`PUT /api/projects/demo/role-agent-configs/developer` 返回 `DEVELOPER / 开发智能体 Pro / #0f766e`。项目未安装 `playwright`，未执行页面自动化；临时服务已停止，8080/5173 无监听，`server/.matrixcode` 已清理。
- 2026-06-25：交互回溯修正完成。根据阶段验收反馈，桌面端将角色智能体配置从右侧 Inspector 移出，改为页面级「配置」按钮打开弹窗，并用产品、开发、测试、运维角色标签切换配置；右侧 Inspector 聚焦运行指标和关键事件。验证命令：`cd desktop && npm test` 通过 72 条测试，`cd desktop && npm run build` 通过；应用内浏览器验证 `http://127.0.0.1:5173/` 存在「配置」按钮，弹窗打开前右侧不包含「角色智能体配置」，「关键事件」区域存在，开发角色配置字段可见。
