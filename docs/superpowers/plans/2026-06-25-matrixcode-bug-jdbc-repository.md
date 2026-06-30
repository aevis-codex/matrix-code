# Bug 正式 MySQL 仓储实现计划

> **面向 AI 代理的工作者：** 必需子技能：使用 superpowers:subagent-driven-development（推荐）或 superpowers:executing-plans 逐任务实现此计划。步骤使用复选框（`- [ ]`）语法来跟踪进度。

**目标：** 让 Bug 服务在 JDBC 模式下优先读写正式 `matrixcode_bugs` 表，并兼容从快照回填。

**架构：** 新增 Bug 仓储接口和 JDBC 实现；`BugService` 使用可选正式仓储；新增 Flyway 迁移补齐 Bug 领域字段。

**技术栈：** Java 21、Spring Boot、Flyway、H2 MySQL 模式、JUnit 5。

---

## 文件结构

- 创建 `server/src/main/java/com/matrixcode/bug/application/BugRepository.java`
- 创建 `server/src/main/java/com/matrixcode/persistence/application/JdbcBugRepository.java`
- 创建 `server/src/test/java/com/matrixcode/persistence/JdbcBugRepositoryTest.java`
- 修改 `server/src/main/java/com/matrixcode/bug/application/BugService.java`
- 修改 `server/src/test/java/com/matrixcode/bug/BugServiceTest.java`
- 修改 `server/src/test/java/com/matrixcode/persistence/JdbcPersistenceSpringTest.java`
- 创建 `server/src/main/resources/db/migration/V29_1__extend_bugs_for_workflow.sql`

## 任务 1：正式 Bug 仓储

- [x] **步骤 1：编写失败的 JDBC 仓储测试**

测试 `JdbcBugRepository` 保存并恢复完整 `ProjectBug` 字段，并自动创建项目行。

- [x] **步骤 2：运行仓储测试验证失败**

运行：`/Users/Masons/Ai/Maven/bin/mvn -Dmaven.repo.local=/Users/Masons/Ai/Maven_Ai_Store -q -pl server -Dtest=JdbcBugRepositoryTest test`

预期：FAIL，缺少 `JdbcBugRepository`。

- [x] **步骤 3：实现最少仓储和迁移**

实现 `BugRepository`、`JdbcBugRepository` 和 `V29_1__extend_bugs_for_workflow.sql`。

- [x] **步骤 4：运行仓储测试验证通过**

运行同上命令，预期 PASS。

## 任务 2：BugService 正式仓储优先

- [x] **步骤 1：编写失败的服务测试**

新增测试：
- 正式仓储已有 Bug 时，`BugService` 优先恢复正式仓储。
- 正式仓储为空、快照中有 Bug 时，`BugService` 回填正式仓储。

- [x] **步骤 2：运行服务测试验证失败**

运行：`/Users/Masons/Ai/Maven/bin/mvn -Dmaven.repo.local=/Users/Masons/Ai/Maven_Ai_Store -q -pl server -Dtest=BugServiceTest test`

预期：FAIL，`BugService` 不支持正式仓储。

- [x] **步骤 3：实现最少服务改造**

让 `BugService` 延迟加载，并按正式仓储优先、快照回填、正式仓储存在时写正式表的规则保存。

- [x] **步骤 4：运行服务测试验证通过**

运行同上命令，预期 PASS。

## 任务 3：Spring JDBC 回归、全量验证和图谱

- [x] **步骤 1：补 Spring JDBC 回归断言**

在 `JdbcPersistenceSpringTest` 中断言 JDBC 上下文启用 `JdbcBugRepository`，重启后 Bug 从正式表恢复，`matrixcode_bugs` 表有记录。

- [x] **步骤 2：运行 Spring 回归验证通过**

运行：`/Users/Masons/Ai/Maven/bin/mvn -Dmaven.repo.local=/Users/Masons/Ai/Maven_Ai_Store -q -pl server -Dtest=JdbcPersistenceSpringTest test`

- [x] **步骤 3：运行服务端全量测试**

运行：`/Users/Masons/Ai/Maven/bin/mvn -Dmaven.repo.local=/Users/Masons/Ai/Maven_Ai_Store -q -pl server test`

- [x] **步骤 4：提交前检查**

运行：`git diff --check`，并对真实密钥片段做精确扫描。

- [x] **步骤 5：更新 Obsidian**

新增第 29 阶段成果页，并更新首页、总览、阶段索引、模块地图、验证风险、状态持久化和测试/Bug 相关页。

- [x] **步骤 6：提交**

运行：`git commit -m "feat: 增加 Bug JDBC 仓储"`

---

## 执行记录

- 仓储红灯：`JdbcBugRepositoryTest` 编译失败，缺少 `JdbcBugRepository`。
- 仓储绿灯：`/Users/Masons/Ai/Maven/bin/mvn -Dmaven.repo.local=/Users/Masons/Ai/Maven_Ai_Store -q -pl server -Dtest=JdbcBugRepositoryTest test` 通过，Flyway 成功应用 V18.1、V28.1 和 V29.1。
- 服务层红灯：`BugServiceTest` 编译失败，`BugService` 缺少正式仓储构造路径。
- 服务层绿灯：`/Users/Masons/Ai/Maven/bin/mvn -Dmaven.repo.local=/Users/Masons/Ai/Maven_Ai_Store -q -pl server -Dtest=BugServiceTest test` 通过。
- Spring 回归：`/Users/Masons/Ai/Maven/bin/mvn -Dmaven.repo.local=/Users/Masons/Ai/Maven_Ai_Store -q -pl server -Dtest=JdbcPersistenceSpringTest test` 通过；新增断言确认 JDBC 上下文启用 `JdbcBugRepository`，正式 `matrixcode_bugs` 表有记录。
- 服务端全量：`/Users/Masons/Ai/Maven/bin/mvn -Dmaven.repo.local=/Users/Masons/Ai/Maven_Ai_Store -q -pl server test` 通过；Surefire 汇总 294 条测试。
- 提交前检查：`git diff --check` 无输出；真实密钥片段精确扫描无命中。
- Obsidian：新增 `MatrixCode/阶段成果/29 Bug 正式 MySQL 仓储.md`，并更新项目首页、项目总览、阶段索引、模块地图、验证与风险、状态持久化与数据库迁移、工作流与文档中心和第 28 阶段后续建议。
- 提交：准备提交为 `feat: 增加 Bug JDBC 仓储`。
