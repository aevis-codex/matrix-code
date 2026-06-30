# 项目活动正式 MySQL 仓储实现计划

> **执行说明：** 用户已授权后续阶段自动执行。本计划按推荐方案直接推进，并在完成后回溯前序阶段、更新 Obsidian 项目图谱。

**目标：** 将模型请求记录和项目事件迁移到正式 MySQL 仓储，继续削减 `workbench-state` 对真实上线运行态的承载。

**架构：** 新增 `ProjectActivityRepository` 聚合接口和 JDBC 实现。`ModelGatewayService` 与 `ProjectEventBus` 在 JDBC 模式下优先读写正式表，旧快照仅作为空表回填来源。

**技术栈：** Java 21、Spring Boot、Flyway、JDBC、H2 MySQL mode、AssertJ。

---

### 任务 1：红灯测试

**文件：**
- 创建：`server/src/test/java/com/matrixcode/persistence/JdbcProjectActivityRepositoryTest.java`
- 创建：`server/src/test/java/com/matrixcode/workbench/ProjectActivityRepositoryServiceTest.java`

- [x] 编写 JDBC 仓储测试，引用尚未存在的 `ProjectActivityRepository` 和 `JdbcProjectActivityRepository`。
- [x] 编写服务接入测试，验证正式仓储优先、旧快照回填和订阅兼容。
- [x] 运行局部测试，预期编译失败。

### 任务 2：迁移和仓储

**文件：**
- 创建：`server/src/main/java/com/matrixcode/workbench/application/ProjectActivityRepository.java`
- 创建：`server/src/main/java/com/matrixcode/persistence/application/JdbcProjectActivityRepository.java`
- 创建：`server/src/main/resources/db/migration/V36_1__create_project_activity_tables.sql`

- [x] 新增聚合接口，方法与 `WorkbenchStateStore` 的模型请求、项目事件切片保持一致。
- [x] 新增 Flyway 迁移，创建 `matrixcode_model_requests`；项目事件复用已有 `matrixcode_project_events`。
- [x] 新增 JDBC 实现，支持 load、按项目 replace、最小项目 upsert 和迁移触发。
- [x] 运行仓储测试，预期通过。

### 任务 3：服务接入

**文件：**
- 修改：`server/src/main/java/com/matrixcode/modelgateway/application/ModelGatewayService.java`
- 修改：`server/src/main/java/com/matrixcode/realtime/application/ProjectEventBus.java`

- [x] 构造器增加可选 `ProjectActivityRepository`。
- [x] 加载顺序改为正式表优先、旧快照空表回填。
- [x] 保存路径改为 JDBC 模式写正式表，非 JDBC 模式写旧 `WorkbenchStateStore`。
- [x] 保持模型摘要、指标、事件订阅和 API 行为不变。

### 任务 4：回归验证和图谱

**文件：**
- 新增：`MatrixCode/阶段成果/36 项目活动正式 MySQL 仓储.md`
- 修改：`MatrixCode/1 项目首页.md`
- 修改：`MatrixCode/3 阶段索引.md`
- 修改：`MatrixCode/5 技术栈与运行约定.md`
- 修改：`MatrixCode/6 验证与风险.md`
- 修改：`MatrixCode/9 模型网关与上下文门禁.md`

- [x] 运行关联测试、服务端全量测试、真实基础设施集成、`git diff --check`、敏感信息扫描、旧 schema 扫描。
- [x] 回溯第 11、13、14、15、33、34、35 阶段，确认没有偏离真实可上线目标。
- [x] 更新 Obsidian 图谱，记录阶段成果、验证证据和剩余风险。
