# MatrixCode 第 49 阶段计划：项目活动 MyBatis-Plus 仓储迁移

> **面向 AI 代理的工作者：** 按红绿重构执行。每步完成后更新复选框，并在阶段结束后更新 Obsidian 项目图谱。

**目标：** 将项目活动正式仓储从 Spring Bean 主路径上的手写 JDBC 迁移到 MyBatis-Plus。

**架构：** 新增模型请求和项目事件两组 Entity/Mapper，新增 `MybatisPlusProjectActivityRepository` 承接 `ProjectActivityRepository`。旧 `JdbcProjectActivityRepository` 保留直接单测，但退出 Spring Bean。

**技术栈：** Java 21、Spring Boot 3.5.15、MyBatis-Plus 3.5.12、Flyway、H2 MySQL mode 测试库、真实 MySQL `matrix_code`。

---

## 文件结构

- 创建：`server/src/main/java/com/matrixcode/persistence/mybatis/entity/ModelRequestEntity.java`
- 创建：`server/src/main/java/com/matrixcode/persistence/mybatis/entity/ProjectEventEntity.java`
- 创建：`server/src/main/java/com/matrixcode/persistence/mybatis/mapper/ModelRequestMapper.java`
- 创建：`server/src/main/java/com/matrixcode/persistence/mybatis/mapper/ProjectEventMapper.java`
- 创建：`server/src/main/java/com/matrixcode/persistence/application/MybatisPlusProjectActivityRepository.java`
- 修改：`server/src/main/java/com/matrixcode/persistence/application/JdbcProjectActivityRepository.java`
- 修改：`server/src/test/java/com/matrixcode/persistence/JdbcPersistenceSpringTest.java`
- 创建：`server/src/test/java/com/matrixcode/persistence/MybatisPlusProjectActivityRepositoryTest.java`

## 执行步骤

- [x] **步骤 1：编写失败测试**
  - 新增 `MybatisPlusProjectActivityRepositoryTest`。
  - 断言 JDBC 模式下 `ProjectActivityRepository` Bean 类名包含 `MybatisPlusProjectActivityRepository`。
  - 断言保存模型请求和项目事件后可从正式表恢复完整字段。
  - 断言同项目二次保存会执行项目级替换，不重复保留旧请求或旧事件。
  - 断言 file 模式不创建 `DataSource`，且没有 `ProjectActivityRepository` Bean。

- [x] **步骤 2：运行测试验证红灯**
  - 命令：`/Users/Masons/Ai/Maven/bin/mvn -Dmaven.repo.local=/Users/Masons/Ai/Maven_Ai_Store -q -pl server -Dtest=MybatisPlusProjectActivityRepositoryTest test`
  - 结果：失败符合预期，断言显示当前 Bean 仍为 `JdbcProjectActivityRepository`。

- [x] **步骤 3：实现 MyBatis-Plus 实体和 Mapper**
  - 新增 `ModelRequestEntity` 和 `ProjectEventEntity`。
  - 集中处理领域对象和正式表字段转换。
  - 新增 `ModelRequestMapper` 和 `ProjectEventMapper`。

- [x] **步骤 4：实现 MyBatis-Plus Repository**
  - 新增 `MybatisPlusProjectActivityRepository`。
  - `loadModelRequests()` 和 `loadProjectEvents()` 保持旧排序。
  - `saveModelRequests()` 和 `saveProjectEvents()` 保持项目级替换语义。
  - 写入前补齐项目外键和用户外键。

- [x] **步骤 5：旧 JDBC Repository 退出 Spring Bean**
  - 删除 `JdbcProjectActivityRepository` 的 `@Repository`、`@ConditionalOnProperty`、`@Autowired` 和对应 import。
  - 保留构造器和直接测试。
  - 更新 `JdbcPersistenceSpringTest` 断言 `ProjectActivityRepository` Bean 为 MyBatis-Plus 实现。

- [x] **步骤 6：运行局部绿灯**
  - 命令：`/Users/Masons/Ai/Maven/bin/mvn -Dmaven.repo.local=/Users/Masons/Ai/Maven_Ai_Store -q -pl server -Dtest=MybatisPlusProjectActivityRepositoryTest,JdbcProjectActivityRepositoryTest,JdbcPersistenceSpringTest test`
  - 结果：`clean test` 后通过。非 clean 首次失败定位为旧 `target/classes` 陈旧字节码；清理后确认生产 Bean 只有 MyBatis-Plus 仓储。

- [x] **步骤 7：运行关联回归**
  - 命令：`/Users/Masons/Ai/Maven/bin/mvn -Dmaven.repo.local=/Users/Masons/Ai/Maven_Ai_Store -q -pl server -Dtest=ModelGatewayServiceTest,ProjectEventStreamTest,ProjectActivityRepositoryServiceTest,WorkbenchControllerTest,MybatisPlusProjectActivityRepositoryTest,JdbcProjectActivityRepositoryTest test`
  - 结果：通过，模型网关、项目事件总线、工作台控制器和项目活动仓储服务未回归。

- [x] **步骤 8：运行全量和真实验证**
  - 服务端全量：`/Users/Masons/Ai/Maven/bin/mvn -Dmaven.repo.local=/Users/Masons/Ai/Maven_Ai_Store -q -pl server test`
  - 真实集成：`set -a; source .env.local; set +a; /Users/Masons/Ai/Maven/bin/mvn -Dmaven.repo.local=/Users/Masons/Ai/Maven_Ai_Store -q -pl server -Dmatrixcode.real-runtime-test=true -Dtest=RealRuntimeIntegrationTest test`
  - 真实 API：真实后端 health、模型请求写读和项目事件写读。
  - 静态检查：`git diff --check`、敏感信息扫描、H2 正式口径扫描。
  - 结果：
    - 服务端全量：`files=77 tests=352 failures=0 errors=0 skipped=6`。
    - 真实集成：`RealRuntimeIntegrationTest tests=6 failures=0 errors=0 skipped=0`，覆盖真实 MySQL、Redis、RocketMQ、千问 embedding、Milvus，以及真实 MySQL 上的项目活动 MyBatis-Plus 仓储写读。
    - 真实 HTTP：当前代码启动后端，`/actuator/health` 为 `UP`；项目 `real-api-20260626004612` 通过产品草稿、文档冻结和开发角色真实模型请求，工作台返回 `FROZEN` 文档、`deepseek-chat` 模型请求和关键事件。
    - 真实表查询：`matrixcode_documents=3`、`matrixcode_model_requests=2`、`matrixcode_project_events=4`。
    - 静态检查：`git diff --check` 无输出；真实密钥精确扫描无命中；正式资源未配置 H2，仅保留测试和方言注释。

- [x] **步骤 9：更新 Obsidian**
  - 新增 `MatrixCode/阶段成果/49 项目活动 MyBatis-Plus 仓储迁移.md`。
  - 更新项目首页、项目总览、阶段索引、模块地图、技术栈与运行约定、验证与风险、状态持久化与数据库迁移。
  - 补充模型网关与上下文门禁、运行态同步与提醒中心，并关闭 GitHub 远程发布阻塞风险。

## 回溯对齐

- 对齐用户要求：正式业务数据使用 MySQL，正式 ORM 使用 MyBatis-Plus。
- 对齐当前架构：延续第 46、47、48 阶段 MyBatis-Plus 迁移模式。
- 对齐真实可上线目标：不改变 API/UI 契约，只替换正式仓储实现，并用真实基础设施验证。

## 阶段结论

- 项目活动正式仓储主路径已迁移到 MyBatis-Plus。
- 旧 JDBC 仓储仅保留直接构造兼容测试，不再作为 Spring Bean 参与正式上下文。
- 模型请求和项目事件已通过 H2 MySQL mode、真实 MySQL 集成测试、真实 HTTP 端到端验证三层证据确认。
- 当前未发现与最初需求偏离；本阶段消除了“正式业务数据仍由手写 JDBC 主路径写读”的偏差。
