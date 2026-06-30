# MatrixCode 第 44 阶段设计：Agent Runtime 前端运行中心展示

## 背景

第 42 阶段完成 Agent Runtime 正式 MySQL 仓储，第 43 阶段把编码智能体任务计划、执行准备、受控 patch 和交付回溯写入运行记录与事件时间线。当前缺口是桌面端仍只展示通用项目事件，右侧运行指标和运行中心没有直接消费真实 Agent Runtime 数据。

## 目标

- 桌面端加载工作台时同步加载最近 Agent 运行记录。
- 右侧 Inspector 展示真实 Agent 运行摘要和关键 Agent 事件，不继续堆叠复杂配置。
- 运行中心弹窗展示更完整的 Agent 运行时间线。
- 配置仍通过顶部独立「配置」按钮打开角色标签配置页。
- 保持 file 模式和旧服务兼容：Agent Runtime API 请求失败时不阻断工作台加载。

## 范围

- 新增桌面端 API 类型和请求函数：
  - `AgentRunRecord`
  - `AgentRunEventRecord`
  - `loadAgentRuns`
  - `loadAgentRunEvents`
- `App` ready state 增加 `agentRuns` 和 `agentRunEvents`。
- `refreshWorkbench` 并行加载 Agent Runtime 数据，失败时降级为空数组。
- `InspectorPanel` 展示运行总数、最近状态、最近运行目标和最近 Agent 事件。
- `OperationsCenterDialog` 复用同一批数据展示完整事件列表。

## 非目标

- 不新增表或 Flyway 迁移。
- 不改变编码智能体审批策略、路径守卫和本地执行权限。
- 不接入 Redis/RocketMQ。
- 不实现完整登录认证。

## 数据流

1. 桌面端加载 `/api/projects/{projectId}/workbench`。
2. 根据返回的 `projectId` 调用 `/api/projects/{projectId}/agent-runs`。
3. 对最近运行调用 `/api/projects/{projectId}/agent-runs/{runId}/events`。
4. Inspector 读取 `agentRuns` 和 `agentRunEvents` 渲染摘要与关键事件。
5. 运行中心读取同一份数据渲染完整时间线。

## 验收标准

- API client 测试覆盖两个 Agent Runtime 请求地址。
- App 测试覆盖右侧显示最近 Agent 运行和关键事件。
- App 测试覆盖 Agent Runtime API 失败时工作台仍可进入。
- 桌面端测试和构建通过。
- 文档、Obsidian 图谱和阶段索引完成更新。
