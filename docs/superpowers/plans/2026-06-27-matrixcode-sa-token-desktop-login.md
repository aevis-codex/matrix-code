# MatrixCode Sa-Token 桌面登录入口实现计划

> **面向 AI 代理的工作者：** 必需子技能：使用 test-driven-development 执行红绿循环。步骤使用复选框（`- [ ]`）语法跟踪进度。阶段结束后必须完整验证、更新 Obsidian 第二大脑、提交并推送。

**目标：** 把第 83 阶段后端 Sa-Token 登录、登出和当前会话 API 接入桌面端身份页。

**架构：** API client 提供登录态函数；配置中心身份页复用现有本地 token 存储键；登录成功写入 token，退出成功清空 token，打开时可校验本地会话。

**技术栈：** React 19、TypeScript、Vitest、Sa-Token 后端 API。

---

## 文件结构

- 修改：`desktop/src/api/client.ts`
  - 新增 `ActorSessionResponse` 类型。
  - 新增 `loginActorSession(...)`、`logoutActorSession(...)`、`loadActorSession(...)`。
- 修改：`desktop/src/api/client.test.ts`
  - 覆盖登录、登出、当前会话 API。
- 修改：`desktop/src/components/RoleAgentConfigDialog.tsx`
  - 身份页文案改为登录态。
  - 登录调用 `loginActorSession(...)`。
  - 退出调用 `logoutActorSession(...)`。
  - 打开身份页时校验已有本地会话。
- 修改：`desktop/src/test/App.test.tsx`
  - 覆盖配置中心登录和退出。

## 任务 1：API client 红灯

- [x] **步骤 1：补登录接口测试**

在 `desktop/src/api/client.test.ts` 中新增登录测试，断言调用：

```text
POST /api/projects/demo/identity/auth/login
```

请求体为 `userId` 和 `ttlSeconds`，bootstrap 凭证只在请求头传递。

- [x] **步骤 2：补登出接口测试**

新增登出测试，断言调用：

```text
POST /api/projects/demo/identity/auth/logout
```

并携带 `X-MatrixCode-User-Id` 和本地 `Authorization: Bearer`。

- [x] **步骤 3：补当前会话测试**

新增当前会话测试，断言调用：

```text
GET /api/projects/demo/identity/auth/session
```

- [x] **步骤 4：运行红灯**

```bash
npm --prefix desktop test -- desktop/src/api/client.test.ts
```

结果：目标测试失败，原因是 `loginActorSession`、`logoutActorSession`、`loadActorSession` 尚未实现。

## 任务 2：配置中心登录 UI 红灯

- [x] **步骤 1：调整 App 测试**

把原「签发身份令牌」测试升级为「登录和退出当前操作者」，断言：

- 点击「登录」调用 `loginActorSession(...)`。
- 登录成功写入本地 token。
- 点击「退出登录」调用 `logoutActorSession(...)` 并清空本地 token。

- [x] **步骤 2：运行红灯**

```bash
npm --prefix desktop test -- desktop/src/test/App.test.tsx
```

结果：目标测试失败，原因是 UI 仍展示旧「身份令牌」文案并调用旧签发流程。

## 任务 3：实现前端登录入口

- [x] **步骤 1：实现 API client**

在 `client.ts` 中新增三个函数，并保持旧 `issueActorToken(...)` 兼容。

- [x] **步骤 2：实现配置中心身份页**

将 `IdentityTokenPanel` 改为调用登录 API；退出调用登出 API；会话校验失败时清空本地 token。

- [x] **步骤 3：运行目标测试**

```bash
npm --prefix desktop test -- desktop/src/api/client.test.ts desktop/src/test/App.test.tsx
```

结果：`src/api/client.test.ts` 和 `src/test/App.test.tsx` 目标测试通过，2 个测试文件、111 条测试通过。

## 任务 4：完整验证、第二大脑和提交

- [x] **步骤 1：完整验证**

```bash
npm --prefix desktop test
npm --prefix desktop run build
/Users/Masons/Ai/Maven/bin/mvn -Dmaven.repo.local=/Users/Masons/Ai/Maven_Ai_Store -q -pl server test
./scripts/check-real-runtime.sh .env.local
git diff --check
```

- [x] **步骤 2：安全扫描**

执行工作区、diff 新增行和 Obsidian 敏感信息扫描。

- [x] **步骤 3：更新 Obsidian**

新增 `阶段成果/84 Sa-Token 桌面登录入口.md`，并更新首页、项目总览、阶段索引、模块地图、技术栈与运行约定、验证与风险、角色工作台与桌面端。

- [x] **步骤 4：提交并推送**

```bash
git add ...
git commit -m "feat(认证): 接入桌面端 Sa-Token 登录"
git push origin HEAD:master
```

结果：代码提交 `c8334d6`，已推送到远程 `master`。
