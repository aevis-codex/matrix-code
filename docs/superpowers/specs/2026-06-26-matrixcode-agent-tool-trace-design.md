# MatrixCode Agent 工具调用 trace 设计

## 背景

第 53 阶段已经让 Agent 失败具备失败摘要、可重试标记和重试来源。当前仍缺少一条标准化工具 trace：编码智能体执行准备、测试命令、Git diff、文件写入和交接文档已经会写业务事件，但事件 payload 没有统一字段，后续很难按工具、动作、状态和引用 ID 做审计、筛选、自动重试或成本分析。

## 目标

- 在 `AgentRuntimeService` 中新增统一工具 trace 入口。
- 工具 trace 复用现有 `matrixcode_agent_run_events`，事件类型为 `TOOL_TRACE`。
- 事件 payload 固定包含 `toolName`、`action`、`status`、`referenceId`、`summary` 和 `metadata`。
- 编码智能体执行准备记录测试命令提交和 Git diff 捕获 trace。
- 编码智能体 patch 应用记录文件写入 trace。
- 编码智能体交接回溯记录文档生成 trace。
- 不保存完整 prompt、完整文件内容、完整命令输出、API Key、数据库密码或密钥。

## 推荐方案

推荐在应用层新增 `AgentRuntimeService.appendToolTrace(...)`，由服务层负责事件格式归一化和敏感数据边界。

方法签名：

```java
public AgentRunEventRecord appendToolTrace(
        String runId,
        String projectId,
        String toolName,
        String action,
        String status,
        String referenceId,
        String summary,
        Map<String, ?> metadata
)
```

该方法内部调用 `appendEvent(...)`，固定写入：

- `eventType=TOOL_TRACE`
- `eventTitle=工具调用 trace`
- `eventPayload` 为结构化 JSON。

## 非目标

- 不新增 Agent 工具 trace 专表。
- 不引入 Redis/RocketMQ；当前仍以正式 MySQL 事件表为审计主路径。
- 不实现自动重试调度。
- 不改变本地执行审批策略、路径守卫或编码智能体写文件权限。
- 不把完整文件内容、完整模型 prompt、完整命令日志或大体积输出写入事件。

## 数据流

1. 编码智能体业务服务完成某个工具动作。
2. 业务服务调用 `appendToolTrace(...)`。
3. Agent Runtime 把 trace 归一化成 `TOOL_TRACE` 事件。
4. `matrixcode_agent_run_events` 按既有 MyBatis-Plus 仓储保存事件。
5. 桌面端运行中心沿用事件时间线展示工具 trace。

## 验证标准

- `AgentRuntimeServiceTest` 先红后绿覆盖 `appendToolTrace(...)` payload 格式。
- `CodingAgentTaskServiceTest` 先红后绿覆盖执行准备、patch、handoff 追加 `TOOL_TRACE` 事件。
- 桌面端 `App.test.tsx` 覆盖运行中心展示工具 trace 事件。
- 服务端关联测试、服务端全量测试、桌面端全量测试、桌面端构建、真实集成、静态检查和密钥扫描通过。

## 回溯对齐

- 对齐最初需求：角色智能体的执行过程必须可审计、可恢复、可扩展。
- 对齐 Codex/Claude Code 设计参考：工具动作需要形成短结构化 trace，而不是依赖自然语言日志。
- 对齐 DeepSeek-Reasonix 缓存优化要求：trace 只保存稳定短摘要，避免把动态大内容扩散到 prompt 和运行链路。
- 对齐上线约束：本阶段不新增 DDL；继续复用已注释的正式 Agent 事件表。
