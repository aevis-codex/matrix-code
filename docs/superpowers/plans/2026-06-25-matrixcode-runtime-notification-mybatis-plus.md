# MatrixCode 第 47 阶段计划：运行态提醒 MyBatis-Plus 仓储迁移

> **面向 AI 代理的工作者：** 按红绿重构执行。每步完成后更新复选框，并在阶段结束后更新 Obsidian 项目图谱。

**目标：** 将运行态提醒正式仓储从 Spring Bean 主路径上的手写 JDBC 迁移到 MyBatis-Plus。

**架构：** 新增 `RuntimeNotificationEntity`、`RuntimeNotificationMapper` 和 `MybatisPlusRuntimeNotificationStore`。旧 `JdbcRuntimeNotificationStore` 保留直接实例化测试，但退出 Spring Bean。

**技术栈：** Java 21、Spring Boot 3.5.15、MyBatis-Plus 3.5.12、Flyway、H2 MySQL mode 测试库、真实 MySQL `matrix_code`。

---

## 文件结构

- 创建：`server/src/main/java/com/matrixcode/persistence/mybatis/entity/RuntimeNotificationEntity.java`
- 创建：`server/src/main/java/com/matrixcode/persistence/mybatis/mapper/RuntimeNotificationMapper.java`
- 创建：`server/src/main/java/com/matrixcode/persistence/application/MybatisPlusRuntimeNotificationStore.java`
- 修改：`server/src/main/java/com/matrixcode/persistence/application/JdbcRuntimeNotificationStore.java`
- 创建：`server/src/test/java/com/matrixcode/persistence/MybatisPlusRuntimeNotificationStoreTest.java`

## 执行步骤

- [x] **步骤 1：编写失败测试**
  - 新增 `MybatisPlusRuntimeNotificationStoreTest`。
  - 断言 JDBC 模式下 `RuntimeNotificationStore` Bean 类名包含 `MybatisPlusRuntimeNotificationStore`。
  - 断言保存提醒后可恢复、正式表有记录、旧快照不写入。
  - 断言正式表为空时能从旧快照回填。
  - 断言 file 模式没有 `DataSource`，且 `RuntimeNotificationStore` 仍为文件实现。

- [x] **步骤 2：运行测试验证红灯**
  - 命令：`/Users/Masons/Ai/Maven/bin/mvn -Dmaven.repo.local=/Users/Masons/Ai/Maven_Ai_Store -q -pl server -Dtest=MybatisPlusRuntimeNotificationStoreTest test`
  - 预期：失败，原因是当前 Bean 仍为 `JdbcRuntimeNotificationStore` 或目标 MyBatis-Plus 实现不存在。

- [x] **步骤 3：实现 MyBatis-Plus 实体和 Mapper**
  - 新增 `RuntimeNotificationEntity`。
  - 新增 `fromDomain()` 和 `toDomain()`，集中处理领域转换。
  - 新增 `RuntimeNotificationMapper`。

- [x] **步骤 4：实现 MyBatis-Plus Store**
  - 新增 `MybatisPlusRuntimeNotificationStore`。
  - `load()` 读取正式表，空表时从旧快照回填。
  - `save()` 事务内清空正式表并批量写入。
  - 写入前补齐项目和用户外键。

- [x] **步骤 5：旧 JDBC Store 退出 Spring Bean**
  - 删除 `JdbcRuntimeNotificationStore` 的 `@Service`、`@ConditionalOnProperty` 和对应 import。
  - 保留构造器和直接测试。

- [x] **步骤 6：运行局部绿灯**
  - 命令：`/Users/Masons/Ai/Maven/bin/mvn -Dmaven.repo.local=/Users/Masons/Ai/Maven_Ai_Store -q -pl server -Dtest=MybatisPlusRuntimeNotificationStoreTest,JdbcRuntimeNotificationStoreTest test`

- [x] **步骤 7：运行关联回归**
  - 命令：`/Users/Masons/Ai/Maven/bin/mvn -Dmaven.repo.local=/Users/Masons/Ai/Maven_Ai_Store -q -pl server -Dtest=RuntimeNotificationServiceTest,WorkbenchControllerTest,JdbcPersistenceSpringTest test`

- [x] **步骤 8：运行全量和真实验证**
  - 服务端全量：`/Users/Masons/Ai/Maven/bin/mvn -Dmaven.repo.local=/Users/Masons/Ai/Maven_Ai_Store -q -pl server test`
  - 真实集成：`set -a; source .env.local; set +a; /Users/Masons/Ai/Maven/bin/mvn -Dmaven.repo.local=/Users/Masons/Ai/Maven_Ai_Store -q -pl server -Dmatrixcode.real-runtime-test=true -Dtest=RealRuntimeIntegrationTest test`
  - 真实 API：真实后端 health 和运行态提醒已读接口。
  - 静态检查：`git diff --check`、敏感信息扫描、H2 正式口径扫描。

- [x] **步骤 9：更新 Obsidian**
  - 新增 `MatrixCode/阶段成果/47 运行态提醒 MyBatis-Plus 仓储迁移.md`。
  - 更新项目首页、项目总览、阶段索引、模块地图、技术栈与运行约定、验证与风险。

## 回溯对齐

- 对齐用户要求：正式 ORM 使用 MyBatis-Plus，正式业务数据使用 MySQL。
- 对齐当前架构：沿用第 42 和第 46 阶段 MyBatis-Plus 模板。
- 对齐真实可上线目标：不改变 API/UI 契约，只替换正式仓储实现，保留旧快照回填。

## 完成记录

- 红灯验证：`MybatisPlusRuntimeNotificationStoreTest` 初始失败，失败点为 JDBC 模式下 `RuntimeNotificationStore` Bean 仍是 `JdbcRuntimeNotificationStore`。
- 局部绿灯：`MybatisPlusRuntimeNotificationStoreTest`、`JdbcRuntimeNotificationStoreTest`、`JdbcPersistenceSpringTest` 通过。
- 关联回归：`RuntimeNotificationServiceTest`、`WorkbenchControllerTest`、`JdbcPersistenceSpringTest`、`MybatisPlusRuntimeNotificationStoreTest`、`JdbcRuntimeNotificationStoreTest` 通过。
- 全量回归：`/Users/Masons/Ai/Maven/bin/mvn -Dmaven.repo.local=/Users/Masons/Ai/Maven_Ai_Store -q -pl server test` 通过，Surefire 汇总为 `files=76 tests=347 failures=0 errors=0 skipped=5`。
- 真实集成：`RealRuntimeIntegrationTest` 通过；真实 MySQL `matrix_code` Flyway 校验 12 个迁移，schema 当前版本为 `45.2`。
- 真实 API：重启真实后端后，`/actuator/health` 返回 `UP`；工作台生成运行态提醒 `approval:87ba136c-6139-429f-8951-1c1d8c67a151`；单条已读接口写入 `readByUserId=stage47-runtime-notification-check`。
- 持久化查询：`matrixcode_runtime_notifications` 中该提醒记录 `project_id=demo`、`user_id=stage47-runtime-notification-check`、`read_at` 非空。
- 静态检查：`git diff --check` 通过；精确密钥扫描无命中；正式 H2 口径扫描仅剩测试依赖和兼容注释。
- 验证数据清理：第 47 阶段创建的本地命令验证任务已通过审批拒绝退出待审批状态，工作台 `activeTasks=0`，最近任务状态为 `DENIED`。
