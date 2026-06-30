# MatrixCode Agent 失败恢复与工具 trace 设计

## 背景

第 42 到第 44 阶段已经完成 Agent Runtime 正式 MySQL 仓储、编码智能体业务接入和桌面端运行中心展示。当前 Agent 运行只能看到状态、目标、摘要和事件，失败时缺少稳定的失败摘要、是否可重试、重试来源等结构化字段。对多人实时协作的智能体控制台来说，这会影响两个上线能力：一是团队无法快速判断失败是否能恢复；二是后续工具执行器、模型网关和审计中心无法按失败类别沉淀回溯数据。

用户新增要求 MatrixCode 参考 DeepSeek-Reasonix，极致压榨大模型供应商缓存命中机制，降低 token 消耗。这个要求已经在第 51、52 阶段落到模型用量与缓存 trace。本阶段继续沿用同一原则：Agent 失败恢复记录只保存稳定、短摘要、可复用的结构化 trace，不保存完整 prompt、完整异常堆栈或大体积工具输出，避免破坏缓存前缀稳定性和审计安全边界。

## 目标

- Agent 运行主记录新增失败摘要、可重试标记和重试来源运行 ID。
- `AgentRuntimeService` 提供统一失败标记入口，保存 `FAILED` 主记录并追加 `RUN_FAILED` 事件。
- 正式 MySQL `matrixcode_agent_runs` 持久化新增字段，字段必须带详细注释。
- MyBatis-Plus 仓储读写新增字段，保持旧构造器和既有业务调用兼容。
- 桌面端右侧 Agent 运行卡片和运行中心能看到失败摘要与恢复策略。
- 回溯对齐最初需求：角色智能体要可审计、可恢复、可扩展，并保持工作台简洁。

## 推荐方案

推荐扩展 `AgentRunRecord`，把失败恢复元数据作为 Agent 运行主记录的一部分。

- `failureSummary`：短失败摘要，面向运行中心展示和后续聚合，不保存完整堆栈。
- `retryable`：当前失败是否可重试，供前端展示恢复策略，后续可被队列恢复器消费。
- `retryOfRunId`：如果本次运行是重试，记录来源运行 ID；为空表示原始运行。

服务层新增 `markFailed(...)` 方法，统一处理失败归一化：

1. 校验角色、项目、模型、目标。
2. 把失败摘要裁剪为短文本，空值归一为“未提供失败摘要”。
3. 保存 `FAILED` 运行，自动补 `startedAt` 与 `finishedAt`。
4. 追加 `RUN_FAILED` 事件，payload 只包含失败摘要、可重试标记、重试来源和模型，不包含密钥、完整 prompt 或完整异常。

## 非目标

- 不实现自动重试调度器；本阶段只打通恢复所需数据结构与展示。
- 不重写编码智能体执行器、不新增真实 shell 工具执行协议。
- 不引入 Redis、RocketMQ 运行依赖；后续需要跨节点恢复时再接消息和缓存。
- 不保存完整异常堆栈、完整 prompt、API Key、数据库密码、工具输出全文。
- 不改变已有 Agent 运行 API 路径。

## 数据流

1. 业务模块调用 `AgentRuntimeService.markFailed(...)` 或带失败字段的 `saveRun(...)`。
2. 服务层构造 `AgentRunRecord`，写入 `failureSummary/retryable/retryOfRunId`。
3. MyBatis-Plus 仓储 upsert `matrixcode_agent_runs`，保留已有事件时间线。
4. 服务层追加 `RUN_FAILED` 事件到 `matrixcode_agent_run_events`。
5. 桌面端加载最近运行与事件，在右侧指标和运行中心展示失败摘要、恢复策略和失败事件。

## UI 展示

右侧“Agent 运行”卡片保持紧凑：

- 第一行仍显示状态和目标。
- 失败时在列表内展示“恢复策略 可重试”或“恢复策略 不可重试”。
- 有失败摘要时展示“失败摘要 ...”。
- 有重试来源时展示“重试来源 ...”。

运行中心时间线继续使用事件流，失败事件标题为“运行失败”，payload 以短 JSON 或短文本方式展示。

## 验证标准

- `AgentRuntimeServiceTest` 先红后绿覆盖 `markFailed(...)`：
  - 主记录状态为 `FAILED`。
  - `failureSummary`、`retryable`、`retryOfRunId` 正确归一化。
  - `finishedAt` 使用当前时钟。
  - 自动追加 `RUN_FAILED` 事件。
- `MybatisPlusAgentRuntimeRepositoryTest` 先红后绿覆盖新增字段保存和恢复。
- Flyway 新增迁移扩展 `matrixcode_agent_runs`，新增字段带注释并通过迁移注释检查。
- 桌面端 `App.test.tsx` 先红后绿覆盖右侧卡片展示失败摘要和恢复策略。
- 服务端关联测试、服务端全量测试、桌面端全量测试、桌面端构建、真实集成、`git diff --check` 和密钥扫描通过。

## 回溯对齐

- 对齐最初需求：MatrixCode 是多人实时协作的智能体控制台，每个角色智能体都需要可观察、可恢复、可审计的运行链路。
- 对齐用户新增要求：参考 Codex、Claude Code 和 DeepSeek-Reasonix，Agent 运行 trace 要结构化、短摘要、稳定，避免把大 prompt 和大输出写入运行链路。
- 对齐上线约束：正式数据使用 MySQL、MyBatis-Plus 和 Flyway；H2 仅用于测试；新增 DDL 表字段注释完整。
- 对齐工作台要求：右侧只显示运行指标和关键事件，失败恢复信息以高密度列表展示，不新增混乱大区域。
