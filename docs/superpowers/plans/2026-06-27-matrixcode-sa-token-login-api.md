# MatrixCode Sa-Token 登录 API 实现计划

> **面向 AI 代理的工作者：** 必需子技能：使用 test-driven-development 执行红绿循环。步骤使用复选框（`- [ ]`）语法来跟踪进度。每项完成后必须运行目标测试；阶段结束后必须完整验证、更新 Obsidian 第二大脑、提交并推送。

**目标：** 提供 Sa-Token 登录、登出和当前会话 API，为后续桌面端正式登录 UI 做后端基座。

**架构：** `IdentityAuthController` 复用现有项目成员校验和 `ActorTokenIssuer`。新增 `ActorSessionTerminator` 适配 Sa-Token 登出；会话查询复用 `RequestActorResolver`。

**技术栈：** Java 21、Spring Boot 3、Sa-Token、MockMvc、JUnit 5。

---

## 文件结构

- 修改：`server/src/main/java/com/matrixcode/identity/api/IdentityAuthController.java`
  - 新增 `/login`、`/logout`、`/session`。
  - 复用 token 签发逻辑。
  - 注入 `RequestActorResolver` 和 `ActorSessionTerminator`。
- 新增：`server/src/main/java/com/matrixcode/identity/application/ActorSessionTerminator.java`
  - 定义登出接口。
- 新增：`server/src/main/java/com/matrixcode/identity/application/SaTokenActorSessionTerminator.java`
  - 生产实现，调用 `StpUtil.logout()`。
- 修改：`server/src/test/java/com/matrixcode/identity/IdentityAuthControllerTest.java`
  - 覆盖登录、登出和当前会话。

## 任务 1：后端红灯测试

- [x] **步骤 1：补登录接口测试**

在 `IdentityAuthControllerTest` 新增 `可以通过登录接口获取SaToken`，请求 `POST /identity/auth/login`，断言返回 Sa-Token token。

- [x] **步骤 2：补登出接口测试**

在 `IdentityAuthControllerTest` 新增 `可以退出当前SaToken会话`，请求 `POST /identity/auth/logout`，断言返回 204 且 fake terminator 被调用。

- [x] **步骤 3：补当前会话测试**

在 `IdentityAuthControllerTest` 新增 `可以读取当前SaToken会话`，请求 `GET /identity/auth/session`，断言返回 `authenticated=true` 和当前用户 ID。

- [x] **步骤 4：运行红灯**

```bash
/Users/Masons/Ai/Maven/bin/mvn -Dmaven.repo.local=/Users/Masons/Ai/Maven_Ai_Store -q -pl server -Dtest=IdentityAuthControllerTest test
```

结果：失败，原因是缺少 `ActorSessionTerminator`、控制器构造器未注入会话组件，新增接口尚未实现。

## 任务 2：实现登录 API

- [x] **步骤 1：新增登出适配接口和生产实现**

创建 `ActorSessionTerminator` 和 `SaTokenActorSessionTerminator`。

- [x] **步骤 2：改造控制器构造器**

`IdentityAuthController` 注入 `RequestActorResolver` 和 `ActorSessionTerminator`。

- [x] **步骤 3：新增登录、登出、会话接口**

登录复用现有签发逻辑；登出先解析当前身份再调用终止器；会话查询只返回当前用户。

- [x] **步骤 4：运行目标测试**

```bash
/Users/Masons/Ai/Maven/bin/mvn -Dmaven.repo.local=/Users/Masons/Ai/Maven_Ai_Store -q -pl server -Dtest=IdentityAuthControllerTest test
```

结果：目标测试通过。

## 任务 3：完整验证、第二大脑和提交

- [x] **步骤 1：完整验证**

```bash
npm --prefix desktop test
npm --prefix desktop run build
/Users/Masons/Ai/Maven/bin/mvn -Dmaven.repo.local=/Users/Masons/Ai/Maven_Ai_Store -q -pl server test
./scripts/check-real-runtime.sh .env.local
git diff --check
```

- [x] **步骤 2：安全扫描**

```bash
rg -n "<项目敏感信息模式>"
```

- [x] **步骤 3：更新 Obsidian**

新增 `阶段成果/83 Sa-Token 登录 API.md`，并更新首页、项目总览、阶段索引、模块地图、技术栈与运行约定、验证与风险、角色工作台与桌面端、模型网关与上下文门禁。

- [x] **步骤 4：提交并推送**

```bash
git add ...
git commit -m "feat(auth): 增加 Sa-Token 登录接口"
git push origin HEAD:master
```

结果：代码提交 `226b705`，已推送到远程 `master`。
