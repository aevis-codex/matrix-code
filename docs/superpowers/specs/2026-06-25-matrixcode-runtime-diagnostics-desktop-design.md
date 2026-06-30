# MatrixCode 桌面端运行诊断设计

## 背景

第 22 阶段已经提供 `GET /api/projects/{projectId}/runtime-diagnostics`，但桌面端仍无法直接看到真实运行阻塞项。当前真实外部联调的主要问题是 MySQL、Milvus、Redis、RocketMQ 的 TCP 可达性和模型供应商密钥配置状态，需要在工作台上可视化，避免每次依赖命令行排查。

## 目标

- 在主工作台顶部操作区新增“诊断”按钮。
- 打开诊断弹窗时调用运行诊断 API。
- 显示整体状态、生成时间、阻塞项数量、警告项数量、通过项数量。
- 列出每个检查项的名称、状态、详情和是否阻塞。
- 展示服务端返回的下一步动作。
- API 调用失败时显示中文失败态，不影响当前工作台。

## 非目标

- 不在本阶段修复外部网络或服务不可达。
- 不把诊断结果写入本地持久化。
- 不自动修改 `.env.local` 或提交任何密钥。
- 不在本阶段接入编码智能体执行器。

## 交互设计

- 顶部操作区保持现有顺序，新增“诊断”按钮，和“文档”“运行”“配置”同级。
- 诊断弹窗复用现有 `config-dialog` 弹窗风格，避免引入新导航结构。
- 弹窗内部采用紧凑信息面板：
  - 顶部：整体状态与生成时间。
  - 摘要条：阻塞、警告、通过数量。
  - 检查项列表：每项显示 label、status、detail；阻塞项用醒目边框。
  - 下一步动作：逐条展示 `nextActions`。
- 失败态显示“运行诊断暂不可用”，并提供“重试诊断”按钮。

## 数据模型

前端新增类型：

- `RuntimeCheckStatus = 'PASS' | 'WARN' | 'FAIL' | 'SKIPPED'`
- `RuntimeCheckItem`
- `RuntimeDiagnosticsReport`

前端新增 API：

- `loadRuntimeDiagnostics(projectId, serverUrl?)`

## 测试策略

- API client 测试：验证 `loadRuntimeDiagnostics` 请求地址和 Accept header。
- App 测试：
  - 点击“诊断”后调用运行诊断 API。
  - 能展示整体失败状态、阻塞项详情和下一步动作。
  - API 失败时展示失败态和重试按钮。

## 验收标准

- 桌面端测试覆盖诊断入口、正常态和失败态。
- `npm test` 和 `npm run build` 通过。
- 不新增真实密钥和敏感信息。
