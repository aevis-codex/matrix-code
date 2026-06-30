# MatrixCode 模型缓存 trace 可观测性实现计划

> **面向 AI 代理的工作者：** 必需子技能：使用 superpowers:subagent-driven-development（推荐）或 superpowers:executing-plans 逐任务实现此计划。步骤使用复选框（`- [ ]`）语法来跟踪进度。

**目标：** 让模型请求记录和右侧运行指标展示缓存来源、缓存作用域和稳定前缀 fingerprint，支撑后续持续降低 token 成本。

**架构：** 扩展 `UsageRecord` 承载缓存 trace 元数据；模型网关在计算 usage 时写入来源；正式 MySQL `matrixcode_model_requests` 新增带注释字段；桌面端复用现有模型网关卡片展示最近请求 trace。

**技术栈：** Java 21、Spring Boot、Flyway、MyBatis-Plus、JUnit 5、React、TypeScript、Vitest。

---

## 文件结构

- 修改：`server/src/main/java/com/matrixcode/usage/domain/UsageRecord.java`，新增缓存 trace 字段和兼容构造器。
- 修改：`server/src/main/java/com/matrixcode/modelgateway/application/ModelGatewayService.java`，构造带 trace 元数据的 usage。
- 新增：`server/src/main/resources/db/migration/V52_1__extend_model_request_cache_trace.sql`，扩展正式模型请求表并写字段注释。
- 修改：`server/src/main/java/com/matrixcode/persistence/mybatis/entity/ModelRequestEntity.java`，映射新增字段。
- 修改：`server/src/main/java/com/matrixcode/persistence/application/JdbcProjectActivityRepository.java`，兼容旧 JDBC 仓储读写新增字段。
- 修改：`server/src/test/java/com/matrixcode/modelgateway/ModelGatewayServiceTest.java`，覆盖 provider/estimated 来源。
- 修改：`server/src/test/java/com/matrixcode/persistence/MybatisPlusProjectActivityRepositoryTest.java`，覆盖 MyBatis-Plus 保存恢复。
- 修改：`server/src/test/java/com/matrixcode/persistence/JdbcProjectActivityRepositoryTest.java`，覆盖旧 JDBC 保存恢复。
- 修改：`desktop/src/api/client.ts`，扩展 `UsageRecord` 类型。
- 修改：`desktop/src/components/InspectorPanel.tsx`，右侧模型网关卡片展示 cache trace。
- 修改：`desktop/src/test/App.test.tsx`，覆盖页面展示。

### 任务 1：用量领域模型承载缓存 trace

- [x] **步骤 1：编写失败的服务测试**

在 `ModelGatewayServiceTest` 中补充断言：

```java
assertThat(response.usage().cacheSource()).isEqualTo("PROVIDER");
assertThat(response.usage().providerUsageAvailable()).isTrue();
assertThat(response.usage().cacheScopeId()).isEqualTo("matrixcode_demo_DEVELOPER_deepseek_deepseek-chat");
assertThat(response.usage().stablePrefixHash()).isEqualTo(response.promptContract().stablePrefixHash());
```

同时在本地估算路径断言：

```java
assertThat(first.usage().cacheSource()).isEqualTo("ESTIMATED");
assertThat(first.usage().providerUsageAvailable()).isFalse();
```

- [x] **步骤 2：运行测试验证失败**

```bash
/Users/Masons/Ai/Maven/bin/mvn -Dmaven.repo.local=/Users/Masons/Ai/Maven_Ai_Store -q -pl server -Dtest=ModelGatewayServiceTest test
```

预期：编译失败，`UsageRecord` 尚无 `cacheSource/cacheScopeId/stablePrefixHash/providerUsageAvailable`。

实际：编译失败，错误集中在 `UsageRecord` 找不到 `cacheSource()`、`providerUsageAvailable()`、`cacheScopeId()` 和 `stablePrefixHash()`。

- [x] **步骤 3：实现最少领域和服务代码**

`UsageRecord` 增加新字段，并保留旧 8 参数构造器默认：

```java
this(roleSessionId, model, cacheHitTokens, cacheMissInputTokens, outputTokens,
        cacheHitRate, estimatedCost, currency, "ESTIMATED", "", "", false);
```

`ModelGatewayService` 根据 `completion.usage().isPresent()` 写入 `PROVIDER` 或 `ESTIMATED`。

- [x] **步骤 4：运行服务测试验证通过**

运行步骤 2 命令，预期退出码 0。

实际：退出码 0。

### 任务 2：正式仓储持久化缓存 trace

- [x] **步骤 1：编写失败的仓储测试**

在 `MybatisPlusProjectActivityRepositoryTest` 和 `JdbcProjectActivityRepositoryTest` 中把测试 `UsageRecord` 改为带 trace 字段，断言保存后恢复完全相等。

- [x] **步骤 2：运行测试验证失败**

```bash
/Users/Masons/Ai/Maven/bin/mvn -Dmaven.repo.local=/Users/Masons/Ai/Maven_Ai_Store -q -pl server -Dtest=MybatisPlusProjectActivityRepositoryTest,JdbcProjectActivityRepositoryTest test
```

预期：新增字段未迁移或实体未映射，导致字段丢失或 SQL 失败。

实际：保存恢复后 `cacheSource/cacheScopeId/stablePrefixHash/providerUsageAvailable` 被读回为默认值，断言失败。

- [x] **步骤 3：新增 Flyway 迁移和仓储映射**

创建 `V52_1__extend_model_request_cache_trace.sql`：

```sql
alter table matrixcode_model_requests
    add column cache_source varchar(32) not null default 'ESTIMATED' comment '模型请求缓存用量来源：PROVIDER 表示供应商真实 prompt cache 字段，ESTIMATED 表示 MatrixCode 本地稳定前缀估算。';
```

同时补 `cache_scope_id`、`stable_prefix_hash`、`provider_usage_available`，并创建 `(project_id, cache_source, created_at)` 索引。更新 MyBatis-Plus 实体和旧 JDBC 仓储读写。

- [x] **步骤 4：运行仓储测试验证通过**

运行步骤 2 命令，预期退出码 0。

实际：退出码 0，Flyway 应用 13 个迁移到 `v52.1`。

### 任务 3：桌面端展示缓存 trace

- [x] **步骤 1：编写失败的 UI 测试**

在 `desktop/src/test/App.test.tsx` 的运行指标测试中断言：

```ts
expect(screen.getByText(/缓存来源 PROVIDER/)).toBeTruthy();
expect(screen.getByText(/prefix fp-cache-001/)).toBeTruthy();
expect(screen.getByText(/最近命中率 60%/)).toBeTruthy();
```

- [x] **步骤 2：运行测试验证失败**

```bash
npm --prefix desktop test -- --run src/test/App.test.tsx
```

预期：页面尚未展示这些文本。

实际：目标文件 46 条测试中 1 条失败，缺少 `缓存来源 PROVIDER`。

- [x] **步骤 3：实现最少前端代码**

扩展 `desktop/src/api/client.ts` 的 `UsageRecord` 类型。`InspectorPanel` 从 `modelGateway.recentRequests` 取最近请求，展示缓存来源、最近命中率和前缀 hash。

- [x] **步骤 4：运行 UI 测试验证通过**

运行步骤 2 命令，预期退出码 0。

实际：`src/test/App.test.tsx` 46 条测试通过。

### 任务 4：回归验证、图谱、提交

- [x] **步骤 1：运行服务端关联回归**

```bash
/Users/Masons/Ai/Maven/bin/mvn -Dmaven.repo.local=/Users/Masons/Ai/Maven_Ai_Store -q -pl server -Dtest=ModelGatewayServiceTest,MybatisPlusProjectActivityRepositoryTest,JdbcProjectActivityRepositoryTest,DatabaseMigrationCommentPolicyTest,DatabaseMigrationServiceTest test
```

- [x] **步骤 2：运行服务端全量测试**

```bash
/Users/Masons/Ai/Maven/bin/mvn -Dmaven.repo.local=/Users/Masons/Ai/Maven_Ai_Store -q -pl server test
```

- [x] **步骤 3：运行桌面端测试和构建**

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

同时扫描真实 API Key 和数据库密码字面值，确认仓库与 Obsidian 文档无泄漏。

实际：`git diff --check` 无输出；真实 API Key 和数据库密码精确扫描无命中。

- [x] **步骤 6：更新第二大脑**

新增 `阶段成果/52 模型缓存 trace 可观测性.md`，并更新首页、总览、阶段索引、模块地图、技术约定、验证与风险、模型网关专题、持久化专题。

- [x] **步骤 7：提交并推送**

```bash
git add .
git commit -m "feat(模型网关): 增加缓存 trace 可观测性"
git push origin HEAD:master
```
