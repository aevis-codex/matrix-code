# MatrixCode 真实运行加固实现计划

> **面向 AI 代理的工作者：** 必需子技能：使用 superpowers:subagent-driven-development（推荐）或 superpowers:executing-plans 逐任务实现此计划。步骤使用复选框（`- [x]`）语法记录实际完成状态。

**目标：** 在 Redis 和 RocketMQ 只预留配置的前提下，优先补齐真实 MySQL 领域仓储、Milvus 向量上下文和真实运行检查。

**架构：** 角色智能体配置先从 JDBC 快照迁移到正式 MySQL 领域表，保留文件/快照模式兼容。向量上下文通过内部接口隔离 embedding 与 Milvus SDK，模型网关只依赖上下文检索接口。真实运行脚本负责环境检查、MySQL 建库、Flyway 和健康探测。

**技术栈：** Java 21、Spring Boot 3.5、Flyway、MySQL、Milvus Java SDK、React/Vite 现有桌面端。

**完成时间：** 2026-06-25

**最终验证证据：**
- `/Users/Masons/Ai/Maven/bin/mvn -Dmaven.repo.local=/Users/Masons/Ai/Maven_Ai_Store -q -pl server test`：退出码 `0`，`271` 个测试，`0` failures，`0` errors，`0` skipped。
- `npm test`：退出码 `0`，Vitest `3` 个测试文件、`73` 个测试通过。
- `npm run build`：退出码 `0`，TypeScript 和 Vite 生产构建通过。
- `docker compose config`：退出码 `0`。
- `docker-compose config`：退出码 `0`。
- `ENV_FILE=.env.example MATRIXCODE_SKIP_CONNECTIVITY_CHECK=true scripts/check-real-runtime.sh`：退出码 `1`，按预期拒绝占位密钥。
- 将 `.env.example` 中 `change-me` 临时替换为测试值后执行 `scripts/check-real-runtime.sh`：退出码 `0`，真实运行配置检查通过。

---

### 任务 1：角色智能体配置正式表仓储

**文件：**
- 创建：`server/src/main/java/com/matrixcode/roleagent/application/RoleAgentConfigRepository.java`
- 创建：`server/src/main/java/com/matrixcode/persistence/application/JdbcRoleAgentConfigRepository.java`
- 修改：`server/src/main/java/com/matrixcode/roleagent/application/RoleAgentConfigService.java`
- 测试：`server/src/test/java/com/matrixcode/persistence/JdbcRoleAgentConfigRepositoryTest.java`
- 测试：`server/src/test/java/com/matrixcode/persistence/JdbcPersistenceSpringTest.java`

- [x] **步骤 1：编写失败测试**

```java
@Test
void 保存后从正式角色智能体配置表恢复配置() {
    var repository = new JdbcRoleAgentConfigRepository(properties);
    repository.save(List.of(config));
    assertThat(repository.load()).singleElement().satisfies(restored -> {
        assertThat(restored.projectId()).isEqualTo("demo");
        assertThat(restored.role()).isEqualTo(ModelRole.DEVELOPER);
    });
}
```

- [x] **步骤 2：运行测试验证失败**

运行：`/Users/Masons/Ai/Maven/bin/mvn -Dmaven.repo.local=/Users/Masons/Ai/Maven_Ai_Store -q -pl server -Dtest=JdbcRoleAgentConfigRepositoryTest test`
预期：FAIL，原因是 `JdbcRoleAgentConfigRepository` 尚不存在。

- [x] **步骤 3：实现最小正式表仓储**

实现 `RoleAgentConfigRepository`，JDBC 模式下用 `matrixcode_role_agent_configs` 读写；插入配置前确保 `matrixcode_projects` 有项目占位行，避免外键失败。

- [x] **步骤 4：服务接入正式表仓储**

`RoleAgentConfigService` 启动时优先读取正式表配置，更新时写正式表；没有正式表仓储时继续走 `WorkbenchStateStore` 快照。

- [x] **步骤 5：运行验证**

运行：`/Users/Masons/Ai/Maven/bin/mvn -Dmaven.repo.local=/Users/Masons/Ai/Maven_Ai_Store -q -pl server -Dtest=JdbcRoleAgentConfigRepositoryTest,JdbcPersistenceSpringTest test`
预期：PASS。

### 任务 2：Milvus 向量上下文接口与模型网关注入

**文件：**
- 修改：`server/pom.xml`
- 创建：`server/src/main/java/com/matrixcode/modelgateway/application/EmbeddingClient.java`
- 创建：`server/src/main/java/com/matrixcode/modelgateway/application/OpenAiCompatibleEmbeddingClient.java`
- 创建：`server/src/main/java/com/matrixcode/modelgateway/application/VectorContextStore.java`
- 创建：`server/src/main/java/com/matrixcode/modelgateway/application/InMemoryVectorContextStore.java`
- 创建：`server/src/main/java/com/matrixcode/modelgateway/application/MilvusVectorContextStore.java`
- 创建：`server/src/main/java/com/matrixcode/modelgateway/application/VectorContextService.java`
- 修改：`server/src/main/java/com/matrixcode/modelgateway/api/ModelGatewayController.java`
- 修改：`server/src/main/java/com/matrixcode/modelgateway/application/ModelGatewayService.java`
- 修改：`server/src/main/resources/application.yml`
- 测试：`server/src/test/java/com/matrixcode/modelgateway/VectorContextServiceTest.java`
- 测试：`server/src/test/java/com/matrixcode/modelgateway/ModelGatewayServiceTest.java`

- [x] **步骤 1：编写失败测试**

```java
@Test
void 模型请求会追加向量召回上下文() {
    var response = gateway.request(new ModelRequestCommand("demo", ModelRole.DEVELOPER, "实现登录", List.of()));
    assertThat(response.contextManifest().blocks()).extracting(ContextBlock::type).contains("VECTOR_CONTEXT");
}
```

- [x] **步骤 2：运行测试验证失败**

运行：`/Users/Masons/Ai/Maven/bin/mvn -Dmaven.repo.local=/Users/Masons/Ai/Maven_Ai_Store -q -pl server -Dtest=VectorContextServiceTest,ModelGatewayServiceTest test`
预期：FAIL，原因是向量上下文服务尚不存在或未注入。

- [x] **步骤 3：实现接口与内存测试适配器**

先实现 embedding、store、service 的内部接口和内存适配器，保证模型网关可以把召回结果追加为 `VECTOR_CONTEXT`。

- [x] **步骤 4：实现 Milvus SDK 适配器**

使用 `io.milvus:milvus-sdk-java:3.0.2`，创建 collection、插入文本元数据和向量、按项目过滤召回。

- [x] **步骤 5：实现上下文记忆 API**

新增写入和检索接口，让工作台或编码智能体可以显式写入项目上下文资产。

- [x] **步骤 6：运行验证**

运行服务端全量测试；默认内存 store 不依赖外部 Milvus，真实环境用 `.env.local` 切换到 milvus。

### 任务 3：真实运行检查脚本增强

**文件：**
- 修改：`scripts/run-real-local.sh`
- 创建：`scripts/check-real-runtime.sh`
- 修改：`.env.example`
- 修改：`MatrixCode` Obsidian 图谱

- [x] **步骤 1：编写脚本检查项**

检查 MySQL TCP、Milvus TCP、模型环境变量是否存在、Redis/RocketMQ 只提示预留。

- [x] **步骤 2：实现脚本**

不输出真实密钥，只输出变量是否已设置。

- [x] **步骤 3：运行验证**

运行：`./scripts/check-real-runtime.sh`
预期：能给出真实可运行差距清单，不泄露密钥。

### 任务 4：阶段回溯与提交

**文件：**
- 修改：`MatrixCode/1 项目首页.md`
- 修改：`MatrixCode/3 阶段索引.md`
- 修改：`MatrixCode/6 验证与风险.md`
- 新增：`MatrixCode/阶段成果/21 真实运行加固.md`

- [x] **步骤 1：更新 Obsidian 图谱**
- [x] **步骤 2：运行全量验证**
- [x] **步骤 3：提交代码**

运行：服务端测试、桌面测试、构建、密钥扫描、健康检查。
