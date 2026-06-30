# MatrixCode 真实联调诊断与编码智能体协议设计

## 背景

第 21 阶段已经完成真实运行配置、角色智能体正式表、Milvus 向量上下文和千问 v4 embedding 基础链路。剩余风险集中在两类：

- 真实 MySQL、Milvus、模型供应商需要 `.env.local` 和网络可达后才能端到端联调。
- 编码智能体尚未形成可审查、可审批、可回溯的任务协议。

本阶段不保存真实密钥，不默认调用外部模型；先把可重复诊断和编码任务协议落到后端 API 与测试中。

## 目标

- 提供项目级运行诊断 API，检查 JDBC、模型供应商、向量上下文、Milvus、Redis/RocketMQ 预留项的配置状态和 TCP 可达性。
- 提供编码智能体任务计划 API，为开发角色生成结构化步骤：上下文召回、计划审查、代码编辑、测试命令、diff 审查、交付回溯。
- 保持安全边界：编码智能体 API 只产出计划，不直接执行命令、不写文件、不绕过本地执行审批。
- 更新计划、验证证据和 Obsidian 图谱，延续每阶段回溯规则。

## 非目标

- 不在仓库中创建 `.env.local`。
- 不保存用户提供的真实数据库密码或模型 API Key。
- 不在默认测试中调用真实 MySQL、Milvus、Redis、RocketMQ 或模型供应商。
- 不实现完整自动编码执行器；执行器需要后续阶段接入本地执行、文件编辑、审批和测试回溯。

## 方案

### 运行诊断

新增 `runtimecheck` 模块：

- `RuntimeDiagnosticsService` 聚合现有配置：
  - `PersistenceModeProperties`
  - `ModelGatewayProperties`
- `TcpConnectivityProbe` 抽象 TCP 检查，生产实现使用 `Socket`，测试使用 stub。
- `RuntimeDiagnosticsReport` 返回：
  - 总状态：`PASS`、`WARN`、`FAIL`
  - 检查项列表：配置项、状态、说明、是否阻塞真实运行
  - 下一步动作列表
- API：`GET /api/projects/{projectId}/runtime-diagnostics`

状态规则：

- JDBC 模式启用但 URL、用户名、密码缺失或密码仍为 `change-me`：`FAIL`。
- MySQL TCP 不可达：`FAIL`。
- 启用的模型供应商缺少 API Key 环境变量：`FAIL`。
- 向量上下文启用且 store 为 `milvus` 时，Milvus host/port/collection/dimension 缺失或 TCP 不可达：`FAIL`。
- Redis/RocketMQ 当前只预留：不可达为 `WARN`，不阻塞。

### 编码智能体任务协议

新增 `codingagent` 模块：

- `CodingAgentTaskService` 创建任务计划。
- `CodingAgentTask` 记录任务编号、项目、角色、目标、状态、创建时间和步骤。
- `CodingAgentStep` 记录步骤类型、标题、说明、工具建议和是否需要审批。
- API：`POST /api/projects/{projectId}/roles/{role}/coding-agent/tasks`

默认步骤：

1. 召回项目上下文。
2. 生成并审查实现计划。
3. 读取相关文件。
4. 进行最小代码修改。
5. 运行聚焦测试。
6. 查看 git diff。
7. 更新交付和回溯记录。

安全规则：

- 代码修改步骤标记为需要审查。
- shell 测试命令步骤标记为需要本地执行审批。
- 生成任务时只读取角色智能体配置状态，不执行外部命令。

## 测试策略

- `RuntimeDiagnosticsServiceTest`
  - 验证 JDBC 真实运行配置缺失时返回阻塞失败。
  - 验证 MySQL/Milvus 可达且必要环境变量已设置时返回通过。
  - 验证 Redis/RocketMQ 不可达只产生非阻塞警告。
- `RuntimeDiagnosticsControllerTest`
  - 验证项目级诊断 API 返回报告结构。
- `CodingAgentTaskServiceTest`
  - 验证开发角色任务包含上下文、计划、编辑、测试、diff、回溯步骤。
  - 验证测试命令步骤需要审批。
- `CodingAgentControllerTest`
  - 验证创建编码智能体任务 API 返回结构化计划。

## 验收标准

- 新增 API 有单元测试和控制器测试。
- 服务端全量测试通过。
- 桌面端测试和构建保持通过。
- 密钥扫描不发现真实 API Key 或数据库密码。
- Obsidian `MatrixCode` 图谱新增第 22 阶段成果页，并更新首页、阶段索引、验证与风险。
