# MatrixCode 第十六阶段项目工作台状态轻量持久化设计规格

## 背景

第十四阶段已经持久化运行态提醒，第十五阶段已经持久化本地执行代理的工作区、任务、日志和审批审计。当前 MVP 仍存在另一条重启断点：产品草稿、冻结文档、Bug 流转、部署目标、Compose 演示环境、模型网关绑定和项目事件仍主要保存在内存中。服务端重启后，用户能看到运行态提醒和本地执行上下文，但项目交付主线会回到空白状态。

第十六阶段补齐这条断点，让项目工作台的核心业务状态通过本地 JSON 快照跨服务端重启恢复。范围继续保持在本地 MVP，不引入数据库、登录体系或多成员权限。

## 目标

- 持久化文档版本，服务端重启后仍能看到 PRD、验收标准、实现说明、测试报告和验收记录。
- 持久化 Bug 队列与状态流转结果，重启后工作台阶段仍能根据未关闭高危 Bug 进入缺陷处理。
- 持久化部署目标、部署操作、健康检查、Compose 环境和 Compose 操作历史。
- 持久化模型角色绑定和最近模型请求记录，保留模型网关统计摘要。
- 持久化项目事件流、工作流条目和文件操作记录，让重启后的工作台仍具备可追溯上下文。
- 持久化最近 Git diff 摘要，避免重启后本地执行摘要突然丢失最近采集结果。
- 存储路径可配置，默认写入 `.matrixcode/workbench-state.json`。

## 非目标

- 不引入数据库、迁移脚本、ORM、Redis 或外部存储。
- 不实现用户登录、用户级已读、多成员隔离或团队服务器同步。
- 不恢复真实运行中的 Docker Compose 进程或外部部署状态；只恢复最近记录和环境配置。
- 不持久化模型供应商注册表的内置供应商列表；它仍由代码启动时初始化。
- 不持久化 Prompt 缓存估算器的内部命中集合；重启后缓存命中估算重新开始。
- 不新增桌面端页面；桌面端继续消费现有工作台接口字段。

## 方案选择

### 方案一：只持久化运维运行态

该方案沿着第十五阶段后续入口，只保存部署目标、健康检查和 Compose 环境。它能修复运维视角，但产品、开发、测试和模型网关仍会在重启后断档。

### 方案二：正式数据库持久化

该方案更接近长期生产架构，但会牵动依赖、迁移、测试启动方式和本地部署说明。当前项目仍处于 MVP 纵切阶段，优先保持本地离线可验证能力。

### 方案三：项目工作台 JSON 快照（推荐）

该方案延续第十四、十五阶段的轻量快照模式，为当前工作台的核心内存状态提供统一落盘边界。它能最大化保住用户已经完成的项目交付上下文，同时不引入数据库复杂度。后续迁移到正式数据库时，这份快照也可以作为领域分区和迁移清单。

## 持久化范围

第十六阶段快照包含以下数据：

- `version`：当前固定为 `1`。
- `documents`：所有 `DocumentVersion`。
- `bugs`：所有 `ProjectBug`。
- `deploymentTargets`：所有 `DeploymentTarget`。
- `deploymentOperations`：按项目保存最近 `DeploymentOperationRecord`。
- `deploymentHealthChecks`：按项目保存最近 `DeploymentHealthCheck`。
- `composeEnvironments`：所有 `ComposeEnvironment`。
- `composeOperations`：按项目保存最近 `ComposeOperationRecord`。
- `modelBindings`：所有项目的 `RoleModelBinding`。
- `modelRequests`：按项目保存最近 `ModelRequestRecord`。
- `projectEvents`：按项目保存最近 `ProjectEvent`。
- `fileOperations`：按项目保存最近 `FileOperationRecord`。
- `gitDiffSummaries`：按项目保存最近 `GitDiffSummary`。
- `workflowItems`：所有 `WorkflowItem`。
- `workflowEvents`：按工作项保存 `WorkflowEvent`。
- `acceptances`：按项目保存 `WorkbenchService` 的最近验收投影，用于恢复“验收退回开发/测试”和“待产品验收”阶段判断。

各服务继续保留现有历史裁剪规则。第十六阶段不改变工作台接口返回结构。

## 启动恢复规则

服务端启动时从配置路径读取快照：

- 文件不存在：以空工作台状态启动。
- 文件损坏：以空工作台状态启动，不删除原文件。
- 版本不支持：以空工作台状态启动。
- 恢复部署和 Compose 操作记录时不重新执行外部命令。
- 恢复的 Compose 环境状态保持快照中的最近状态；真实容器状态仍由后续手动校验或启动操作更新。
- 恢复的模型请求只用于历史和指标，不重新调用模型。

## 架构

新增 `workbench-state` 方向的轻量存储层：

- `WorkbenchStateSnapshot`：可写入 JSON 的统一快照 record。
- `WorkbenchStateStore`：定义加载快照和分区保存接口。
- `InMemoryWorkbenchStateStore`：单元测试使用。
- `FileWorkbenchStateStore`：Spring 默认实现，负责 JSON 读写和原子替换。
- `WorkbenchStateStorageProperties`：配置 `matrixcode.workbench-state.storage-path`，默认 `.matrixcode/workbench-state.json`。

各服务仍拥有自己的内存结构，并在构造时恢复、变更后保存对应分区：

- `DocumentService` 恢复并保存文档。
- `BugService` 恢复并保存 Bug。
- `DeploymentTargetService`、`DeploymentOperationService`、`DeploymentHealthService`、`ComposeEnvironmentService` 恢复并保存运维状态。
- `RoleModelBindingService` 和 `ModelGatewayService` 恢复并保存模型网关状态。
- `ProjectEventBus` 恢复并保存项目事件；订阅者只存在于当前进程，不落盘。
- `LocalFileService` 和 `LocalGitDiffService` 恢复并保存文件操作和最近 diff。
- `WorkflowService` 恢复并保存工作流状态。
- `WorkbenchService` 恢复并保存最近验收投影。

`FileWorkbenchStateStore` 内部维护当前快照，分区更新时合并其他分区后写入，避免服务之间互相覆盖。

## API 和桌面端

第十六阶段不新增 API。现有接口自然受益：

- `GET /api/projects/{projectId}/workbench`：重启后返回恢复的文档、Bug、部署、Compose、模型网关、事件流和验收阶段。
- `POST /api/projects/{projectId}/roles/...`：新增或变更项目状态时立即写回快照。
- `GET /api/projects/{projectId}/model-gateway/config`：重启后返回恢复的角色绑定和模型请求历史。

桌面端不改组件和样式。只要工作台响应恢复既有字段，角色工作台、事件流、模型网关、部署运行态和本地执行摘要会自动展示恢复结果。

## 错误处理

- 写入失败时接口返回中文错误，不默默丢弃变更。
- 快照损坏时以空状态启动并保留原文件，便于人工排查。
- 单条记录反序列化失败按文件损坏处理，不做部分恢复，避免状态互相矛盾。
- 工作区路径或 Compose 文件不存在时不在恢复阶段删除配置；真正执行 Compose 操作时继续通过现有路径守卫拦截。

## 测试策略

- 文件存储测试：覆盖文件不存在、保存后加载、损坏文件容错和分区更新不互相覆盖。
- 文档和 Bug 测试：服务重建后恢复文档版本、冻结状态、Bug 状态和流转备注。
- 运维测试：服务重建后恢复部署目标、部署操作、健康检查、Compose 环境和 Compose 操作历史。
- 模型网关测试：服务重建后恢复角色绑定、最近请求和指标统计。
- 事件和本地文件测试：服务重建后恢复项目事件、文件操作记录和最近 Git diff。
- 工作流和验收测试：服务重建后恢复工作流事件和最近验收投影。
- Spring 集成测试：使用隔离存储路径启动两次 Spring 上下文，验证工作台主接口跨重启恢复完整状态。
- 全量验证继续包含服务端测试、桌面端测试、桌面端构建、Tauri 入口检查、文档扫描和浏览器重启验证。

## 验收标准

- 服务端重启后，工作台仍能显示第十六阶段前创建的文档、Bug、部署目标、Compose 环境、模型请求和事件流。
- 重启后当前阶段不会因内存丢失回到“需求录入”。
- 重启后模型网关指标仍能基于最近请求计算。
- 重启后最近文件操作和 Git diff 摘要仍出现在本地执行代理摘要中。
- 默认 `.matrixcode/workbench-state.json` 不进入 Git 状态。
- 所有新增和既有测试通过，浏览器重启验证无控制台 error，端口和进程清理干净。

## 后续阶段入口

第十七阶段可以在两个方向中选择：一是引入用户登录和团队成员身份，让提醒已读、审批责任和事件流具备用户维度；二是开始设计正式数据库迁移边界，把第十四至十六阶段的 JSON 快照统一迁移到生产级持久化模型。
