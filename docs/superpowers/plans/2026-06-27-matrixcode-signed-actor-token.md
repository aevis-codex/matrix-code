# MatrixCode 签名身份令牌实现计划

> **面向 AI 代理的工作者：** 本计划按 TDD 执行。每项完成后必须运行目标测试；阶段结束后必须完整验证、更新 Obsidian 第二大脑、提交并推送。

**目标：** 为当前用户身份上下文增加服务端签名令牌能力，补齐第 75 阶段身份头可伪造的上线风险。

**架构：** 新增认证配置、无状态 HMAC actor token 服务、增强请求身份解析器、增加受 bootstrap token 保护的签发接口，桌面端支持可选 actor token 透传。

**技术栈：** Java 21、Spring Boot、JUnit 5、MockMvc、React/Vitest。

---

## 文件结构

- 新增：`server/src/main/java/com/matrixcode/identity/application/MatrixCodeAuthProperties.java`
- 新增：`server/src/main/java/com/matrixcode/identity/application/SignedActorTokenService.java`
- 修改：`server/src/main/java/com/matrixcode/identity/api/RequestActorResolver.java`
- 新增：`server/src/main/java/com/matrixcode/identity/api/IdentityAuthController.java`
- 新增：`server/src/test/java/com/matrixcode/identity/SignedActorTokenServiceTest.java`
- 新增：`server/src/test/java/com/matrixcode/identity/RequestActorResolverTest.java`
- 新增：`server/src/test/java/com/matrixcode/identity/IdentityAuthControllerTest.java`
- 修改：`desktop/src/api/client.ts`
- 修改：`desktop/src/api/client.test.ts`
- 修改：`server/src/main/resources/application.yml`
- 修改：`.env.example`

## 任务 1：后端令牌红灯测试

- [x] **步骤 1：编写服务和解析器测试**

覆盖签发验证、篡改失败、过期失败、强制签名拒绝裸用户头、用户头和令牌不一致返回 403。

- [x] **步骤 2：运行目标测试确认失败**

```bash
/Users/Masons/Ai/Maven/bin/mvn -Dmaven.repo.local=/Users/Masons/Ai/Maven_Ai_Store -q -pl server -Dtest=SignedActorTokenServiceTest,RequestActorResolverTest test
```

## 任务 2：实现签名令牌服务和解析器

- [x] **步骤 1：新增认证配置**

增加 `matrixcode.auth.require-signed-actor-token`、`actor-token-secret`、`bootstrap-token` 和 `default-token-ttl-seconds`。

- [x] **步骤 2：实现 HMAC token**

实现 `issue(...)`、`verify(...)` 和 `expiresAt(...)`，令牌不包含 prompt、响应、向量正文或密钥。

- [x] **步骤 3：增强 `RequestActorResolver`**

优先验证 `Authorization: Bearer <token>`；强制签名时拒绝裸头；非强制模式保留第 75 阶段兼容行为。

## 任务 3：签发接口

- [x] **步骤 1：编写控制器测试**

覆盖 bootstrap token 正确签发、缺少 bootstrap token 401、非项目成员 403。

- [x] **步骤 2：实现控制器**

`POST /api/projects/{projectId}/identity/auth/actor-token`，要求目标用户是当前项目成员。

## 任务 4：桌面端可选令牌透传

- [x] **步骤 1：编写 API client 测试**

本地存在 actor token 时，`loadAgentRunUserAudit(...)` 附加 `Authorization: Bearer <token>`。

- [x] **步骤 2：实现 token 读取**

优先读取 `localStorage.matrixcode.actorToken`，后续完整登录 UI 可复用该存储点。

## 任务 5：完整验证、第二大脑和提交

- [x] **步骤 1：完整验证**

```bash
npm --prefix desktop test
npm --prefix desktop run build
/Users/Masons/Ai/Maven/bin/mvn -Dmaven.repo.local=/Users/Masons/Ai/Maven_Ai_Store -q -pl server test
./scripts/check-real-runtime.sh .env.local
set -a; source .env.local; set +a; /Users/Masons/Ai/Maven/bin/mvn -Dmaven.repo.local=/Users/Masons/Ai/Maven_Ai_Store -q -pl server -Dmatrixcode.real-runtime-test=true -Dtest=RealRuntimeIntegrationTest test
git diff --check
```

- [x] **步骤 2：安全扫描**

```bash
rg -n "<旧地址>|<旧向量集合名>" server/src desktop/src scripts .env.example docs/development --glob '!server/target/**' --glob '!desktop/dist/**'
git diff -- . ':!desktop/package-lock.json' | rg -n "<项目敏感信息模式>"
```

- [x] **步骤 3：更新 Obsidian**

新增 `阶段成果/76 签名身份令牌.md`，并更新首页、阶段索引、模块地图、验证与风险、角色工作台、状态持久化和模型网关页面。

- [x] **步骤 4：提交并推送**

```bash
git add ...
git commit -m "feat(auth): 增加签名身份令牌"
git push origin HEAD:master
```

## 第 76 阶段实际验证记录

- `npm --prefix desktop test`：3 个测试文件，107 条测试通过。
- `npm --prefix desktop run build`：TypeScript 检查和 Vite production build 通过。
- `/Users/Masons/Ai/Maven/bin/mvn -Dmaven.repo.local=/Users/Masons/Ai/Maven_Ai_Store -q -pl server test`：`files=88 tests=426 failures=0 errors=0 skipped=7`。
- `./scripts/check-real-runtime.sh .env.local`：MySQL、Milvus、Redis、RocketMQ 连通性通过，真实运行配置检查通过。
- `RealRuntimeIntegrationTest`：真实环境退出码 0，耗时 549 秒，真实 MySQL `matrix_code` 保持 Flyway `v69.1`。
- `git diff --check`、旧地址/旧 collection 扫描和敏感信息扫描均通过。
