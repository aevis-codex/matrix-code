# MatrixCode 第十六阶段项目工作台状态轻量持久化实现计划

> **面向 AI 代理的工作者：** 必需子技能：使用 superpowers:executing-plans 逐任务实现此计划；每个实现任务先写失败测试，再写实现，再运行定向测试。步骤使用复选框（`- [ ]`）语法跟踪进度。

**目标：** 让项目工作台的核心业务状态通过 JSON 快照跨服务端重启恢复，包括文档、Bug、部署、Compose、模型网关、事件流、文件操作、Git diff、工作流和最近验收投影。

**架构：** 新增 `WorkbenchStateStore` 轻量存储层，Spring 默认写入 `.matrixcode/workbench-state.json`。各内存服务在构造时恢复对应分区，状态变化后保存分区；文件存储内部合并其他分区并原子替换写入。

**技术栈：** Java 21、Spring Boot、Jackson、JUnit 5、AssertJ、MockMvc、React、TypeScript、Vitest、Vite。

---

## 文件结构

- 创建：`server/src/main/java/com/matrixcode/workbench/application/WorkbenchStateSnapshot.java`
- 创建：`server/src/main/java/com/matrixcode/workbench/application/WorkbenchStateStore.java`
- 创建：`server/src/main/java/com/matrixcode/workbench/application/InMemoryWorkbenchStateStore.java`
- 创建：`server/src/main/java/com/matrixcode/workbench/application/WorkbenchStateStorageProperties.java`
- 创建：`server/src/main/java/com/matrixcode/workbench/application/FileWorkbenchStateStore.java`
- 修改：`server/src/main/java/com/matrixcode/document/application/DocumentService.java`
- 修改：`server/src/main/java/com/matrixcode/bug/application/BugService.java`
- 修改：`server/src/main/java/com/matrixcode/deployment/application/DeploymentTargetService.java`
- 修改：`server/src/main/java/com/matrixcode/deployment/application/DeploymentOperationService.java`
- 修改：`server/src/main/java/com/matrixcode/deployment/application/DeploymentHealthService.java`
- 修改：`server/src/main/java/com/matrixcode/deployment/application/ComposeEnvironmentService.java`
- 修改：`server/src/main/java/com/matrixcode/modelgateway/application/RoleModelBindingService.java`
- 修改：`server/src/main/java/com/matrixcode/modelgateway/application/ModelGatewayService.java`
- 修改：`server/src/main/java/com/matrixcode/realtime/application/ProjectEventBus.java`
- 修改：`server/src/main/java/com/matrixcode/localexecution/application/LocalFileService.java`
- 修改：`server/src/main/java/com/matrixcode/localexecution/application/LocalGitDiffService.java`
- 修改：`server/src/main/java/com/matrixcode/workflow/application/WorkflowService.java`
- 修改：`server/src/main/java/com/matrixcode/workbench/application/WorkbenchService.java`
- 创建：`server/src/test/java/com/matrixcode/workbench/FileWorkbenchStateStoreTest.java`
- 修改或新增相关服务持久化测试。
- 创建：`server/src/test/java/com/matrixcode/workbench/WorkbenchStatePersistenceSpringTest.java`
- 修改：`docs/development/local-run.md`

## 任务 1：项目工作台快照存储

**文件：**
- 创建 `WorkbenchStateSnapshot`
- 创建 `WorkbenchStateStore`
- 创建 `InMemoryWorkbenchStateStore`
- 创建 `WorkbenchStateStorageProperties`
- 创建 `FileWorkbenchStateStore`
- 创建 `FileWorkbenchStateStoreTest`

- [x] **步骤 1：编写失败的文件存储测试**

覆盖文件不存在、保存后重新加载、损坏文件容错、分区更新保留其他分区。

- [x] **步骤 2：运行测试验证失败**

```bash
/Users/Masons/Ai/Maven/bin/mvn -Dmaven.repo.local=/Users/Masons/Ai/Maven_Ai_Store -q -pl server -Dtest=FileWorkbenchStateStoreTest test
```

预期：编译失败，提示工作台状态存储类不存在。

- [x] **步骤 3：实现快照和文件存储**

`WorkbenchStateSnapshot` 固定 `version=1`，所有集合字段做空值归一化和防御性复制。`FileWorkbenchStateStore` 参考 `FileLocalExecutionStateStore`，维护当前快照并提供分区保存方法。

- [x] **步骤 4：运行定向测试验证通过**

```bash
/Users/Masons/Ai/Maven/bin/mvn -Dmaven.repo.local=/Users/Masons/Ai/Maven_Ai_Store -q -pl server -Dtest=FileWorkbenchStateStoreTest test
```

- [x] **步骤 5：Commit**

```bash
git add server/src/main/java/com/matrixcode/workbench/application/WorkbenchStateSnapshot.java \
  server/src/main/java/com/matrixcode/workbench/application/WorkbenchStateStore.java \
  server/src/main/java/com/matrixcode/workbench/application/InMemoryWorkbenchStateStore.java \
  server/src/main/java/com/matrixcode/workbench/application/WorkbenchStateStorageProperties.java \
  server/src/main/java/com/matrixcode/workbench/application/FileWorkbenchStateStore.java \
  server/src/test/java/com/matrixcode/workbench/FileWorkbenchStateStoreTest.java
git commit -m "feat(服务端): 添加工作台状态文件存储"
```

## 任务 2：文档、Bug 与验收投影持久化

**文件：**
- 修改 `DocumentService`
- 修改 `BugService`
- 修改 `WorkbenchService`
- 修改 `DocumentServiceTest`
- 修改 `BugServiceTest`
- 修改 `WorkbenchServiceTest`

- [x] **步骤 1：编写失败测试**

新增测试验证服务重建后恢复文档版本、冻结状态、Bug 状态流转和最近验收投影。

- [x] **步骤 2：运行定向测试验证失败**

```bash
/Users/Masons/Ai/Maven/bin/mvn -Dmaven.repo.local=/Users/Masons/Ai/Maven_Ai_Store -q -pl server -Dtest=DocumentServiceTest,BugServiceTest,WorkbenchServiceTest test
```

- [x] **步骤 3：接入存储**

服务构造时从 `WorkbenchStateStore.load()` 恢复对应分区；新增、冻结、更新和流转后保存分区。`WorkbenchService` 将 `AcceptanceProjection` 改为可持久化 record 并保存最近验收投影。

- [x] **步骤 4：运行定向测试验证通过**

```bash
/Users/Masons/Ai/Maven/bin/mvn -Dmaven.repo.local=/Users/Masons/Ai/Maven_Ai_Store -q -pl server -Dtest=DocumentServiceTest,BugServiceTest,WorkbenchServiceTest test
```

- [x] **步骤 5：Commit**

```bash
git add server/src/main/java/com/matrixcode/document/application/DocumentService.java \
  server/src/main/java/com/matrixcode/bug/application/BugService.java \
  server/src/main/java/com/matrixcode/workbench/application/WorkbenchService.java \
  server/src/test/java/com/matrixcode/document/DocumentServiceTest.java \
  server/src/test/java/com/matrixcode/bug/BugServiceTest.java \
  server/src/test/java/com/matrixcode/workbench/WorkbenchServiceTest.java
git commit -m "feat(服务端): 持久化文档 Bug 和验收状态"
```

## 任务 3：运维与 Compose 状态持久化

**文件：**
- 修改 `DeploymentTargetService`
- 修改 `DeploymentOperationService`
- 修改 `DeploymentHealthService`
- 修改 `ComposeEnvironmentService`
- 修改对应测试

- [x] **步骤 1：编写失败测试**

验证服务重建后恢复部署目标、部署操作、健康检查、Compose 环境、Compose 操作历史和 Compose 最近状态。

- [x] **步骤 2：运行定向测试验证失败**

```bash
/Users/Masons/Ai/Maven/bin/mvn -Dmaven.repo.local=/Users/Masons/Ai/Maven_Ai_Store -q -pl server -Dtest=DeploymentTargetServiceTest,DeploymentOperationServiceTest,DeploymentHealthServiceTest,ComposeEnvironmentServiceTest test
```

- [x] **步骤 3：接入存储**

各服务构造时恢复分区；新增目标、记录操作、记录健康检查、配置或执行 Compose 操作后保存。保持现有每项目 20 条历史裁剪规则。

- [x] **步骤 4：运行定向测试验证通过**

```bash
/Users/Masons/Ai/Maven/bin/mvn -Dmaven.repo.local=/Users/Masons/Ai/Maven_Ai_Store -q -pl server -Dtest=DeploymentTargetServiceTest,DeploymentOperationServiceTest,DeploymentHealthServiceTest,ComposeEnvironmentServiceTest test
```

- [x] **步骤 5：Commit**

```bash
git add server/src/main/java/com/matrixcode/deployment/application/DeploymentTargetService.java \
  server/src/main/java/com/matrixcode/deployment/application/DeploymentOperationService.java \
  server/src/main/java/com/matrixcode/deployment/application/DeploymentHealthService.java \
  server/src/main/java/com/matrixcode/deployment/application/ComposeEnvironmentService.java \
  server/src/test/java/com/matrixcode/deployment/DeploymentTargetServiceTest.java \
  server/src/test/java/com/matrixcode/deployment/DeploymentOperationServiceTest.java \
  server/src/test/java/com/matrixcode/deployment/DeploymentHealthServiceTest.java \
  server/src/test/java/com/matrixcode/deployment/ComposeEnvironmentServiceTest.java
git commit -m "feat(服务端): 持久化部署和 Compose 状态"
```

## 任务 4：模型网关、事件流、文件记录、Git diff 与工作流持久化

**文件：**
- 修改 `RoleModelBindingService`
- 修改 `ModelGatewayService`
- 修改 `ProjectEventBus`
- 修改 `LocalFileService`
- 修改 `LocalGitDiffService`
- 修改 `WorkflowService`
- 修改对应测试

- [x] **步骤 1：编写失败测试**

验证服务重建后恢复角色模型绑定、最近模型请求、项目事件、文件操作记录、最近 Git diff、工作流条目和工作流事件。

- [x] **步骤 2：运行定向测试验证失败**

```bash
/Users/Masons/Ai/Maven/bin/mvn -Dmaven.repo.local=/Users/Masons/Ai/Maven_Ai_Store -q -pl server -Dtest=RoleModelBindingServiceTest,ModelGatewayServiceTest,ProjectEventStreamTest,LocalFileServiceTest,LocalGitDiffServiceTest,WorkflowServiceTest test
```

- [x] **步骤 3：接入存储**

各服务恢复并保存自身分区。`ProjectEventBus` 只持久化事件，不持久化当前进程订阅者。模型请求恢复后只用于历史和指标，不重新发布事件。

- [x] **步骤 4：运行定向测试验证通过**

```bash
/Users/Masons/Ai/Maven/bin/mvn -Dmaven.repo.local=/Users/Masons/Ai/Maven_Ai_Store -q -pl server -Dtest=RoleModelBindingServiceTest,ModelGatewayServiceTest,ProjectEventStreamTest,LocalFileServiceTest,LocalGitDiffServiceTest,WorkflowServiceTest test
```

- [x] **步骤 5：Commit**

```bash
git add server/src/main/java/com/matrixcode/modelgateway/application/RoleModelBindingService.java \
  server/src/main/java/com/matrixcode/modelgateway/application/ModelGatewayService.java \
  server/src/main/java/com/matrixcode/realtime/application/ProjectEventBus.java \
  server/src/main/java/com/matrixcode/localexecution/application/LocalFileService.java \
  server/src/main/java/com/matrixcode/localexecution/application/LocalGitDiffService.java \
  server/src/main/java/com/matrixcode/workflow/application/WorkflowService.java \
  server/src/test/java/com/matrixcode/modelgateway/RoleModelBindingServiceTest.java \
  server/src/test/java/com/matrixcode/modelgateway/ModelGatewayServiceTest.java \
  server/src/test/java/com/matrixcode/realtime/ProjectEventStreamTest.java \
  server/src/test/java/com/matrixcode/localexecution/LocalFileServiceTest.java \
  server/src/test/java/com/matrixcode/localexecution/LocalGitDiffServiceTest.java \
  server/src/test/java/com/matrixcode/workflow/WorkflowServiceTest.java
git commit -m "feat(服务端): 持久化模型网关和工作台事件"
```

## 任务 5：Spring 重启集成与文档

**文件：**
- 创建 `WorkbenchStatePersistenceSpringTest`
- 修改可能共享默认 `.matrixcode/workbench-state.json` 的 Spring 测试属性
- 修改 `docs/development/local-run.md`
- 修改本计划执行记录

- [x] **步骤 1：编写 Spring 重启集成测试**

使用隔离 `matrixcode.workbench-state.storage-path` 启动两次 Spring 上下文。第一次通过 API 创建产品草稿、冻结 PRD、提交开发交付物、创建 Bug、配置部署目标、发起模型请求；第二次验证工作台仍恢复这些状态。

- [x] **步骤 2：运行定向测试**

```bash
/Users/Masons/Ai/Maven/bin/mvn -Dmaven.repo.local=/Users/Masons/Ai/Maven_Ai_Store -q -pl server -Dtest=WorkbenchStatePersistenceSpringTest test
```

- [x] **步骤 3：运行服务端全量测试**

```bash
/Users/Masons/Ai/Maven/bin/mvn -Dmaven.repo.local=/Users/Masons/Ai/Maven_Ai_Store -q -pl server test
```

- [x] **步骤 4：运行桌面端验证**

```bash
cd desktop
npm test
npm run build
npm run tauri:build -- --help
```

- [x] **步骤 5：浏览器重启验证**

启动服务端和桌面端，使用隔离的 `workbench-state.json`、`local-execution.json` 和 `runtime-notifications.json`。通过 API 准备完整工作台状态，打开浏览器确认页面展示；停止服务端后重启，再刷新页面确认文档、Bug、部署、Compose、模型请求、事件流和本地执行摘要仍存在。

- [x] **步骤 6：文档和空白检查**

```bash
rg "/Users/Masons/Ai/Maven/bin/mvn" docs -n
rg "mv[n] -q|mv[n] test|mv[n] spring-boot:run" docs -n
git diff --check
```

- [x] **步骤 7：Commit**

```bash
git add server/src/test/java/com/matrixcode/workbench/WorkbenchStatePersistenceSpringTest.java \
  docs/development/local-run.md \
  docs/superpowers/plans/2026-06-25-matrixcode-workbench-state-persistence.md
git commit -m "docs: 记录第十六阶段工作台状态验证"
```

## 执行记录

- 已提交 `8f07e73 docs: 规划第十六阶段工作台状态持久化`，补充第十六阶段规格和执行计划。
- 已提交 `eacb1c0 feat(服务端): 添加工作台状态文件存储`，新增 `WorkbenchStateStore`、内存实现、文件实现、存储配置和 `FileWorkbenchStateStoreTest`。红灯阶段为缺少快照和存储类型；实现后 `FileWorkbenchStateStoreTest` 通过。
- 已提交 `07a17fb feat(服务端): 持久化文档 Bug 和验收状态`，让文档、Bug 和工作台最近验收投影从快照恢复并在变更后保存。`DocumentServiceTest`、`BugServiceTest`、`WorkbenchServiceTest` 完成红绿验证。
- 已提交 `0b60f0d feat(服务端): 持久化部署和 Compose 状态`，让部署目标、部署操作、健康检查、Compose 环境和 Compose 操作历史跨服务重建恢复。`DeploymentTargetServiceTest`、`DeploymentOperationServiceTest`、`DeploymentHealthServiceTest`、`ComposeEnvironmentServiceTest` 完成红绿验证。
- 已提交 `bbf4106 feat(服务端): 持久化模型网关和工作台事件`，让角色模型绑定、模型请求、项目事件、文件操作、Git diff 和工作流状态落入工作台快照。`RoleModelBindingServiceTest`、`ModelGatewayServiceTest`、`ProjectEventStreamTest`、`LocalFileServiceTest`、`LocalGitDiffServiceTest`、`WorkflowServiceTest` 完成红绿验证。
- 已提交 `c618963 test(服务端): 验证工作台状态重启恢复`，新增 `WorkbenchStatePersistenceSpringTest`，用两次 Spring 上下文验证同一 JSON 快照可恢复阶段、冻结文档、关闭 Bug、部署、Compose、模型请求、事件、文件操作和 Git diff。同步隔离相关 MockMvc 测试的本地运行态存储路径。
- 已提交 `de775e0 test(服务端): 隔离工作台状态测试存储`，补齐 `MatrixCodeServerApplicationTest` 和 `ModelGatewayControllerTest` 的三份快照临时路径，确认全量测试不再写入默认 `server/.matrixcode`。
- 服务端最终全量验证命令：`/Users/Masons/Ai/Maven/bin/mvn -Dmaven.repo.local=/Users/Masons/Ai/Maven_Ai_Store -q -pl server test`。结果：39 份 Surefire 报告、243 条测试、0 失败、0 错误、0 跳过。
- 桌面端验证命令：`cd desktop && npm test`、`cd desktop && npm run build`、`cd desktop && npm run tauri:build -- --help`。结果：Vitest 3 个测试文件、69 条测试通过；TypeScript 和 Vite 构建通过；Tauri 构建帮助命令通过。
- 浏览器重启验证使用隔离快照 `/tmp/matrixcode-workbench-state-stage16-browser.json`、`/tmp/matrixcode-local-execution-stage16-browser.json`、`/tmp/matrixcode-runtime-notifications-stage16-browser.json`。通过真实 HTTP API 准备完整工作台状态后，页面展示 `验收退回测试`、`失败原因为空`、`浏览器测试环境`、`matrixcode-demo` 和 `产品验收记录`；重启服务端后 API 与页面继续恢复这些状态，请求失败列表为空。截图保存为 `/tmp/matrixcode-stage16-before.png` 和 `/tmp/matrixcode-stage16-after-restart.png`。
