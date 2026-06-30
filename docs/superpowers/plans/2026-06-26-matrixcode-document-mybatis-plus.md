# MatrixCode 第 50 阶段计划：文档中心 MyBatis-Plus 仓储迁移

> **面向 AI 代理的工作者：** 按红绿重构执行。每步完成后更新复选框，并在阶段结束后更新 Obsidian 项目图谱。

**目标：** 将文档中心正式仓储从 Spring Bean 主路径上的手写 JDBC 迁移到 MyBatis-Plus。

**架构：** 新增 `DocumentEntity` 和 `DocumentMapper`，由 `MybatisPlusDocumentRepository` 承接 `DocumentRepository`。旧 `JdbcDocumentRepository` 保留直接单测，但退出 Spring Bean。

**技术栈：** Java 21、Spring Boot 3.5.15、MyBatis-Plus 3.5.12、Flyway、H2 MySQL mode 测试库、真实 MySQL `matrix_code`。

---

## 文件结构

- 创建：`server/src/main/java/com/matrixcode/persistence/mybatis/entity/DocumentEntity.java`
- 创建：`server/src/main/java/com/matrixcode/persistence/mybatis/mapper/DocumentMapper.java`
- 创建：`server/src/main/java/com/matrixcode/persistence/application/MybatisPlusDocumentRepository.java`
- 修改：`server/src/main/java/com/matrixcode/persistence/application/JdbcDocumentRepository.java`
- 修改：`server/src/test/java/com/matrixcode/persistence/JdbcPersistenceSpringTest.java`
- 创建：`server/src/test/java/com/matrixcode/persistence/MybatisPlusDocumentRepositoryTest.java`

## 执行步骤

- [x] **步骤 1：编写失败测试**
  - 新增 `MybatisPlusDocumentRepositoryTest`。
  - 断言 JDBC 模式下 `DocumentRepository` Bean 类名包含 `MybatisPlusDocumentRepository`。
  - 断言保存冻结 PRD 和编码智能体交接文档后可从正式表恢复完整字段。
  - 断言同 ID 二次保存执行 upsert，能更新正文、版本、冻结人和冻结时间。
  - 断言 file 模式不创建 `DataSource`，且没有 `DocumentRepository` Bean。

- [x] **步骤 2：运行测试验证红灯**
  - 命令：`/Users/Masons/Ai/Maven/bin/mvn -Dmaven.repo.local=/Users/Masons/Ai/Maven_Ai_Store -q -pl server -Dtest=MybatisPlusDocumentRepositoryTest test`
  - 结果：失败符合预期，断言显示当前 Bean 仍为 `JdbcDocumentRepository`。

- [x] **步骤 3：实现 MyBatis-Plus 实体和 Mapper**
  - 新增 `DocumentEntity`，集中处理 `DocumentVersion` 与 `matrixcode_documents` 字段转换。
  - 对 `content`、`parent_version_id`、`created_by_role`、`updated_by_role`、`frozen_by`、`frozen_at` 使用可写空值策略。
  - 新增 `DocumentMapper`。

- [x] **步骤 4：实现 MyBatis-Plus Repository**
  - 新增 `MybatisPlusDocumentRepository`。
  - `load()` 按 `project_id, title, version, id` 保持旧排序。
  - `save()` 按 ID 逐条 upsert，不删除其他文档。
  - 写入前补齐项目外键。

- [x] **步骤 5：旧 JDBC Repository 退出 Spring Bean**
  - 删除 `JdbcDocumentRepository` 的 `@Repository`、`@ConditionalOnProperty`、`@Autowired` 和对应 import。
  - 保留构造器和直接测试。
  - 更新 `JdbcPersistenceSpringTest` 断言 `DocumentRepository` Bean 为 MyBatis-Plus 实现。

- [x] **步骤 6：运行局部绿灯**
  - 命令：`/Users/Masons/Ai/Maven/bin/mvn -Dmaven.repo.local=/Users/Masons/Ai/Maven_Ai_Store -q -pl server -Dtest=MybatisPlusDocumentRepositoryTest,JdbcDocumentRepositoryTest,JdbcPersistenceSpringTest test`
  - 结果：通过，文档 MyBatis-Plus 仓储、旧 JDBC 直接测试和 Spring Bean 选择均通过。

- [x] **步骤 7：运行关联回归**
  - 命令：`/Users/Masons/Ai/Maven/bin/mvn -Dmaven.repo.local=/Users/Masons/Ai/Maven_Ai_Store -q -pl server -Dtest=DocumentServiceTest,WorkbenchControllerTest,MybatisPlusDocumentRepositoryTest,JdbcDocumentRepositoryTest,JdbcPersistenceSpringTest test`
  - 结果：通过，文档服务、工作台控制器和 Spring 持久化重启恢复未回归。

- [x] **步骤 8：运行全量和真实验证**
  - 服务端全量：`/Users/Masons/Ai/Maven/bin/mvn -Dmaven.repo.local=/Users/Masons/Ai/Maven_Ai_Store -q -pl server test`
  - 真实集成：`set -a; source .env.local; set +a; /Users/Masons/Ai/Maven/bin/mvn -Dmaven.repo.local=/Users/Masons/Ai/Maven_Ai_Store -q -pl server -Dmatrixcode.real-runtime-test=true -Dtest=RealRuntimeIntegrationTest test`
  - 真实 API：真实后端 health、产品草稿、文档冻结、编码智能体交接文档写入和工作台读回。
  - 静态检查：`git diff --check`、敏感信息扫描、H2 正式口径扫描。
  - 结果：
    - 服务端全量：`files=78 tests=355 failures=0 errors=0 skipped=6`。
    - 真实集成：`RealRuntimeIntegrationTest tests=6 failures=0 errors=0 skipped=0`。
    - 真实 HTTP：项目 `real-doc-mp-20260626011229` health 为 `UP`，冻结 PRD 和编码智能体交接文档均能从工作台读回。
    - 真实表查询：该项目在 `matrixcode_documents` 中存在 `PRD:FROZEN=1`、`CODING_AGENT_HANDOFF:DRAFT=1`、`ACCEPTANCE_CRITERIA:DRAFT=1`、`UI_BRIEF:DRAFT=1`。
    - 静态检查：`git diff --check` 无输出；真实密钥精确扫描无命中；正式资源未配置 H2，仅保留测试方言/注释说明。

- [x] **步骤 9：更新 Obsidian**
  - 新增 `MatrixCode/阶段成果/50 文档中心 MyBatis-Plus 仓储迁移.md`。
  - 更新项目首页、项目总览、阶段索引、模块地图、技术栈与运行约定、验证与风险、状态持久化与数据库迁移、工作流与文档中心。
  - 补充模型网关后续关注，记录参考 DeepSeek-Reasonix 的缓存命中优化主线。

## 回溯对齐

- 对齐用户要求：正式业务数据使用 MySQL，正式 ORM 使用 MyBatis-Plus。
- 对齐当前架构：延续第 46、47、48、49 阶段 MyBatis-Plus 迁移模式。
- 对齐真实可上线目标：冻结文档、交接文档和工作台读回必须通过真实链路验证。
- 对齐新增成本目标：模型网关后续需要参考 DeepSeek-Reasonix，通过稳定 prompt 前缀、会话复用和缓存命中指标降低 token 消耗。

## 阶段结论

- 文档中心正式仓储主路径已迁移到 MyBatis-Plus。
- 旧 JDBC 仓储仅保留直接构造兼容测试，不再作为 Spring Bean 参与正式上下文。
- 冻结 PRD、编码智能体交接文档和工作台读回已通过 H2 MySQL mode、真实 MySQL 集成测试、真实 HTTP 端到端验证三层证据确认。
- 当前未发现与最初需求偏离；本阶段消除了“文档中心正式业务数据仍由手写 JDBC 主路径写读”的偏差。
