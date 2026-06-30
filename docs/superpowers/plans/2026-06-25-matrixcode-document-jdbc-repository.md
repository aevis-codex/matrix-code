# 文档中心正式 MySQL 仓储实现计划

> **面向 AI 代理的工作者：** 必需子技能：使用 superpowers:subagent-driven-development（推荐）或 superpowers:executing-plans 逐任务实现此计划。步骤使用复选框（`- [ ]`）语法来跟踪进度。

**目标：** 让文档中心在 JDBC 模式下优先读写正式 `matrixcode_documents` 表，并兼容从快照回填。

**架构：** 新增文档仓储接口和 JDBC 实现；`DocumentService` 使用可选正式仓储，正式仓储为空时从 `WorkbenchStateStore` 回填；新增 Flyway 迁移补齐文档版本字段。

**技术栈：** Java 21、Spring Boot、Flyway、H2 MySQL 模式、JUnit 5。

---

## 文件结构

- 创建 `server/src/main/java/com/matrixcode/document/application/DocumentRepository.java`
- 创建 `server/src/main/java/com/matrixcode/persistence/application/JdbcDocumentRepository.java`
- 创建 `server/src/test/java/com/matrixcode/persistence/JdbcDocumentRepositoryTest.java`
- 修改 `server/src/main/java/com/matrixcode/document/application/DocumentService.java`
- 修改 `server/src/test/java/com/matrixcode/document/DocumentServiceTest.java`
- 修改 `server/src/test/java/com/matrixcode/persistence/JdbcPersistenceSpringTest.java`
- 创建 `server/src/main/resources/db/migration/V28_1__extend_documents_for_versions.sql`

## 任务 1：正式文档仓储

**文件：**
- 创建：`server/src/main/java/com/matrixcode/document/application/DocumentRepository.java`
- 创建：`server/src/main/java/com/matrixcode/persistence/application/JdbcDocumentRepository.java`
- 创建测试：`server/src/test/java/com/matrixcode/persistence/JdbcDocumentRepositoryTest.java`
- 创建迁移：`server/src/main/resources/db/migration/V28_1__extend_documents_for_versions.sql`

- [x] **步骤 1：编写失败的 JDBC 仓储测试**

测试保存并恢复：
- `DocumentType.PRD`
- `DocumentState.FROZEN`
- `parentVersionId`
- `frozenBy`
- `frozenAt`
- 自动创建 `matrixcode_projects` 行

- [x] **步骤 2：运行仓储测试验证失败**

运行：`/Users/Masons/Ai/Maven/bin/mvn -Dmaven.repo.local=/Users/Masons/Ai/Maven_Ai_Store -q -pl server -Dtest=JdbcDocumentRepositoryTest test`

预期：FAIL，缺少 `JdbcDocumentRepository` 或新增迁移字段。

- [x] **步骤 3：实现最少仓储和迁移**

实现：
- `DocumentRepository.load()`
- `DocumentRepository.save(List<DocumentVersion>)`
- `JdbcDocumentRepository` 的 load/upsert/ensureProject
- `V28_1__extend_documents_for_versions.sql`

- [x] **步骤 4：运行仓储测试验证通过**

运行同上命令，预期 PASS。

## 任务 2：DocumentService 正式仓储优先

**文件：**
- 修改：`server/src/main/java/com/matrixcode/document/application/DocumentService.java`
- 修改测试：`server/src/test/java/com/matrixcode/document/DocumentServiceTest.java`

- [x] **步骤 1：编写失败的服务测试**

新增测试：
- 当正式仓储已有文档时，`DocumentService` 从正式仓储恢复。
- 当正式仓储为空、快照中有文档时，`DocumentService` 从快照恢复并回填正式仓储。

- [x] **步骤 2：运行服务测试验证失败**

运行：`/Users/Masons/Ai/Maven/bin/mvn -Dmaven.repo.local=/Users/Masons/Ai/Maven_Ai_Store -q -pl server -Dtest=DocumentServiceTest test`

预期：FAIL，`DocumentService` 不接受正式仓储或不会回填。

- [x] **步骤 3：实现最少服务改造**

让 `DocumentService`：
- 接收 `ObjectProvider<DocumentRepository>`。
- 延迟加载文档。
- 正式仓储有数据时优先使用正式仓储。
- 正式仓储为空时读取快照并回填。
- 正式仓储存在时写正式表，否则写快照。

- [x] **步骤 4：运行服务测试验证通过**

运行同上命令，预期 PASS。

## 任务 3：Spring JDBC 模式回归

**文件：**
- 修改测试：`server/src/test/java/com/matrixcode/persistence/JdbcPersistenceSpringTest.java`

- [x] **步骤 1：编写失败的 Spring 回归断言**

断言 JDBC Spring 上下文中存在正式文档仓储，重启后文档仍恢复，并且 `matrixcode_documents` 表中至少有产品文档和交付文档记录。

- [x] **步骤 2：运行 Spring 回归验证失败或暴露缺口**

运行：`/Users/Masons/Ai/Maven/bin/mvn -Dmaven.repo.local=/Users/Masons/Ai/Maven_Ai_Store -q -pl server -Dtest=JdbcPersistenceSpringTest test`

- [x] **步骤 3：修正 Spring 集成缺口**

根据失败结果补齐 Bean 条件、迁移字段或测试数据。

- [x] **步骤 4：运行 Spring 回归验证通过**

运行同上命令，预期 PASS。

## 任务 4：全量验证和图谱记录

- [x] **步骤 1：运行服务端全量测试**

运行：`/Users/Masons/Ai/Maven/bin/mvn -Dmaven.repo.local=/Users/Masons/Ai/Maven_Ai_Store -q -pl server test`

- [x] **步骤 2：提交前检查**

运行：`git diff --check`，并对真实密钥片段做精确扫描，确认无命中。

- [x] **步骤 3：更新 Obsidian**

新增第 28 阶段成果页，并更新首页、总览、阶段索引、模块地图、验证风险、工作流与文档中心、状态持久化页。

- [x] **步骤 4：提交**

运行：`git commit -m "feat: 增加文档中心 JDBC 仓储"`

---

## 执行记录

- 仓储红灯：`JdbcDocumentRepositoryTest` 编译失败，缺少 `JdbcDocumentRepository`。
- 仓储绿灯：`/Users/Masons/Ai/Maven/bin/mvn -Dmaven.repo.local=/Users/Masons/Ai/Maven_Ai_Store -q -pl server -Dtest=JdbcDocumentRepositoryTest test` 通过，Flyway 成功应用 V18.1 和 V28.1。
- 服务层红灯：`DocumentServiceTest` 编译失败，`DocumentService` 缺少正式仓储构造路径。
- 服务层绿灯：`/Users/Masons/Ai/Maven/bin/mvn -Dmaven.repo.local=/Users/Masons/Ai/Maven_Ai_Store -q -pl server -Dtest=DocumentServiceTest test` 通过。
- Spring 回归：`/Users/Masons/Ai/Maven/bin/mvn -Dmaven.repo.local=/Users/Masons/Ai/Maven_Ai_Store -q -pl server -Dtest=JdbcPersistenceSpringTest test` 通过；新增断言确认 JDBC 上下文启用 `JdbcDocumentRepository`，重启后恢复 `PRD` 和 `CODING_AGENT_HANDOFF` 文档，正式 `matrixcode_documents` 表存在对应类型。
- 服务端全量验证：`/Users/Masons/Ai/Maven/bin/mvn -Dmaven.repo.local=/Users/Masons/Ai/Maven_Ai_Store -q -pl server test` 退出码 0，Surefire 汇总 291 条测试。
- 提交前门禁：`git diff --check` 无输出；精确密钥扫描未命中真实密钥或数据库密码。
- Obsidian 图谱：已新增 `MatrixCode/阶段成果/28 文档中心正式 MySQL 仓储.md`，并更新首页、总览、阶段索引、模块地图、验证风险、工作流与文档中心、状态持久化与数据库迁移页。
