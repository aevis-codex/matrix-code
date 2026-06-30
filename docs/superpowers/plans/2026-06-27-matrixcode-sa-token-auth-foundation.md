# MatrixCode Sa-Token 认证基座实现计划

> **面向 AI 代理的工作者：** 必需子技能：使用 test-driven-development 执行红绿循环。步骤使用复选框（`- [ ]`）语法来跟踪进度。每项完成后必须运行目标测试；阶段结束后必须完整验证、更新 Obsidian 第二大脑、提交并推送。

**目标：** 将登录、token 和请求身份解析基座切换到 Sa-Token，同时保留现有过渡兼容链路。

**架构：** 新增 Sa-Token 依赖、配置和轻量适配层。`RequestActorResolver` 优先读取 Sa-Token 登录态；签发接口通过 `ActorTokenIssuer` 发行 Sa-Token token；旧 HMAC actor token 和身份头只作为默认兼容模式保留。

**技术栈：** Java 21、Spring Boot 3、Sa-Token、MockMvc、JUnit 5。

---

## 文件结构

- 修改：`server/pom.xml`
  - 新增 `cn.dev33:sa-token-spring-boot3-starter:1.45.0`。
- 修改：`server/src/main/resources/application.yml`
  - 新增 `sa-token` 请求头 token 配置。
  - `matrixcode.auth` 新增 `require-sa-token`。
- 修改：`.env.example`
  - 新增 Sa-Token 超时、token 名称和 token 前缀配置占位。
- 修改：`server/src/main/java/com/matrixcode/identity/application/MatrixCodeAuthProperties.java`
  - 新增 `requireSaToken` 配置。
- 新增：`server/src/main/java/com/matrixcode/identity/application/ActorTokenIssuer.java`
  - 定义 token 签发接口。
- 新增：`server/src/main/java/com/matrixcode/identity/application/IssuedActorToken.java`
  - 承载 `userId`、`token` 和 `expiresAt`。
- 新增：`server/src/main/java/com/matrixcode/identity/application/SaTokenActorTokenIssuer.java`
  - 生产实现，调用 Sa-Token 登录并返回 token。
- 新增：`server/src/main/java/com/matrixcode/identity/api/SaTokenActorSession.java`
  - 定义从当前请求解析 Sa-Token 登录用户的接口。
- 新增：`server/src/main/java/com/matrixcode/identity/api/StpUtilSaTokenActorSession.java`
  - 生产实现，调用 `StpUtil.isLogin()` 和 `StpUtil.getLoginIdAsString()`。
- 修改：`server/src/main/java/com/matrixcode/identity/api/RequestActorResolver.java`
  - 优先读取 Sa-Token 登录态。
  - 支持 `requireSaToken` 强制模式。
- 修改：`server/src/main/java/com/matrixcode/identity/api/IdentityAuthController.java`
  - 使用 `ActorTokenIssuer` 签发 Sa-Token token。
- 修改：`server/src/test/java/com/matrixcode/identity/RequestActorResolverTest.java`
  - 增加 Sa-Token 优先和强制模式测试。
- 修改：`server/src/test/java/com/matrixcode/identity/IdentityAuthControllerTest.java`
  - 验证签发接口使用 Sa-Token issuer 返回 token。

## 任务 1：后端红灯测试

- [x] **步骤 1：补 RequestActorResolver Sa-Token 测试**

在 `RequestActorResolverTest` 增加用例：

- Sa-Token 登录用户存在时优先返回该用户。
- Sa-Token 用户与 `X-MatrixCode-User-Id` 不一致时返回 403。
- `requireSaToken=true` 时，缺少 Sa-Token 登录态即使有旧 token/header 也返回 401。

- [x] **步骤 2：补签发接口测试**

在 `IdentityAuthControllerTest` 中把成功签发断言调整为 Sa-Token issuer 返回的 token，证明控制器不再依赖 HMAC token 格式。

- [x] **步骤 3：运行红灯**

```bash
/Users/Masons/Ai/Maven/bin/mvn -Dmaven.repo.local=/Users/Masons/Ai/Maven_Ai_Store -q -pl server -Dtest=RequestActorResolverTest,IdentityAuthControllerTest test
```

预期：测试失败，失败原因是 Sa-Token 适配接口、强制配置或签发接口尚未实现。

结果：已失败。失败原因为 `ActorTokenIssuer`、`IssuedActorToken`、`SaTokenActorSession`、`requireSaToken` 配置和 `RequestActorResolver` 新构造器尚不存在，符合红灯预期。

## 任务 2：实现 Sa-Token 基座

- [x] **步骤 1：新增依赖与配置**

修改 `server/pom.xml`、`application.yml` 和 `.env.example`，新增 Sa-Token starter 与请求头 token 配置。

- [x] **步骤 2：新增签发与会话适配接口**

创建 `ActorTokenIssuer`、`IssuedActorToken`、`SaTokenActorTokenIssuer`、`SaTokenActorSession` 和 `StpUtilSaTokenActorSession`。

- [x] **步骤 3：改造 RequestActorResolver**

解析顺序改为：Sa-Token 登录态 -> 强制 Sa-Token 检查 -> 旧 HMAC token -> 身份头。

- [x] **步骤 4：改造 IdentityAuthController**

签发接口调用 `ActorTokenIssuer.issue(...)`，返回 Sa-Token token 和过期时间。

- [x] **步骤 5：运行目标测试**

```bash
/Users/Masons/Ai/Maven/bin/mvn -Dmaven.repo.local=/Users/Masons/Ai/Maven_Ai_Store -q -pl server -Dtest=RequestActorResolverTest,IdentityAuthControllerTest test
```

预期：目标测试通过。

结果：`RequestActorResolverTest,IdentityAuthControllerTest` 已通过，退出码 0。

## 任务 3：完整验证、第二大脑和提交

- [x] **步骤 1：完整验证**

```bash
npm --prefix desktop test
npm --prefix desktop run build
/Users/Masons/Ai/Maven/bin/mvn -Dmaven.repo.local=/Users/Masons/Ai/Maven_Ai_Store -q -pl server test
./scripts/check-real-runtime.sh .env.local
git diff --check
```

结果：桌面端全量 114 条测试通过，桌面构建通过；服务端全量 88 个测试文件、445 条测试、0 failures、0 errors、7 skipped；真实 MySQL、Milvus、Redis、RocketMQ 连通性检查通过；`git diff --check` 通过。

- [x] **步骤 2：安全扫描**

```bash
rg -n "<项目敏感信息模式>"
```

结果：全仓敏感扫描和新增行敏感扫描均通过，未发现真实密钥、数据库密码、旧内网地址或旧向量集合名入仓。

- [x] **步骤 3：更新 Obsidian**

新增 `阶段成果/82 Sa-Token 认证基座.md`，并更新首页、项目总览、阶段索引、模块地图、技术栈与运行约定、验证与风险、角色工作台与桌面端、本地执行与审批安全、模型网关与上下文门禁。

结果：已更新第 82 阶段成果页和相关项目图谱页面，且未写入真实密钥或密码。

- [x] **步骤 4：提交并推送**

```bash
git add ...
git commit -m "feat(auth): 接入 Sa-Token 认证基座"
git push origin HEAD:master
```

结果：主提交 `f9a3707 feat(auth): 接入 Sa-Token 认证基座` 已推送到远程 `master`。
