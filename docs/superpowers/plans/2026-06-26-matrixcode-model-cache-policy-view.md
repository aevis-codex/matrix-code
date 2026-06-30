# MatrixCode 模型缓存策略视图实现计划

> **面向 AI 代理的工作者：** 必需子技能：使用 superpowers:subagent-driven-development（推荐）或 superpowers:executing-plans 逐任务实现此计划。步骤使用复选框（`- [ ]`）语法来跟踪进度。

**目标：** 持久化并展示模型请求采用的缓存策略，让第 51、52、55 阶段的缓存治理变成用户可见证据。

**架构：** `UsageRecord` 增加 `cachePolicyId` 和 `volatileSuffixStrategy`；`ModelGatewayService` 写入稳定前缀策略；MyBatis-Plus/JDBC 项目活动仓储映射新字段；Flyway `V56_1` 扩展正式表；桌面端模型网关卡片展示最近请求策略。

**技术栈：** Java 21、Spring Boot、MyBatis-Plus、Flyway、JUnit 5、React、TypeScript、Vitest。

---

## 文件结构

- 修改：`server/src/main/java/com/matrixcode/usage/domain/UsageRecord.java`，新增策略字段和带策略的 `withCacheTrace(...)`。
- 修改：`server/src/main/java/com/matrixcode/modelgateway/application/ModelGatewayService.java`，写入 `stable-platform-prefix-v1` 和 `role-prompt-and-dynamic-context`。
- 新增：`server/src/main/resources/db/migration/V56_1__extend_model_request_cache_policy.sql`。
- 修改：`server/src/main/java/com/matrixcode/persistence/mybatis/entity/ModelRequestEntity.java`。
- 修改：`server/src/main/java/com/matrixcode/persistence/application/JdbcProjectActivityRepository.java`。
- 修改测试：`ModelGatewayServiceTest`、`MybatisPlusProjectActivityRepositoryTest`、`JdbcProjectActivityRepositoryTest`。
- 修改桌面端：`desktop/src/api/client.ts`、`desktop/src/components/InspectorPanel.tsx`、`desktop/src/test/App.test.tsx`。
- 新增：`/Users/Masons/Documents/Obsidian/Aevis/MatrixCode/阶段成果/56 模型缓存策略视图.md`。

### 任务 1：领域用量记录增加缓存策略字段

- [x] **步骤 1：编写失败的服务测试**

在 `ModelGatewayServiceTest` 的模型请求断言中增加：

```java
assertThat(response.usage().cachePolicyId()).isEqualTo("stable-platform-prefix-v1");
assertThat(response.usage().volatileSuffixStrategy()).isEqualTo("role-prompt-and-dynamic-context");
```

- [x] **步骤 2：运行测试验证失败**

```bash
/Users/Masons/Ai/Maven/bin/mvn -Dmaven.repo.local=/Users/Masons/Ai/Maven_Ai_Store -q -pl server -Dtest=ModelGatewayServiceTest test
```

预期：编译失败，`cachePolicyId()` 和 `volatileSuffixStrategy()` 不存在。

实际红灯：`ModelGatewayServiceTest` 编译失败，`UsageRecord.cachePolicyId()`、`UsageRecord.volatileSuffixStrategy()` 和 14 参数构造器不存在。

- [x] **步骤 3：实现最少领域和服务代码**

- `UsageRecord` 新增字段和默认值。
- 新增带策略字段的 `withCacheTrace(...)` 重载。
- `ModelGatewayService` 调用新重载写入策略字段。

- [x] **步骤 4：运行测试验证通过**

运行步骤 2 命令，预期退出码 0。

实际绿灯：`ModelGatewayServiceTest` 退出码 0。

### 任务 2：正式仓储和 Flyway 持久化策略字段

- [x] **步骤 1：编写失败的仓储测试**

在 MyBatis-Plus 和 JDBC 项目活动仓储测试中，构造带策略字段的 `UsageRecord`，并断言保存恢复后字段完整。

- [x] **步骤 2：运行测试验证失败**

```bash
/Users/Masons/Ai/Maven/bin/mvn -Dmaven.repo.local=/Users/Masons/Ai/Maven_Ai_Store -q -pl server -Dtest=MybatisPlusProjectActivityRepositoryTest,JdbcProjectActivityRepositoryTest,DatabaseMigrationCommentPolicyTest,DatabaseMigrationServiceTest test
```

预期：新增字段未映射或迁移不存在，测试失败。

实际红灯：仓储关联测试编译失败，原因同为 `UsageRecord` 新策略字段不存在。

- [x] **步骤 3：实现迁移和仓储映射**

- 新增 `V56_1__extend_model_request_cache_policy.sql`，字段和索引写详细注释。
- `ModelRequestEntity` 增加字段、getter/setter、领域映射。
- `JdbcProjectActivityRepository` select/insert 增加字段。

- [x] **步骤 4：运行测试验证通过**

运行步骤 2 命令，预期退出码 0。

实际绿灯：`MybatisPlusProjectActivityRepositoryTest,JdbcProjectActivityRepositoryTest,DatabaseMigrationCommentPolicyTest,DatabaseMigrationServiceTest` 退出码 0，Flyway 迁移到 `v56.1`。

### 任务 3：桌面端展示缓存策略

- [x] **步骤 1：编写失败的 UI 测试**

在 `App.test.tsx` 模型请求 fixture 中加入：

```ts
cachePolicyId: 'stable-platform-prefix-v1',
volatileSuffixStrategy: 'role-prompt-and-dynamic-context'
```

断言右侧模型网关显示：

```ts
expect(模型网关.getByText(/缓存策略 stable-platform-prefix-v1/)).toBeTruthy();
expect(模型网关.getByText(/volatile role-prompt-and-dynamic-context/)).toBeTruthy();
```

- [x] **步骤 2：运行测试验证失败**

```bash
npm --prefix desktop test -- --run src/test/App.test.tsx
```

预期：缺少缓存策略文案。

实际红灯：`App.test.tsx` 45 passed / 1 failed，找不到 `缓存策略 stable-platform-prefix-v1`。

- [x] **步骤 3：实现最少前端代码**

- `UsageRecord` 类型增加两个可选字段。
- `InspectorPanel` 在模型网关卡片展示最近请求的策略字段。

- [x] **步骤 4：运行测试验证通过**

运行步骤 2 命令，预期退出码 0。

实际绿灯：`App.test.tsx` 46/46 通过。

### 任务 4：回归、第二大脑、提交

- [x] **步骤 1：运行关联回归**

```bash
/Users/Masons/Ai/Maven/bin/mvn -Dmaven.repo.local=/Users/Masons/Ai/Maven_Ai_Store -q -pl server -Dtest=ModelGatewayServiceTest,MybatisPlusProjectActivityRepositoryTest,JdbcProjectActivityRepositoryTest,DatabaseMigrationCommentPolicyTest,DatabaseMigrationServiceTest test
```

- [x] **步骤 2：运行服务端全量测试**

```bash
/Users/Masons/Ai/Maven/bin/mvn -Dmaven.repo.local=/Users/Masons/Ai/Maven_Ai_Store -q -pl server test
```

- [x] **步骤 3：运行桌面端全量测试和构建**

```bash
npm --prefix desktop test
npm --prefix desktop run build
```

- [x] **步骤 4：运行真实集成**

```bash
set -a; source .env.local; set +a
/Users/Masons/Ai/Maven/bin/mvn -Dmaven.repo.local=/Users/Masons/Ai/Maven_Ai_Store -q -pl server -Dmatrixcode.real-runtime-test=true -Dtest=RealRuntimeIntegrationTest test
```

- [x] **步骤 5：静态和安全检查**

```bash
git diff --check
```

同时精确扫描真实 API Key 和数据库密码字面值，确认仓库与 Obsidian 文档无泄漏。

- [x] **步骤 6：更新第二大脑并回溯对齐**

新增第 56 阶段成果，更新首页、总览、阶段索引、模块地图、验证与风险、模型网关专题、状态持久化专题。

- [x] **步骤 7：提交并推送**

```bash
git add .
git commit -m "feat(模型网关): 展示缓存策略视图"
git push origin HEAD:master
```

实际结果：提交 `09e425c feat(模型网关): 展示缓存策略视图` 已推送到 `origin/master`，远程 `master` 指向 `09e425c2367511b3290de6784a29bbb9e64c8e30`。

执行证据：

- 关联回归：`ModelGatewayServiceTest,MybatisPlusProjectActivityRepositoryTest,JdbcProjectActivityRepositoryTest,DatabaseMigrationCommentPolicyTest,DatabaseMigrationServiceTest` 退出码 0。
- 服务端全量：`files=78 tests=366 failures=0 errors=0 skipped=7`。
- 桌面端全量：`Tests 93 passed`。
- 桌面端构建：`npm --prefix desktop run build` 退出码 0。
- 真实集成：`RealRuntimeIntegrationTest tests=7 failures=0 errors=0 skipped=0`；真实 MySQL `matrix_code` 从 `v53.1` 迁移到 `v56.1`。
- 静态检查：`git diff --check` 无输出；精确密钥扫描 `secret_matches=0`。
