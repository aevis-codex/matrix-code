# Agent 运行记录正式仓储实现计划

> **面向 AI 代理的工作者：** 必需子技能：使用 TDD。步骤使用复选框语法跟踪进度。

**目标：** 建立 Agent 运行记录正式 MySQL 仓储，并把新增 DDL 注释和核心方法注释纳入上线门禁。

**架构：** 新增 `agentruntime` 应用接口和 MyBatis-Plus 实现，使用 Flyway 创建 `matrixcode_agent_runs` 与 `matrixcode_agent_run_events`。迁移 SQL 使用 MySQL/H2 MySQL 兼容的表注释和字段注释；测试扫描 V42 之后迁移，防止后续新表缺注释。

**技术栈：** Java 21、Spring Boot、MyBatis-Plus、Flyway、MySQL、H2 MySQL mode（仅测试）、JUnit 5、AssertJ。

---

## 任务 1：迁移注释门禁红灯

- [x] 步骤 1：新增失败测试 `DatabaseMigrationCommentPolicyTest`，扫描 V42 及之后 SQL，要求 `create table` 有表注释和字段注释。
- [x] 步骤 2：运行测试，确认在没有 V42 迁移或迁移缺失时失败。

## 任务 2：Agent 运行表迁移

- [x] 步骤 1：新增失败测试，断言 Flyway 创建 `matrixcode_agent_runs`、`matrixcode_agent_run_events`，且 H2 `information_schema` 能读到表和字段注释。
- [x] 步骤 2：新增 `V42_1__create_agent_runtime_tables.sql`，用 `comment` 写完整表注释和字段注释。
- [x] 步骤 3：运行迁移测试确认通过。

## 任务 3：MyBatis-Plus 仓储

- [x] 步骤 1：新增失败测试 `MybatisPlusAgentRuntimeRepositoryTest`，覆盖 Spring JDBC 模式下启用 MyBatis-Plus 仓储、保存运行、追加事件、读取最近运行和事件顺序。
- [x] 步骤 2：新增 `AgentRunRecord`、`AgentRunEventRecord`、`AgentRuntimeRepository`。
- [x] 步骤 3：新增 `MatrixCodeDataSourceConfiguration`，只在 `matrixcode.persistence.mode=jdbc` 时创建 DataSource，避免默认 file 模式误依赖 H2 或数据库。
- [x] 步骤 4：新增 `AgentRunEntity`、`AgentRunEventEntity`、`MatrixUserEntity`、`MatrixProjectEntity` 与 MyBatis-Plus Mapper，领域对象不直接承载 ORM 注解。
- [x] 步骤 5：新增 `MybatisPlusAgentRuntimeRepository`，核心 public 方法写 JavaDoc，说明职责、边界和副作用。
- [x] 步骤 6：运行仓储测试确认通过。

## 任务 4：回归与图谱

- [x] 步骤 1：运行服务端定向测试、服务端全量测试、真实预检和真实集成。
- [x] 步骤 2：运行 `git diff --check`、旧 schema/密钥扫描。
- [x] 步骤 3：更新 Obsidian 图谱和第 42 阶段成果页。

## 执行记录

- 红灯：`/Users/Masons/Ai/Maven/bin/mvn -Dmaven.repo.local=/Users/Masons/Ai/Maven_Ai_Store -q -pl server -Dtest=MybatisPlusAgentRuntimeRepositoryTest test` 编译失败，缺少 `MybatisPlusAgentRuntimeRepository`。
- 绿灯：同一命令通过，确认 JDBC 模式启用 MyBatis-Plus 仓储，默认 file 模式不创建 DataSource。
- 回归：`/Users/Masons/Ai/Maven/bin/mvn -Dmaven.repo.local=/Users/Masons/Ai/Maven_Ai_Store -q -pl server -Dtest=DatabaseMigrationServiceTest,DatabaseMigrationCommentPolicyTest,MybatisPlusAgentRuntimeRepositoryTest test` 通过。
- 修正：服务端全量测试发现旧测试仍使用 H2 PostgreSQL 模式，已统一切换为 H2 MySQL 模式，和生产 MySQL 目标对齐。
- 全量：`/Users/Masons/Ai/Maven/bin/mvn -Dmaven.repo.local=/Users/Masons/Ai/Maven_Ai_Store -q -pl server test` 通过。
- 真实预检：`./scripts/check-real-runtime.sh` 通过，MySQL、Milvus、Redis、RocketMQ 均可连接。
- 真实集成：`/Users/Masons/Ai/Maven/bin/mvn -Dmaven.repo.local=/Users/Masons/Ai/Maven_Ai_Store -q -pl server -Dmatrixcode.real-runtime-test=true -Dtest=RealRuntimeIntegrationTest test` 通过，真实 MySQL `matrix_code` 已迁移到 Flyway 42.1，并完成 MyBatis-Plus Agent Runtime 写读。
- 桌面回归：`npm --prefix desktop test -- --run` 通过，3 个测试文件、89 条测试通过；`npm --prefix desktop run build` 通过。
- 静态检查：`git diff --check` 通过；密钥扫描无命中；旧 `matrixcode` schema 扫描无命中；生产代码无 PostgreSQL/H2 运行配置残留，H2 仅在 test scope 和 `server/src/test` 中出现。
- 图谱：已新增 [[42 Agent 运行记录正式仓储]]，并更新项目首页、项目总览、阶段索引、模块地图、技术栈、持久化模块、模型网关模块、验证与风险。
