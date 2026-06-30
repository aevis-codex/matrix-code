# MatrixCode Agent Runtime Worker 执行状态机设计

## 背景

第 67 到 70 阶段已经完成失败恢复排队、按运行 ID 认领、项目级认领下一条、租约续期和过期回收。下一步不能直接让 Worker 调用模型或工具；需要先把“这次运行准备执行哪些步骤、哪些步骤需要审批、为什么当前不能执行”固化为低敏、可审计的状态机骨架。

## 推荐方案

采用后端只读/审计型执行计划：

- `AgentRuntimeWorkerService.prepareExecution(...)` 读取已认领的 `RUNNING` 运行。
- 只有当前认领人且租约未过期时，返回可执行计划并追加 `WORKER_EXECUTION_PREPARED` 事件。
- 非当前认领人、非 `RUNNING` 状态、租约缺失或租约过期时，返回阻塞计划，不写执行准备事件。
- 计划步骤按 `agentKind` 生成。`coding` 使用编码智能体协议步骤；其他 Agent 使用通用模型上下文、模型请求、工具审批和交付回溯步骤。
- 本阶段不调用模型、不执行命令、不读写文件、不应用 Patch。

## 验证回溯修正

真实集成验证暴露远程 MySQL 在握手阶段偶发 SQLState 08 通信断连，会导致 raw JDBC 仓储在应用启动时读取历史状态失败。为保持正式运行链路稳定，本阶段同步增加 `JdbcConnectionFactory`，所有 raw JDBC 仓储统一通过该入口创建连接，并仅对通信类异常做最多 3 次短暂重试；配置错误和 SQL 语义错误不重试，避免掩盖真实配置问题。

## API

新增：

```http
POST /api/projects/{projectId}/agent-runs/{runId}/worker-execution-plan?workerId=...
```

返回 `AgentRuntimeWorkerExecutionPlan`：

- `projectId`
- `runId`
- `workerId`
- `plannedAt`
- `executable`
- `blockedReason`
- `steps`

每个 `step` 包含：

- `order`
- `stepKey`
- `title`
- `toolName`
- `status`
- `requiresApproval`
- `summary`

## 边界

- 不新增 Flyway DDL。
- 不引入 Redis/RocketMQ 业务依赖。
- 不保存 prompt 正文、模型响应正文、文件内容、命令输出、密钥或数据库密码。
- 不改变桌面端布局；本阶段先提供后端可验证入口。

## 验收

- 当前认领人可以为运行中的编码 Agent 生成 7 步执行计划，并写入 `WORKER_EXECUTION_PREPARED`。
- 非认领人获取阻塞计划，不写事件。
- 过期租约获取阻塞计划，不写事件。
- HTTP 端点返回同一计划结构。
- 服务端全量、桌面端全量、桌面构建、真实运行检查、真实集成和安全扫描通过。
