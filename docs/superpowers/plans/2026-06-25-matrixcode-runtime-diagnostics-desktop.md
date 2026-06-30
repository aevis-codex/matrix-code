# MatrixCode 桌面端运行诊断实现计划

> **面向 AI 代理的工作者：** 必需子技能：使用 superpowers:subagent-driven-development（推荐）或 superpowers:executing-plans 逐任务实现此计划。步骤使用复选框（`- [ ]`）语法来跟踪进度。

**目标：** 在桌面端新增运行诊断入口，调用后端诊断 API 并展示真实运行阻塞项、警告项和下一步动作。

**架构：** 前端 API client 新增运行诊断类型和加载函数。`App.tsx` 新增诊断弹窗状态和 `RuntimeDiagnosticsDialog` 组件，弹窗打开时拉取诊断报告，关闭后不持久化状态。样式复用现有弹窗和面板体系，仅补诊断列表必要样式。

**技术栈：** React 19、TypeScript、Vitest、Testing Library、现有 Fetch API client。

---

### 任务 1：API client 类型和函数

**文件：**
- 修改：`desktop/src/api/client.ts`
- 修改：`desktop/src/api/client.test.ts`

- [x] **步骤 1：编写失败的 API 测试**

在 `desktop/src/api/client.test.ts` 的“角色工作台 API 客户端”用例组中新增：

```ts
it('加载运行诊断报告时调用项目诊断地址', async () => {
  const fetchMock = vi.fn().mockResolvedValue({
    ok: true,
    json: async () => ({
      status: 'FAIL',
      generatedAt: '2026-06-25T06:00:00Z',
      items: [{ key: 'jdbc', label: 'MySQL', status: 'FAIL', detail: '历史内网地址 MySQL 不可达', blocking: true }],
      nextActions: ['检查 MySQL 服务和网络']
    })
  });
  vi.stubGlobal('fetch', fetchMock);

  const report = await loadRuntimeDiagnostics('demo', 'http://localhost:8080');

  expect(fetchMock).toHaveBeenCalledWith('http://localhost:8080/api/projects/demo/runtime-diagnostics', {
    headers: { Accept: 'application/json' }
  });
  expect(report.items[0].label).toBe('MySQL');
});
```

- [x] **步骤 2：运行测试验证失败**

运行：`cd desktop && npm test -- src/api/client.test.ts -t 运行诊断`

预期：FAIL，原因是 `loadRuntimeDiagnostics` 未导出。

- [x] **步骤 3：实现最少 API client 代码**

在 `desktop/src/api/client.ts` 增加运行诊断类型和函数：

```ts
export type RuntimeCheckStatus = 'PASS' | 'WARN' | 'FAIL' | 'SKIPPED';
export type RuntimeCheckItem = {
  key: string;
  label: string;
  status: RuntimeCheckStatus;
  detail: string;
  blocking: boolean;
};
export type RuntimeDiagnosticsReport = {
  status: RuntimeCheckStatus;
  generatedAt: string;
  items: RuntimeCheckItem[];
  nextActions: string[];
};

export function loadRuntimeDiagnostics(
  projectId = 'demo',
  serverUrl = matrixCodeServerUrl()
): Promise<RuntimeDiagnosticsReport> {
  return requestJson<RuntimeDiagnosticsReport>(projectUrl(serverUrl, projectId, '/runtime-diagnostics'));
}
```

- [x] **步骤 4：运行测试验证通过**

运行：`cd desktop && npm test -- src/api/client.test.ts -t 运行诊断`

预期：PASS。

### 任务 2：桌面端诊断弹窗

**文件：**
- 修改：`desktop/src/App.tsx`
- 修改：`desktop/src/test/App.test.tsx`
- 修改：`desktop/src/App.css`

- [x] **步骤 1：编写失败的弹窗测试**

在 `desktop/src/test/App.test.tsx`：

- mock 中加入 `loadRuntimeDiagnostics: vi.fn()`
- 导入 `loadRuntimeDiagnostics`
- 新增 mock 变量 `加载运行诊断`
- `beforeEach` 默认返回一份包含 `FAIL`、`WARN`、`PASS` 的报告
- 新增用例“可以打开运行诊断并查看阻塞项和下一步动作”

核心断言：

```ts
fireEvent.click(screen.getByRole('button', { name: '诊断' }));
const 诊断 = within(await screen.findByRole('dialog', { name: '运行诊断' }));
expect(加载运行诊断).toHaveBeenCalledWith('demo');
expect(诊断.getByText('整体状态：失败')).toBeTruthy();
expect(诊断.getByText('MySQL')).toBeTruthy();
expect(诊断.getByText(/历史内网地址 MySQL 不可达/)).toBeTruthy();
expect(诊断.getByText('检查 MySQL 服务和网络')).toBeTruthy();
```

- [x] **步骤 2：运行测试验证失败**

运行：`cd desktop && npm test -- src/test/App.test.tsx -t 运行诊断`

预期：FAIL，原因是没有诊断按钮和弹窗。

- [x] **步骤 3：实现最少 UI**

实现内容：

- `App.tsx` 导入 `loadRuntimeDiagnostics` 和类型。
- 新增 `diagnosticsDialogOpen` 状态。
- 顶部操作区新增“诊断”按钮。
- 新增 `RuntimeDiagnosticsDialog` 组件，打开时加载报告，展示加载态、正常态、失败态和重试按钮。
- `App.css` 新增 `.diagnostics-*` 样式，复用暗色面板和 8px 圆角。

- [x] **步骤 4：运行测试验证通过**

运行：`cd desktop && npm test -- src/test/App.test.tsx -t 运行诊断`

预期：PASS。

### 任务 3：验证、图谱和提交

**文件：**
- 修改：Obsidian `MatrixCode/1 项目首页.md`
- 修改：Obsidian `MatrixCode/3 阶段索引.md`
- 修改：Obsidian `MatrixCode/6 验证与风险.md`
- 新增：Obsidian `MatrixCode/阶段成果/23 桌面端运行诊断入口.md`

- [x] **步骤 1：运行桌面端全量验证**

运行：

```bash
cd desktop
npm test
npm run build
```

预期：PASS。

- [x] **步骤 2：运行服务端回归测试**

运行：

```bash
/Users/Masons/Ai/Maven/bin/mvn -Dmaven.repo.local=/Users/Masons/Ai/Maven_Ai_Store -q -pl server test
```

预期：PASS。

- [x] **步骤 3：运行代码卫生和密钥扫描**

运行：

```bash
git diff --check
```

预期：PASS；密钥扫描不得发现用户真实 API Key 或数据库密码片段。

- [x] **步骤 4：更新 Obsidian 图谱**

记录第 23 阶段完成内容、验证证据、剩余风险和下一阶段建议。

- [x] **步骤 5：提交**

```bash
git add .
git commit -m "feat: 增加桌面端运行诊断入口"
```
