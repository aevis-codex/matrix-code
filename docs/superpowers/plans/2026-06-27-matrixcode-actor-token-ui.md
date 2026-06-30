# MatrixCode 身份令牌配置入口实现计划

> **面向 AI 代理的工作者：** 必需子技能：使用 TDD 执行。每步完成后更新复选框；阶段结束后必须完整验证、更新 Obsidian 第二大脑、提交并推送。

**目标：** 在桌面端配置中心补齐 actor token 获取、保存、展示和清除入口。

**架构：** 复用第 76 阶段签发接口，新增 API client 函数和配置中心「身份」页签。前端只保存 actor token 及元数据，不保存 bootstrap token。

**技术栈：** React、Vitest、Testing Library、Spring Boot 既有签发接口。

---

## 文件结构

- 修改：`desktop/src/api/client.ts`
- 修改：`desktop/src/api/client.test.ts`
- 修改：`desktop/src/components/RoleAgentConfigDialog.tsx`
- 修改：`desktop/src/App.css`
- 修改：`desktop/src/test/App.test.tsx`
- 新增：`docs/superpowers/specs/2026-06-27-matrixcode-actor-token-ui-design.md`
- 新增：`docs/superpowers/plans/2026-06-27-matrixcode-actor-token-ui.md`

## 任务 1：API client 红灯测试

- [x] **步骤 1：编写签发接口测试**

在 `desktop/src/api/client.test.ts` 中新增用例：调用 `issueActorToken('demo', { userId: 'user-dev', ttlSeconds: 3600, bootstrapToken: 'test-signing-ticket' }, 'http://localhost:8080')`，断言请求为：

```ts
expect(fetchMock).toHaveBeenCalledWith('http://localhost:8080/api/projects/demo/identity/auth/actor-token', {
  method: 'POST',
  body: JSON.stringify({ userId: 'user-dev', ttlSeconds: 3600 }),
  headers: {
    Accept: 'application/json',
    'Content-Type': 'application/json',
    'X-MatrixCode-Bootstrap-Token': 'test-signing-ticket'
  }
});
```

- [x] **步骤 2：运行测试确认失败**

```bash
npm --prefix desktop test -- src/api/client.test.ts
```

实际：`issueActorToken is not a function`，红灯符合预期。

## 任务 2：实现 API client

- [x] **步骤 1：新增类型和函数**

在 `desktop/src/api/client.ts` 中新增 `ActorTokenIssueInput`、`ActorTokenIssueResponse` 和 `issueActorToken(...)`。函数只把 bootstrap token 放入请求头，正文只包含 `userId` 和 `ttlSeconds`。

- [x] **步骤 2：运行 API client 测试通过**

```bash
npm --prefix desktop test -- src/api/client.test.ts
```

预期：API client 测试通过。

实际：`src/api/client.test.ts` 49 条通过。

## 任务 3：配置中心身份页 TDD

- [x] **步骤 1：编写桌面端红灯测试**

在 `desktop/src/test/App.test.tsx` 中 mock `issueActorToken`，打开「配置」→「身份」，选择 `user-dev`，填写 bootstrap token 和 TTL，点击「签发身份令牌」，断言：

```ts
expect(签发身份令牌).toHaveBeenCalledWith('demo', {
  userId: 'user-dev',
  ttlSeconds: 3600,
  bootstrapToken: 'test-signing-ticket'
});
expect(window.localStorage.getItem('matrixcode.actorToken')).toBe('signed-token');
```

- [x] **步骤 2：运行测试确认失败**

```bash
npm --prefix desktop test -- src/test/App.test.tsx
```

实际：找不到「身份」页签，红灯符合预期。

## 任务 4：实现身份页

- [x] **步骤 1：扩展配置弹窗**

在 `RoleAgentConfigDialog` 中新增 `identity` section，加载项目成员，提供用户、TTL、bootstrap token 输入，调用 `issueActorToken` 后写入本地 token 元数据。

- [x] **步骤 2：补充样式**

在 `App.css` 中复用现有配置中心密度，新增身份页状态和按钮样式，避免主工作区布局变化。

- [x] **步骤 3：运行桌面目标测试通过**

```bash
npm --prefix desktop test -- src/test/App.test.tsx
```

## 任务 5：完整验证、第二大脑和提交

- [x] **步骤 1：完整验证**

```bash
npm --prefix desktop test
npm --prefix desktop run build
/Users/Masons/Ai/Maven/bin/mvn -Dmaven.repo.local=/Users/Masons/Ai/Maven_Ai_Store -q -pl server -Dtest=IdentityAuthControllerTest test
git diff --check
```

- [x] **步骤 2：安全扫描**

```bash
git diff -- . ':!desktop/package-lock.json' | rg -n "<项目敏感信息模式>"
```

- [x] **步骤 3：更新 Obsidian**

新增 `阶段成果/77 身份令牌配置入口.md`，并更新首页、阶段索引、模块地图、验证与风险、角色工作台和身份相关记录。

- [x] **步骤 4：提交并推送**

```bash
git add ...
git commit -m "feat(desktop): 增加身份令牌配置入口"
git push origin HEAD:master
```

## 第 77 阶段实际验证记录

- `npm --prefix desktop test -- src/api/client.test.ts`：49 条通过。
- `npm --prefix desktop test -- src/test/App.test.tsx`：54 条通过。
- `npm --prefix desktop test`：3 个测试文件，109 条通过。
- `npm --prefix desktop run build`：TypeScript 检查和 Vite production build 通过。
- `/Users/Masons/Ai/Maven/bin/mvn -Dmaven.repo.local=/Users/Masons/Ai/Maven_Ai_Store -q -pl server -Dtest=IdentityAuthControllerTest test`：退出码 0。
- `./scripts/check-real-runtime.sh .env.local`：MySQL、Milvus、Redis、RocketMQ 连通性通过。
- `git diff --check`、旧地址/旧 collection 扫描和敏感信息扫描均通过。
