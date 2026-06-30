# MatrixCode 第 48 阶段计划：部署目标 MyBatis-Plus 仓储迁移

> **面向 AI 代理的工作者：** 按红绿重构执行。每步完成后更新复选框，并在阶段结束后更新 Obsidian 项目图谱。

**目标：** 将部署目标正式仓储从 Spring Bean 主路径上的手写 JDBC 迁移到 MyBatis-Plus。

**架构：** 新增 `DeploymentTargetEntity`、`DeploymentTargetMapper` 和 `MybatisPlusDeploymentTargetRepository`。旧 `JdbcDeploymentTargetRepository` 保留直接实例化测试，但退出 Spring Bean。

**技术栈：** Java 21、Spring Boot 3.5.15、MyBatis-Plus 3.5.12、Flyway、H2 MySQL mode 测试库、真实 MySQL `matrix_code`。

---

## 文件结构

- 创建：`server/src/main/java/com/matrixcode/persistence/mybatis/entity/DeploymentTargetEntity.java`
- 创建：`server/src/main/java/com/matrixcode/persistence/mybatis/mapper/DeploymentTargetMapper.java`
- 创建：`server/src/main/java/com/matrixcode/persistence/application/MybatisPlusDeploymentTargetRepository.java`
- 修改：`server/src/main/java/com/matrixcode/persistence/application/JdbcDeploymentTargetRepository.java`
- 修改：`server/src/test/java/com/matrixcode/persistence/JdbcPersistenceSpringTest.java`
- 创建：`server/src/test/java/com/matrixcode/persistence/MybatisPlusDeploymentTargetRepositoryTest.java`

## 执行步骤

- [x] **步骤 1：编写失败测试**
  - 新增 `MybatisPlusDeploymentTargetRepositoryTest`。
  - 断言 JDBC 模式下 `DeploymentTargetRepository` Bean 类名包含 `MybatisPlusDeploymentTargetRepository`。
  - 断言保存部署目标后可恢复完整字段，并补齐项目外键。
  - 断言再次保存同 ID 部署目标时走 upsert，不重复插入。
  - 断言 file 模式不创建 `DataSource`，且没有 `DeploymentTargetRepository` Bean。

- [x] **步骤 2：运行测试验证红灯**
  - 命令：`/Users/Masons/Ai/Maven/bin/mvn -Dmaven.repo.local=/Users/Masons/Ai/Maven_Ai_Store -q -pl server -Dtest=MybatisPlusDeploymentTargetRepositoryTest test`
  - 预期：失败，原因是当前 Bean 仍为 `JdbcDeploymentTargetRepository` 或目标 MyBatis-Plus 实现不存在。

- [x] **步骤 3：实现 MyBatis-Plus 实体和 Mapper**
  - 新增 `DeploymentTargetEntity`。
  - 新增 `fromDomain()` 和 `toDomain()`，集中处理领域转换。
  - 新增 `DeploymentTargetMapper`。

- [x] **步骤 4：实现 MyBatis-Plus Repository**
  - 新增 `MybatisPlusDeploymentTargetRepository`。
  - `load()` 按项目、环境名和 ID 稳定排序。
  - `save()` 事务内逐条 upsert，保持部署目标增量保存语义。
  - 写入前补齐项目外键。

- [x] **步骤 5：旧 JDBC Repository 退出 Spring Bean**
  - 删除 `JdbcDeploymentTargetRepository` 的 `@Repository`、`@ConditionalOnProperty`、`@Autowired` 和对应 import。
  - 保留构造器和直接测试。
  - 更新 `JdbcPersistenceSpringTest` 断言 `DeploymentTargetRepository` Bean 为 MyBatis-Plus 实现。

- [x] **步骤 6：运行局部绿灯**
  - 命令：`/Users/Masons/Ai/Maven/bin/mvn -Dmaven.repo.local=/Users/Masons/Ai/Maven_Ai_Store -q -pl server -Dtest=MybatisPlusDeploymentTargetRepositoryTest,JdbcDeploymentTargetRepositoryTest,JdbcPersistenceSpringTest test`

- [x] **步骤 7：运行关联回归**
  - 命令：`/Users/Masons/Ai/Maven/bin/mvn -Dmaven.repo.local=/Users/Masons/Ai/Maven_Ai_Store -q -pl server -Dtest=DeploymentTargetServiceTest,WorkbenchControllerTest,JdbcPersistenceSpringTest,MybatisPlusDeploymentTargetRepositoryTest,JdbcDeploymentTargetRepositoryTest test`

- [x] **步骤 8：运行全量和真实验证**
  - 服务端全量：`/Users/Masons/Ai/Maven/bin/mvn -Dmaven.repo.local=/Users/Masons/Ai/Maven_Ai_Store -q -pl server test`
  - 真实集成：`set -a; source .env.local; set +a; /Users/Masons/Ai/Maven/bin/mvn -Dmaven.repo.local=/Users/Masons/Ai/Maven_Ai_Store -q -pl server -Dmatrixcode.real-runtime-test=true -Dtest=RealRuntimeIntegrationTest test`
  - 真实 API：真实后端 health 和部署目标写读接口。
  - 静态检查：`git diff --check`、敏感信息扫描、H2 正式口径扫描。

- [x] **步骤 9：更新 Obsidian**
  - 新增 `MatrixCode/阶段成果/48 部署目标 MyBatis-Plus 仓储迁移.md`。
  - 更新项目首页、项目总览、阶段索引、模块地图、技术栈与运行约定、验证与风险。

## 回溯对齐

- 对齐用户要求：正式 ORM 使用 MyBatis-Plus，正式业务数据使用 MySQL。
- 对齐当前架构：沿用第 46、47 阶段 MyBatis-Plus 单表迁移模板。
- 对齐真实可上线目标：不改变 API/UI 契约，只替换正式仓储实现。

## 完成记录

- 红灯记录：
  - 首次测试使用不存在的部署状态枚举，属于测试编译错误，已修正为有效状态枚举。
  - 有效红灯命令：`/Users/Masons/Ai/Maven/bin/mvn -Dmaven.repo.local=/Users/Masons/Ai/Maven_Ai_Store -q -pl server -Dtest=MybatisPlusDeploymentTargetRepositoryTest test`。
  - 失败点符合预期：`DeploymentTargetRepository` Bean 仍为 `JdbcDeploymentTargetRepository`，证明测试能捕获未迁移状态。
- 局部绿灯：`/Users/Masons/Ai/Maven/bin/mvn -Dmaven.repo.local=/Users/Masons/Ai/Maven_Ai_Store -q -pl server -Dtest=MybatisPlusDeploymentTargetRepositoryTest,JdbcDeploymentTargetRepositoryTest,JdbcPersistenceSpringTest test` 通过。
- 关联回归：`/Users/Masons/Ai/Maven/bin/mvn -Dmaven.repo.local=/Users/Masons/Ai/Maven_Ai_Store -q -pl server -Dtest=DeploymentTargetServiceTest,WorkbenchControllerTest,JdbcPersistenceSpringTest,MybatisPlusDeploymentTargetRepositoryTest,JdbcDeploymentTargetRepositoryTest test` 通过。
- 服务端全量：`/Users/Masons/Ai/Maven/bin/mvn -Dmaven.repo.local=/Users/Masons/Ai/Maven_Ai_Store -q -pl server test` 通过，Surefire 汇总为 `files=77 tests=350 failures=0 errors=0 skipped=5`。
- 真实集成：`set -a; source .env.local; set +a; /Users/Masons/Ai/Maven/bin/mvn -Dmaven.repo.local=/Users/Masons/Ai/Maven_Ai_Store -q -pl server -Dmatrixcode.real-runtime-test=true -Dtest=RealRuntimeIntegrationTest test` 通过，真实 MySQL `matrix_code` Flyway 校验 12 个迁移，schema `45.2`。
- 真实后端：`./scripts/run-real-local.sh` 在 `18080` 启动成功，`/actuator/health` 返回 `{"status":"UP"}`。
- 真实 API 写读：
  - 写入部署目标 `1f806cca-d5cf-4982-8984-402d371ebed2`，环境名 `第48阶段验证环境`。
  - `/api/projects/demo/workbench` 读回该目标，字段包含 `environmentUrl`、`sshAddress`、`deployNote`、`healthCheckUrl`、`rollbackNote`、`status=RECORDED`、`remoteExecuted=false`。
- 真实持久表验证：`matrixcode_deployment_targets` 查询到 `1f806cca-d5cf-4982-8984-402d371ebed2|demo|第48阶段验证环境|https://stage48.example.com|RECORDED|false`。
- 静态扫描：
  - `git diff --check` 无输出。
  - 精确敏感信息扫描无命中。
  - 正式 H2 口径扫描仅命中测试依赖和兼容性注释，未发现正式运行配置使用 H2。
- 回溯结论：
  - 对齐最初需求：正式业务数据继续使用 MySQL，部署目标主路径已从 JDBC 迁移到 MyBatis-Plus。
  - 对齐上线目标：真实后端、真实数据库和真实 API 已验证。
  - 仍需继续推进：剩余 JDBC 主路径包括部署运行态、项目身份、项目活动、工作流进度、文档和 Bug 仓储等。
