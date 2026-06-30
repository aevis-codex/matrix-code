# MatrixCode 第 44 阶段计划：Agent Runtime 前端运行中心展示

## 回溯对齐

- 原始目标：MatrixCode 是多人实时协作的智能体控制台，每个角色都有可配置智能体，开发编码智能体参考 Codex/Claude Code 的运行体验。
- 当前状态：第 43 阶段已把编码智能体运行过程写入 Agent Runtime，缺口在桌面端尚未展示真实运行记录。
- 本阶段对齐：优先让右侧运行指标和运行中心引用真实 Agent Runtime 数据，配置继续走独立按钮，保证工作区清爽。

## 执行步骤

- [x] **步骤 1：补 API client 红测**
  - 增加 Agent Runtime 类型和请求函数测试。
  - 先让测试因函数不存在失败。

- [x] **步骤 2：实现 API client**
  - 增加 `AgentRunRecord`、`AgentRunEventRecord`。
  - 增加 `loadAgentRuns`、`loadAgentRunEvents`。

- [x] **步骤 3：补 App/Inspector 红测**
  - 测试右侧显示最近 Agent 运行状态和关键事件。
  - 测试 Agent Runtime API 失败时工作台仍正常显示。

- [x] **步骤 4：实现桌面端状态和 UI**
  - `refreshWorkbench` 加载 `agentRuns` 和最近运行事件。
  - `InspectorPanel` 和 `OperationsCenterDialog` 接收并展示 Agent Runtime 数据。
  - API 失败降级为空数组并保留工作台。

- [x] **步骤 5：验证**
  - 运行 API client 目标测试。
  - 运行 App 目标测试。
  - 运行桌面端全量测试和构建。
  - 运行静态检查和敏感信息扫描。

- [x] **步骤 6：更新 Obsidian**
  - 更新项目首页、项目总览、阶段索引、模块地图、技术栈与验证风险。

## 风险

- 运行事件过多时右侧区域拥挤：右侧只显示最近少量，完整列表放运行中心。
- 旧后端无 Agent Runtime API：请求失败降级为空数据。
- 数据字段未来扩展：前端类型先跟第 43 阶段 API 保持一致，避免推测字段。

## 验证结果

- API client 红测：`loadAgentRuns`、`loadAgentRunEvents` 未实现时失败。
- API client 绿测：`npm --prefix desktop test -- --run src/api/client.test.ts`，41 条通过。
- App 红测：未加载 Agent Runtime API、未展示 Agent 运行卡片时失败。
- App 绿测：`npm --prefix desktop test -- --run src/test/App.test.tsx`，46 条通过。
- 桌面端全量测试：`npm --prefix desktop test -- --run`，3 个测试文件、93 条测试通过。
- 桌面端构建：`npm --prefix desktop run build` 通过。
- 真实浏览器验收：`http://127.0.0.1:5173/` 右侧 Agent 运行卡片展示真实 MySQL Agent Runtime 记录，运行中心展示 Agent 运行时间线。
- 静态检查：`git diff --check` 通过。
- 敏感信息扫描：精确 API Key 和密码扫描无命中。
- H2 口径扫描：正式主代码无 H2 运行依赖；仅保留测试依赖和一条生产配置说明注释。
