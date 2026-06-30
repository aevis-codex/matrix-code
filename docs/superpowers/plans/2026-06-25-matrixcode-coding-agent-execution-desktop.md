# 桌面端编码智能体执行准备入口实现计划

> **面向 AI 代理的工作者：** 必需子技能：使用 superpowers:subagent-driven-development（推荐）或 superpowers:executing-plans 逐任务实现此计划。步骤使用复选框（`- [ ]`）语法来跟踪进度。

**目标：** 在开发角色桌面工作区接入服务端编码智能体执行准备接口，并展示可验收的执行准备报告。

**架构：** API client 增加类型和 POST 封装；`App.tsx` 负责注入项目、角色和操作者；`DeveloperPanel` 负责表单、调用回调、展示结果。服务端仍是安全边界，前端不执行代码修改。

**技术栈：** React、TypeScript、Vitest、Testing Library、Vite。

---

## 文件结构

- 修改 `desktop/src/api/client.ts`：新增编码智能体执行准备类型与 API 函数。
- 修改 `desktop/src/api/client.test.ts`：新增 API 请求契约测试。
- 修改 `desktop/src/App.tsx`：导入 API，新增 `user-dev` 操作者，把工作区和执行准备回调传入开发面板。
- 修改 `desktop/src/components/DeveloperPanel.tsx`：新增执行准备表单、状态展示和结果面板。
- 修改 `desktop/src/test/App.test.tsx`：新增开发工作区执行准备测试和 mock。
- 修改 `desktop/src/App.css`：补充紧凑布局样式。
- 新增/更新 Obsidian `MatrixCode` 项目图谱：记录第 25 阶段。

## 任务 1：API client 契约

**文件：**
- 修改：`desktop/src/api/client.test.ts`
- 修改：`desktop/src/api/client.ts`

- [x] **步骤 1：编写失败的测试**

新增测试：调用 `prepareCodingAgentExecution('demo', 'developer', input, 'http://localhost:8080')` 时，请求 `POST /api/projects/demo/roles/developer/coding-agent/execution-plans`，body 包含 `goal`、`workspaceId`、`actorId`、`testCommand`。

- [x] **步骤 2：运行测试验证失败**

运行：`cd desktop && npm test -- src/api/client.test.ts -t 编码智能体执行`

预期：FAIL，原因是 `prepareCodingAgentExecution` 尚未导出。

- [x] **步骤 3：编写最少实现代码**

在 `client.ts` 中增加类型和函数，复用 `requestJson` 与 `projectUrl`。

- [x] **步骤 4：运行测试验证通过**

运行：`cd desktop && npm test -- src/api/client.test.ts -t 编码智能体执行`

预期：PASS。

## 任务 2：开发面板执行准备入口

**文件：**
- 修改：`desktop/src/test/App.test.tsx`
- 修改：`desktop/src/App.tsx`
- 修改：`desktop/src/components/DeveloperPanel.tsx`
- 修改：`desktop/src/App.css`

- [x] **步骤 1：编写失败的 UI 测试**

新增测试：切换到“开发”，填写“执行目标”和“测试命令”，点击“生成执行准备”，断言 API 使用 `demo`、`developer`、`workspace-1`、`user-dev` 调用，并断言页面显示“执行准备报告”“代码编辑”“需要审批”“测试命令已提交到本地执行服务”。

- [x] **步骤 2：运行测试验证失败**

运行：`cd desktop && npm test -- src/test/App.test.tsx -t 执行准备`

预期：FAIL，原因是按钮/回调/展示区域不存在。

- [x] **步骤 3：编写最少实现代码**

`DeveloperPanel` 接收 `workspaces` 和 `onPrepareExecution`；本地维护执行准备表单和结果状态；`App.tsx` 注入 `prepareCodingAgentExecution(projectId, 'developer', { actorId: 'user-dev', ...input })`。

- [x] **步骤 4：运行测试验证通过**

运行：`cd desktop && npm test -- src/test/App.test.tsx -t 执行准备`

预期：PASS。

## 任务 3：全量验证、回溯和记录

**文件：**
- 修改：`docs/superpowers/plans/2026-06-25-matrixcode-coding-agent-execution-desktop.md`
- 更新：Obsidian `MatrixCode` 项目图谱

- [x] **步骤 1：运行桌面端全量测试**

运行：`cd desktop && npm test`

预期：全部测试通过。

- [x] **步骤 2：运行桌面端构建**

运行：`cd desktop && npm run build`

预期：TypeScript 和 Vite 构建通过。

- [x] **步骤 3：浏览器验收**

打开 `http://127.0.0.1:5173/`，进入开发角色，确认执行准备入口可见，表单布局清爽，结果展示不挤占右侧运行指标。

- [x] **步骤 4：回溯阶段偏差**

核对第 11、13、14、15、22、23、24 阶段目标，确认本阶段没有偏离“多人实时协作智能体控制台、开发编码智能体、真实运行、安全审批、高度配置化”的主线。

- [x] **步骤 5：更新 Obsidian 项目图谱**

新增阶段成果页并更新首页、总览、阶段索引、模块地图、验证与风险、角色工作台、本地执行与审批安全。

- [x] **步骤 6：提交**

运行：

```bash
git add docs/superpowers/specs/2026-06-25-matrixcode-coding-agent-execution-desktop-design.md docs/superpowers/plans/2026-06-25-matrixcode-coding-agent-execution-desktop.md desktop/src/api/client.ts desktop/src/api/client.test.ts desktop/src/App.tsx desktop/src/components/DeveloperPanel.tsx desktop/src/test/App.test.tsx desktop/src/App.css
git commit -m "feat: 增加桌面端编码智能体执行准备入口"
```

## 执行记录

- API 红灯：`prepareCodingAgentExecution is not a function`。
- API 绿灯：`npm test -- src/api/client.test.ts -t 编码智能体执行` 通过。
- UI 红灯：找不到“执行目标”控件。
- UI 绿灯：`npm test -- src/test/App.test.tsx -t 执行准备` 通过。
- 缺陷回归红灯：默认使用旧工作区 `workspace-old`。
- 缺陷回归绿灯：`npm test -- src/test/App.test.tsx -t 最近访问` 通过。
- 全量验证：`npm test` 通过，3 个测试文件、79 条测试。
- 构建验证：`npm run build` 通过。
- 浏览器验收：`http://localhost:5173/` 开发角色可生成执行准备报告，控制台无 error。
- 密钥精确扫描：无用户真实 key 或数据库密码命中。
