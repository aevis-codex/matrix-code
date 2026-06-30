# MatrixCode Agent 运行记录业务接入设计

## 背景

第 42 阶段已经建立正式的 Agent 运行表和 MyBatis-Plus 仓储，并通过真实 MySQL、Flyway、Milvus、Redis、RocketMQ 连通性验证。当前缺口是编码智能体的真实业务入口还没有写入运行表，导致工作台和审计视图只能看到即时响应，看不到可回放的 Agent 运行时间线。

## 目标

本阶段把编码智能体的核心入口接入 `matrixcode_agent_runs` 和 `matrixcode_agent_run_events`：

- 创建编码任务时记录一次 `QUEUED` 运行和 `TASK_PLANNED` 事件。
- 生成执行准备报告时把同一次运行更新为 `RUNNING`，并追加 `EXECUTION_PREPARED` 事件。
- 应用 Patch 时记录 `PATCH_APPLIED` 事件，并把 Patch 结果关联到运行编号。
- 记录交接文档时追加 `HANDOFF_RECORDED` 事件，形成从计划到交付的闭环。
- 提供项目级运行查询接口，用于工作台右侧运行指标、关键事件和后续运行中心。

## 架构

新增 `AgentRuntimeService` 作为业务层门面。编码智能体服务只依赖该门面，不直接依赖 MyBatis-Plus 仓储。`AgentRuntimeService` 通过可选 `AgentRuntimeRepository` 写正式库；默认文件模式没有仓储时保持无副作用空实现，避免轻量启动依赖数据库。

查询接口放在 `AgentRuntimeController`，路径为：

- `GET /api/projects/{projectId}/agent-runs`
- `GET /api/projects/{projectId}/agent-runs/{runId}/events`

## 数据约定

运行记录使用角色配置中的 `agentKind`、`providerId`、`model`，保证历史记录能追溯当时的 Agent 类型和模型绑定。`actorUserId` 为空时归一为 `system`，便于任务计划这类系统动作通过数据库非空约束。

事件 payload 使用紧凑 JSON 字符串，只记录复盘必需字段：工作区、目标、测试任务、文件路径、Diff 文件数、交接文档标题等。密钥、完整文件内容、完整 Prompt 不进入事件 payload。

## 错误处理

业务写入成功后再记录事件；如果运行记录仓储不可用，业务主流程不失败。正式 `jdbc` 模式下仓储异常应暴露出来，避免审计链路静默丢失。

## 测试策略

- 单元测试覆盖 `AgentRuntimeService` 的写入、查询、无仓储降级行为。
- 编码智能体服务测试覆盖计划、执行准备、Patch、交接事件写入。
- 控制器测试覆盖项目级运行和事件查询接口。
- 运行聚焦测试、全量后端测试、真实运行时测试、前端测试和构建。
