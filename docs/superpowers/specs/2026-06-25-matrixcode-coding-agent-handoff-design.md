# 编码智能体交付回溯设计

## 背景

第 24 阶段完成编码智能体执行准备，第 25 阶段把执行准备放入桌面端开发工作区，第 26 阶段补齐显式确认后的受控 patch 应用。当前缺口是：patch 写入、测试任务和交付结论还没有形成可在文档中心与工作台事件中追踪的交付记录。

## 目标

第 27 阶段实现“编码智能体交付回溯”：开发角色在应用 patch 后，可以把目标、文件、diff 摘要、测试任务状态、测试命令和交付结论记录为文档中心的一份交付回溯文档，并向工作台事件流发布事件。

## 方案对比

### 方案 A：复用文档中心和事件流

- 新增 `DocumentType.CODING_AGENT_HANDOFF`。
- 新增编码智能体 handoff 服务，创建回溯文档并发布 `CODING_AGENT_HANDOFF_RECORDED` 事件。
- 桌面端在 Patch 结果后提供“记录交付回溯”按钮。

优点：复用现有持久化、文档中心和工作台事件，改动小，能快速进入真实可用闭环。

缺点：还不是独立的编码运行仓储，后续审计分析能力有限。

### 方案 B：新增独立编码运行仓储

- 新增编码运行表或快照，保存每次任务、patch、测试和日志。
- 文档中心只展示投影。

优点：长期建模更完整。

缺点：当前正式 MySQL 领域仓储迁移尚未完成，提前引入会扩大范围。

### 方案 C：只在前端展示回溯摘要

- 不新增后端记录，只在当前页面展示 patch 和测试信息。

优点：最小改动。

缺点：刷新后丢失，不满足“可上线、可追踪、知识图谱可回溯”的要求。

## 推荐方案

采用方案 A。它把第 24-26 阶段串成可追踪闭环，同时不提前引入 Redis、RocketMQ 或独立编码运行仓储。后续正式 MySQL 迁移时，可以把 `CODING_AGENT_HANDOFF` 文档迁移到独立编码运行表或继续作为文档中心投影。

## 后端设计

- 新增 `DocumentType.CODING_AGENT_HANDOFF`。
- 新增 `CodingAgentHandoffService`。
- 输入字段：
  - `workspaceId`
  - `actorId`
  - `goal`
  - `relativePath`
  - `patchSummary`
  - `diffSummary`
  - `testTaskId`
  - `testTaskStatus`
  - `testCommand`
  - `deliveryConclusion`
- 输出字段：复用 `DocumentSummary`。
- API：`POST /api/projects/{projectId}/roles/{role}/coding-agent/handoffs`。
- 文档标题固定为“编码智能体交付回溯”。
- 文档内容使用结构化中文文本，包含目标、工作区、变更文件、patch 摘要、diff 摘要、测试任务、测试命令、测试状态和交付结论。
- 服务发布事件：`CODING_AGENT_HANDOFF_RECORDED`，消息为“开发记录了编码智能体交付回溯”。

## 桌面端设计

- API client 新增 `recordCodingAgentHandoff`。
- `DeveloperPanel` 在 patch 应用结果下方展示“交付回溯”表单。
- 表单默认从当前执行准备和 patch 结果推导：
  - 目标来自执行准备目标。
  - 工作区来自 patch 结果。
  - 文件、patch 摘要和 diff 摘要来自 patch 结果。
  - 测试任务、命令和状态来自执行准备的测试命令任务。
- 用户填写交付结论后点击“记录交付回溯”。
- 成功后展示文档 ID，并由 `App` 刷新工作台，使文档中心和事件列表可见。

## 验证标准

- 后端服务测试先红后绿：能创建 `CODING_AGENT_HANDOFF` 文档并发布事件；缺少核心字段时拒绝。
- 后端控制器测试先红后绿：handoff API 返回文档标题和类型。
- 桌面端 API 测试先红后绿：调用正确 URL 和请求体。
- 桌面端 UI 测试先红后绿：patch 后填写交付结论，提交后展示“交付回溯已记录”。
- 全量验证通过：服务端测试、桌面端测试、桌面端构建、diff 检查和密钥扫描。

## 非目标

- 本阶段不新增独立编码运行 MySQL 表。
- 本阶段不接 Redis 或 RocketMQ。
- 本阶段不自动读取测试日志全文；日志摘要后续从本地执行任务和持久化审计链路接入。
- 本阶段不自动把所有 patch 标记为可上线；交付结论仍需要显式填写。
