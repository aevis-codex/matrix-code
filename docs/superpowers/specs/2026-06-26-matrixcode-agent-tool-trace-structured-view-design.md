# MatrixCode Agent 工具 trace 结构化展示设计

## 背景

第 54 阶段已经把编码智能体测试命令、Git diff、受控文件写入和交接文档生成统一写入 `TOOL_TRACE` 事件。当前运行中心仍直接展示 `eventPayload` 原文，用户只能看到一段 JSON，不利于快速判断工具、动作、状态和引用对象。

## 目标

在桌面端运行中心把 `TOOL_TRACE` 展示为结构化条目，保留旧事件兼容和安全边界。

## 推荐方案

采用前端解析 JSON payload 的轻量方案：

- `InspectorPanel` 新增只读解析函数，识别 `event.eventType === "TOOL_TRACE"` 的 JSON payload。
- 解析成功时展示工具、动作、状态、引用 ID 和摘要。
- 解析失败或事件不是 `TOOL_TRACE` 时继续展示原始 payload。
- 不新增后端 DTO，不新增 DDL，不改变 Agent Runtime API。

## 备选方案

- 后端新增 `AgentToolTraceView` DTO：可减少前端解析，但需要改控制器契约和更多测试，本阶段收益不足。
- 新增 trace 专表：利于长期检索，但当前 `matrixcode_agent_run_events` 已能承载低敏 trace，提前拆表会增加迁移成本。

## 安全边界

- 只展示第 54 阶段允许进入 payload 的低敏字段。
- 不展示完整命令输出、完整文件内容、完整 prompt、API Key、数据库密码或异常堆栈全文。
- 对历史非 JSON payload 保持兼容，不抛异常、不阻塞运行中心。

## 验收标准

- 桌面端 `App.test.tsx` 能证明运行中心展示：
  - `工具 local-execution.commands`
  - `动作 submit-test-command`
  - `状态 APPROVAL_PENDING`
  - `引用 task-1`
  - `摘要 测试命令已提交审批`
- 非工具事件仍展示原始摘要。
- 桌面端全量测试、桌面端构建、服务端全量和真实集成继续通过。

## 回溯对齐

- 对齐最初“多人实时协作智能体控制台”诉求：运行中心应能快速读懂 Agent 做过什么。
- 对齐第 54 阶段工具 trace：本阶段只消费已有 trace 结构，不扩大写入范围。
- 对齐可上线标准：不新增后端存储风险，不引入敏感内容展示。
