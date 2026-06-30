# MatrixCode Agent Runtime Worker 后台调度设计

## 背景

MatrixCode 已具备 Agent Runtime 队列、失败恢复、受控认领、租约治理、执行计划和 Worker 受控模型请求。当前缺口是这些能力仍主要依赖人工或 HTTP 调用触发，生产运行时不会自动消费 `QUEUED` 运行。

第 131 阶段补齐一个可配置后台调度入口，让生产环境可以周期性回收过期租约、认领下一条排队运行，并按配置触发一次受控模型步骤。

## 目标

- 新增 Agent Runtime Worker 调度配置，默认关闭。
- 新增单次调度服务，复用既有 `tick(...)`、租约守卫和受控模型请求服务。
- 新增 Spring 定时适配器，开启配置后周期执行调度。
- 保持安全边界：不自动执行命令、不写文件、不应用 Patch、不自动批准审批项。

## 配置

```yaml
matrixcode:
  agent-runtime:
    worker-scheduler:
      enabled: false
      project-id: demo
      worker-id: matrixcode-worker
      execute-model-request: false
      fixed-delay-ms: 10000
```

对应环境变量：

- `MATRIXCODE_AGENT_WORKER_SCHEDULER_ENABLED`
- `MATRIXCODE_AGENT_WORKER_SCHEDULER_PROJECT_ID`
- `MATRIXCODE_AGENT_WORKER_SCHEDULER_WORKER_ID`
- `MATRIXCODE_AGENT_WORKER_SCHEDULER_EXECUTE_MODEL_REQUEST`
- `MATRIXCODE_AGENT_WORKER_SCHEDULER_FIXED_DELAY_MS`

## 安全边界

- 默认关闭，避免开发环境误消费队列。
- `execute-model-request=false` 时只回收过期租约并认领运行。
- `execute-model-request=true` 时只调用 `AgentRuntimeWorkerModelExecutionService.executeModelRequest(...)`，继续复用当前认领人和租约有效性校验。
- 调度结果和日志只包含项目、Worker、运行 ID、过期数量和模型请求 ID，不包含 prompt、模型回答全文、文件内容、工具输出或密钥。

## 验证标准

- TDD 红灯：`AgentRuntimeWorkerSchedulerServiceTest` 在调度属性和服务缺失时编译失败。
- 绿灯：调度关闭时不认领、不调用模型。
- 绿灯：调度开启时可认领下一条 `QUEUED` 运行，默认不自动执行模型。
- 绿灯：开启模型步骤后可认领并执行受控模型请求，写入 `WORKER_EXECUTION_PREPARED`、`TOOL_TRACE` 和 `WORKER_MODEL_REQUEST_COMPLETED`。

## 回溯对齐

- 对齐最初“多人实时协作智能体控制台”目标：Agent Runtime 不再停留在人工触发队列消费，具备生产后台调度入口。
- 对齐 Codex / Claude Code 类编码 Agent 设计：后台调度只推进受控、可审计步骤，命令、文件写入和 Patch 仍必须经过审批边界。
- 对齐 DeepSeek-Reasonix 成本优化目标：调度模型步骤仍走模型网关和角色缓存作用域策略，不绕过 prompt cache trace。
