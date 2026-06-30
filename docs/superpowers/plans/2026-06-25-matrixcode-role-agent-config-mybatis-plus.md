# MatrixCode 第 46 阶段计划：角色智能体配置 MyBatis-Plus 仓储迁移

> **面向 AI 代理的工作者：** 必需子技能：使用 superpowers:subagent-driven-development（推荐）或 superpowers:executing-plans 逐任务实现此计划。步骤使用复选框（`- [ ]`）语法来跟踪进度。

**目标：** 将角色智能体配置正式仓储从 Spring Bean 主路径上的手写 JDBC 迁移到 MyBatis-Plus。

**架构：** 新增 `RoleAgentConfigEntity`、`RoleAgentConfigMapper` 和 `MybatisPlusRoleAgentConfigRepository`。旧 `JdbcRoleAgentConfigRepository` 保留直接实例化测试，但退出 Spring Bean，避免 JDBC 模式下出现两个 `RoleAgentConfigRepository` 实现。

**技术栈：** Java 21、Spring Boot 3.5.15、MyBatis-Plus 3.5.12、Flyway、H2 MySQL mode 测试库、真实 MySQL `matrix_code`。

---

## 文件结构

- 创建：`server/src/main/java/com/matrixcode/persistence/mybatis/entity/RoleAgentConfigEntity.java`
  - 负责 `matrixcode_role_agent_configs` 表和 `RoleAgentConfig` 领域对象之间的转换。
- 创建：`server/src/main/java/com/matrixcode/persistence/mybatis/mapper/RoleAgentConfigMapper.java`
  - MyBatis-Plus `BaseMapper`。
- 创建：`server/src/main/java/com/matrixcode/persistence/application/MybatisPlusRoleAgentConfigRepository.java`
  - JDBC 模式下的正式 Spring Bean，实现 `RoleAgentConfigRepository`。
- 修改：`server/src/main/java/com/matrixcode/persistence/application/JdbcRoleAgentConfigRepository.java`
  - 去掉 Spring 自动装配注解，作为兼容类保留直接单测。
- 创建：`server/src/test/java/com/matrixcode/persistence/MybatisPlusRoleAgentConfigRepositoryTest.java`
  - 覆盖 Bean 选择、保存恢复、项目外键补齐、file 模式不建 DataSource。

## 执行步骤

- [x] **步骤 1：编写失败测试**
  - 新增 `MybatisPlusRoleAgentConfigRepositoryTest`。
  - 断言 JDBC 模式下 `RoleAgentConfigRepository` Bean 类名包含 `MybatisPlusRoleAgentConfigRepository`。
  - 断言保存配置后可恢复，且 `matrixcode_projects` 只有 1 条对应项目。
  - 断言 file 模式没有 `DataSource` 和 `RoleAgentConfigRepository` Bean。

- [x] **步骤 2：运行测试验证红灯**
  - 命令：`/Users/Masons/Ai/Maven/bin/mvn -Dmaven.repo.local=/Users/Masons/Ai/Maven_Ai_Store -q -pl server -Dtest=MybatisPlusRoleAgentConfigRepositoryTest test`
  - 预期：失败，原因是当前 Bean 仍为 `JdbcRoleAgentConfigRepository` 或目标 MyBatis-Plus 实现不存在。

- [x] **步骤 3：实现 MyBatis-Plus 实体和 Mapper**
  - 新增 `RoleAgentConfigEntity`，包含 `id/projectId/roleKey/displayName/agentKind/modelProvider/modelName/toolContractVersion/systemPrompt/userPromptTemplate/themeColor/fontFamily/fontSize/sortOrder/enabled/createdAt/updatedAt`。
  - 新增 `fromDomain()` 和 `toDomain()`，集中处理领域转换。
  - 新增 `RoleAgentConfigMapper extends BaseMapper<RoleAgentConfigEntity>`。

- [x] **步骤 4：实现 MyBatis-Plus 仓储**
  - 新增 `MybatisPlusRoleAgentConfigRepository`。
  - `load()` 使用 `LambdaQueryWrapper` 按 `projectId/sortOrder/roleKey` 排序。
  - `save()` 使用 `@Transactional`，先补齐项目，再 update-by-id，不存在则 insert。
  - 复用 `MatrixProjectEntity.touch/fallbackProject` 和 `MatrixProjectMapper`。

- [x] **步骤 5：旧 JDBC 仓储退出 Spring Bean**
  - 删除 `JdbcRoleAgentConfigRepository` 的 `@Repository`、`@ConditionalOnProperty` 和 `@Autowired` 注解及对应 import。
  - 保留两个构造器和现有直接测试。

- [x] **步骤 6：运行局部绿灯**
  - 命令：`/Users/Masons/Ai/Maven/bin/mvn -Dmaven.repo.local=/Users/Masons/Ai/Maven_Ai_Store -q -pl server -Dtest=MybatisPlusRoleAgentConfigRepositoryTest,JdbcRoleAgentConfigRepositoryTest test`
  - 预期：通过。

- [x] **步骤 7：运行关联回归**
  - 命令：`/Users/Masons/Ai/Maven/bin/mvn -Dmaven.repo.local=/Users/Masons/Ai/Maven_Ai_Store -q -pl server -Dtest=RoleAgentConfigControllerTest,ModelGatewayControllerTest,JdbcPersistenceSpringTest test`
  - 预期：通过。

- [x] **步骤 8：运行全量和真实验证**
  - 服务端全量：`/Users/Masons/Ai/Maven/bin/mvn -Dmaven.repo.local=/Users/Masons/Ai/Maven_Ai_Store -q -pl server test`
  - 真实集成：`set -a; source .env.local; set +a; /Users/Masons/Ai/Maven/bin/mvn -Dmaven.repo.local=/Users/Masons/Ai/Maven_Ai_Store -q -pl server -Dmatrixcode.real-runtime-test=true -Dtest=RealRuntimeIntegrationTest test`
  - 静态检查：`git diff --check`、敏感信息扫描、H2 正式口径扫描。

- [x] **步骤 9：更新 Obsidian**
  - 新增 `MatrixCode/阶段成果/46 角色智能体配置 MyBatis-Plus 仓储迁移.md`。
  - 更新项目首页、项目总览、阶段索引、模块地图、技术栈与运行约定、验证与风险。

## 回溯对齐

- 对齐用户要求：正式 ORM 使用 MyBatis-Plus，正式业务数据使用 MySQL。
- 对齐当前架构：继续沿用第 42 阶段 MyBatis-Plus 模板，不一次性迁移所有仓储。
- 对齐真实可上线目标：保留现有 API/UI 行为，只替换正式仓储实现，降低风险。

## 完成记录

- 红灯：`MybatisPlusRoleAgentConfigRepositoryTest` 首次失败，Spring Bean 仍为 `JdbcRoleAgentConfigRepository`。
- 局部绿灯：`MybatisPlusRoleAgentConfigRepositoryTest,JdbcRoleAgentConfigRepositoryTest` 通过。
- 关联回归：`RoleAgentConfigServiceTest,RoleAgentConfigControllerTest,ModelGatewayControllerTest,JdbcPersistenceSpringTest` 等通过。
- 全量回归：`/Users/Masons/Ai/Maven/bin/mvn -Dmaven.repo.local=/Users/Masons/Ai/Maven_Ai_Store -q -pl server test` 通过，Surefire 汇总 `files=75 tests=344 failures=0 errors=0 skipped=0`。
- 真实集成：`RealRuntimeIntegrationTest` 使用真实 MySQL `matrix_code` 和真实运行配置通过。
- 运行态验证：真实后端 `http://localhost:18080/actuator/health` 返回 `UP`；`/api/projects/demo/role-agent-configs` 可读；`PUT /api/projects/demo/role-agent-configs/developer` 可写并读回。
- 静态检查：`git diff --check` 通过；敏感信息扫描无命中；正式 H2 扫描仅剩测试依赖和兼容注释。
