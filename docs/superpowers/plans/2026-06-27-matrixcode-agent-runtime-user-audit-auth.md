# MatrixCode Agent Runtime 用户责任审计权限实现计划

> **面向 AI 代理的工作者：** 本计划按 TDD 执行。每个任务完成后必须运行对应验证命令，并在阶段完成后更新 Obsidian `MatrixCode` 项目图谱。

**目标：** 收紧用户责任审计接口权限边界，使其达到可上线的最小服务端访问控制标准。

**架构：** 后端新增轻量请求身份解析器；用户责任审计控制器强制校验当前请求用户；桌面端 API client 自动携带当前操作者身份头。

**技术栈：** Spring Boot、JUnit 5、MockMvc、React、TypeScript、Vitest。

---

## 文件结构

- 新增：`server/src/main/java/com/matrixcode/identity/api/RequestActorResolver.java`
- 修改：`server/src/main/java/com/matrixcode/agentruntime/api/AgentRuntimeUserAuditController.java`
- 修改：`server/src/test/java/com/matrixcode/agentruntime/AgentRuntimeUserAuditControllerTest.java`
- 修改：`desktop/src/api/client.ts`
- 修改：`desktop/src/api/client.test.ts`
- 修改：Obsidian `MatrixCode` 项目图谱。

## 任务 1：后端权限红灯测试

- [x] **步骤 1：补充控制器测试**

新增用例：

- 未带 `X-MatrixCode-User-Id` 返回 401。
- 当前用户查询自己返回 200。
- OWNER 查询其他用户返回 200。
- 普通成员查询其他用户返回 403。

- [x] **步骤 2：运行目标测试确认失败**

```bash
/Users/Masons/Ai/Maven/bin/mvn -Dmaven.repo.local=/Users/Masons/Ai/Maven_Ai_Store -q -pl server -Dtest=AgentRuntimeUserAuditControllerTest test
```

结果：FAIL，缺少 `RequestActorResolver`，证明权限实现尚未落地。

## 任务 2：实现请求身份与权限控制

- [x] **步骤 1：新增 `RequestActorResolver`**

职责：

- 从请求头 `X-MatrixCode-User-Id` 读取当前用户。
- 归一化空白字符。
- 缺失时抛出 401。

- [x] **步骤 2：控制器权限校验**

职责：

- 本人查询直接允许。
- 管理角色 OWNER、ADMIN、MAINTAINER 查询他人允许。
- 其他情况抛出 403。

- [x] **步骤 3：运行目标测试通过**

```bash
/Users/Masons/Ai/Maven/bin/mvn -Dmaven.repo.local=/Users/Masons/Ai/Maven_Ai_Store -q -pl server -Dtest=AgentRuntimeUserAuditControllerTest test
```

## 任务 3：桌面端请求身份头

- [x] **步骤 1：更新 API client 测试**

用户责任审计请求应带：

```ts
headers: { Accept: 'application/json', 'X-MatrixCode-User-Id': 'user-dev' }
```

- [x] **步骤 2：更新 API client 实现**

`loadAgentRunUserAudit(...)` 使用目标 `userId` 作为当前请求身份头。

- [x] **步骤 3：运行目标测试通过**

```bash
npm --prefix desktop test -- client.test.ts
```

## 任务 4：完整验证、第二大脑和提交

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

新增 `阶段成果/75 Agent Runtime 用户责任审计权限边界.md`，并更新首页、阶段索引、模块地图、验证与风险、身份/状态相关条目。

- [x] **步骤 4：提交并推送**

```bash
git add ...
git commit -m "feat(auth): 限制用户责任审计查询"
git push origin HEAD:master
```
