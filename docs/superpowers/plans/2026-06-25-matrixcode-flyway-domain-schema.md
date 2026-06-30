# MatrixCode 第十八阶段 Flyway 领域表迁移基础实现计划

> **面向 AI 代理的工作者：** 必需子技能：使用 superpowers:executing-plans 逐任务实现此计划；每个实现任务先写失败测试，再写实现，再运行定向测试。步骤使用复选框（`- [ ]`）语法跟踪进度。

**目标：** 引入 Flyway 迁移基础和第一批 MySQL 生产领域表，让 MatrixCode 从 JDBC JSON 快照过渡到可上线数据库模型的可验证起点。

**架构：** 在现有 `persistence` 包中新增迁移配置、迁移服务和启动 Runner。默认文件模式不执行迁移；只有 `matrixcode.persistence.mode=jdbc` 且 `matrixcode.persistence.jdbc.migrate-on-startup=true` 时，才执行 `classpath:db/migration` 下的 Flyway 脚本。

**技术栈：** Java 21、Spring Boot、Flyway、JDBC DriverManager、MySQL JDBC Driver、H2 MySQL 兼容模式、JUnit 5、AssertJ。后续按实际需求接入 Milvus、Redis、RocketMQ，本阶段不引入它们的运行依赖。

---

## 文件结构

- 修改：`server/pom.xml`
- 修改：`server/src/main/resources/application.yml`
- 修改：`server/src/main/java/com/matrixcode/persistence/application/PersistenceModeProperties.java`
- 创建：`server/src/main/java/com/matrixcode/persistence/application/DatabaseMigrationService.java`
- 创建：`server/src/main/java/com/matrixcode/persistence/application/DatabaseMigrationRunner.java`
- 创建：`server/src/main/resources/db/migration/V18_1__create_core_domain_tables.sql`
- 创建：`server/src/test/java/com/matrixcode/persistence/DatabaseMigrationServiceTest.java`
- 创建：`server/src/test/java/com/matrixcode/persistence/DatabaseMigrationRunnerTest.java`
- 修改：`docs/development/local-run.md`
- 修改：`docs/superpowers/plans/2026-06-25-matrixcode-flyway-domain-schema.md`

## 任务 1：Flyway 依赖、配置和迁移服务红绿

**文件：**
- 修改：`server/pom.xml`
- 修改：`server/src/main/resources/application.yml`
- 修改：`server/src/main/java/com/matrixcode/persistence/application/PersistenceModeProperties.java`
- 创建：`server/src/main/java/com/matrixcode/persistence/application/DatabaseMigrationService.java`
- 创建：`server/src/test/java/com/matrixcode/persistence/DatabaseMigrationServiceTest.java`

- [x] **步骤 1：编写失败测试**

创建 `DatabaseMigrationServiceTest`，先写空 JDBC URL 错误测试：

```java
@Test
void Jdbc迁移开启时要求JdbcUrl不能为空() {
    var properties = new PersistenceModeProperties();
    properties.setMode("jdbc");
    properties.getJdbc().setMigrateOnStartup(true);

    var service = new DatabaseMigrationService(properties);

    assertThatThrownBy(service::migrate)
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("JDBC 迁移 URL 不能为空");
}
```

- [x] **步骤 2：运行测试验证失败**

```bash
/Users/Masons/Ai/Maven/bin/mvn -Dmaven.repo.local=/Users/Masons/Ai/Maven_Ai_Store -q -pl server -Dtest=DatabaseMigrationServiceTest test
```

预期：编译失败，提示 `DatabaseMigrationService` 或 `setMigrateOnStartup` 不存在。

- [x] **步骤 3：实现依赖、配置和迁移服务**

`server/pom.xml` 增加：

```xml
<dependency>
    <groupId>org.flywaydb</groupId>
    <artifactId>flyway-core</artifactId>
</dependency>
<dependency>
    <groupId>org.flywaydb</groupId>
    <artifactId>flyway-mysql</artifactId>
</dependency>
<dependency>
    <groupId>com.mysql</groupId>
    <artifactId>mysql-connector-j</artifactId>
    <scope>runtime</scope>
</dependency>
```

`PersistenceModeProperties.Jdbc` 增加 `boolean migrateOnStartup`。

`application.yml` 增加：

```yaml
matrixcode:
  persistence:
    mode: ${MATRIXCODE_PERSISTENCE_MODE:file}
    jdbc:
      url: ${MATRIXCODE_PERSISTENCE_JDBC_URL:}
      username: ${MATRIXCODE_PERSISTENCE_JDBC_USERNAME:}
      password: ${MATRIXCODE_PERSISTENCE_JDBC_PASSWORD:}
      table-name: ${MATRIXCODE_PERSISTENCE_JDBC_TABLE_NAME:matrixcode_state_snapshots}
      migrate-on-startup: ${MATRIXCODE_PERSISTENCE_JDBC_MIGRATE_ON_STARTUP:false}
```

`DatabaseMigrationService.migrate()` 使用 Flyway API：

```java
Flyway.configure()
        .dataSource(jdbc.getUrl(), jdbc.getUsername(), jdbc.getPassword())
        .locations("classpath:db/migration")
        .load()
        .migrate();
```

- [x] **步骤 4：运行定向测试验证通过**

```bash
/Users/Masons/Ai/Maven/bin/mvn -Dmaven.repo.local=/Users/Masons/Ai/Maven_Ai_Store -q -pl server -Dtest=DatabaseMigrationServiceTest test
```

- [x] **步骤 5：Commit**

```bash
git add server/pom.xml \
  server/src/main/resources/application.yml \
  server/src/main/java/com/matrixcode/persistence/application/PersistenceModeProperties.java \
  server/src/main/java/com/matrixcode/persistence/application/DatabaseMigrationService.java \
  server/src/test/java/com/matrixcode/persistence/DatabaseMigrationServiceTest.java
git commit -m "feat(服务端): 添加 Flyway 迁移配置"
```

## 任务 2：第一批领域表迁移脚本

**文件：**
- 创建：`server/src/main/resources/db/migration/V18_1__create_core_domain_tables.sql`
- 修改：`server/src/test/java/com/matrixcode/persistence/DatabaseMigrationServiceTest.java`

- [x] **步骤 1：补充失败测试**

在 `DatabaseMigrationServiceTest` 增加 H2 MySQL 模式测试。执行 `service.migrate()` 后，通过 `DatabaseMetaData` 验证以下表存在：

- `flyway_schema_history`
- `matrixcode_users`
- `matrixcode_projects`
- `matrixcode_project_members`
- `matrixcode_role_agent_configs`
- `matrixcode_collaboration_sessions`
- `matrixcode_collaboration_participants`
- `matrixcode_documents`
- `matrixcode_bugs`
- `matrixcode_deployment_targets`
- `matrixcode_runtime_notifications`
- `matrixcode_local_execution_tasks`
- `matrixcode_audit_records`
- `matrixcode_project_events`

同一个数据库连续执行两次 `migrate()`，第二次应成功。

- [x] **步骤 2：运行测试验证失败**

```bash
/Users/Masons/Ai/Maven/bin/mvn -Dmaven.repo.local=/Users/Masons/Ai/Maven_Ai_Store -q -pl server -Dtest=DatabaseMigrationServiceTest test
```

预期：测试失败，提示核心领域表不存在。

- [x] **步骤 3：实现迁移脚本**

创建 `V18_1__create_core_domain_tables.sql`，使用 MySQL/H2 兼容 SQL 创建表。字段必须包含主键、项目归属、状态、创建/更新时间和必要索引。当前阶段不使用 MySQL `json` 类型。

- [x] **步骤 4：运行定向测试验证通过**

```bash
/Users/Masons/Ai/Maven/bin/mvn -Dmaven.repo.local=/Users/Masons/Ai/Maven_Ai_Store -q -pl server -Dtest=DatabaseMigrationServiceTest test
```

- [x] **步骤 5：Commit**

```bash
git add server/src/main/resources/db/migration/V18_1__create_core_domain_tables.sql \
  server/src/test/java/com/matrixcode/persistence/DatabaseMigrationServiceTest.java
git commit -m "feat(服务端): 添加核心领域表迁移脚本"
```

## 任务 3：启动迁移 Runner

**文件：**
- 创建：`server/src/main/java/com/matrixcode/persistence/application/DatabaseMigrationRunner.java`
- 创建：`server/src/test/java/com/matrixcode/persistence/DatabaseMigrationRunnerTest.java`

- [x] **步骤 1：编写失败测试**

`DatabaseMigrationRunnerTest` 使用一个计数迁移服务：

- 默认 `mode=file` 时调用 `run()`，计数仍为 0。
- `mode=jdbc` 但 `migrateOnStartup=false` 时调用 `run()`，计数仍为 0。
- `mode=jdbc` 且 `migrateOnStartup=true` 时调用 `run()`，计数为 1。

- [x] **步骤 2：运行测试验证失败**

```bash
/Users/Masons/Ai/Maven/bin/mvn -Dmaven.repo.local=/Users/Masons/Ai/Maven_Ai_Store -q -pl server -Dtest=DatabaseMigrationRunnerTest test
```

预期：编译失败，提示 `DatabaseMigrationRunner` 不存在。

- [x] **步骤 3：实现 Runner**

`DatabaseMigrationRunner` 实现 `ApplicationRunner`。判断逻辑：

```java
if ("jdbc".equalsIgnoreCase(properties.getMode()) && properties.getJdbc().isMigrateOnStartup()) {
    migrationService.migrate();
}
```

- [x] **步骤 4：运行定向测试验证通过**

```bash
/Users/Masons/Ai/Maven/bin/mvn -Dmaven.repo.local=/Users/Masons/Ai/Maven_Ai_Store -q -pl server -Dtest=DatabaseMigrationRunnerTest test
```

- [x] **步骤 5：Commit**

```bash
git add server/src/main/java/com/matrixcode/persistence/application/DatabaseMigrationRunner.java \
  server/src/test/java/com/matrixcode/persistence/DatabaseMigrationRunnerTest.java
git commit -m "feat(服务端): 支持启动时执行数据库迁移"
```

## 任务 4：集成验证、文档和 Obsidian 图谱

**文件：**
- 修改：`docs/development/local-run.md`
- 修改：`docs/superpowers/plans/2026-06-25-matrixcode-flyway-domain-schema.md`
- 修改 Obsidian：
  - `MatrixCode/1 项目首页.md`
  - `MatrixCode/3 阶段索引.md`
  - `MatrixCode/4 模块地图.md`
  - `MatrixCode/6 验证与风险.md`
  - `MatrixCode/14 状态持久化与数据库迁移.md`
  - `MatrixCode/阶段成果/18 第十八阶段进度待补证据.md` 或重命名为 `18 Flyway 领域表迁移基础.md`

- [x] **步骤 1：运行服务端全量测试**

```bash
/Users/Masons/Ai/Maven/bin/mvn -Dmaven.repo.local=/Users/Masons/Ai/Maven_Ai_Store -q -pl server test
```

- [x] **步骤 2：运行桌面端验证**

```bash
cd desktop
npm test
npm run build
npm run tauri:build -- --help
```

- [x] **步骤 3：更新本地运行文档**

在 `docs/development/local-run.md` 增加第十八阶段说明：

- 默认不执行 Flyway。
- JDBC 模式开启迁移的环境变量。
- 迁移只建表，不切换业务仓储。
- 业务数据基线为 MySQL；向量库 Milvus、缓存 Redis、消息 RocketMQ 按后续真实需求接入。
- 第十八阶段已建立角色智能体配置表，承接系统提示词、用户提示词模板、模型配置、颜色和字体。
- 真实数据库地址由用户最终提供后再跑。

- [x] **步骤 4：更新计划执行记录**

记录红绿测试、服务端全量、桌面端验证和 Obsidian 更新结果。

- [x] **步骤 5：更新 Obsidian 项目图谱**

将第十八阶段从“待补证据”改为已完成阶段，补齐阶段成果页、阶段索引、模块地图、持久化模块和项目首页。

- [x] **步骤 6：文档和空白检查**

```bash
rg "mv[n] -q|mv[n] test|mv[n] spring-boot:run" docs/superpowers/plans/2026-06-25-matrixcode-flyway-domain-schema.md docs/development/local-run.md -n
git diff --check
```

- [x] **步骤 7：Commit**

```bash
git add docs/development/local-run.md \
  docs/superpowers/plans/2026-06-25-matrixcode-flyway-domain-schema.md
git commit -m "docs: 记录第十八阶段数据库迁移验证"
```

## 执行记录

- 任务 1 红灯：`/Users/Masons/Ai/Maven/bin/mvn -Dmaven.repo.local=/Users/Masons/Ai/Maven_Ai_Store -q -pl server -Dtest=DatabaseMigrationServiceTest test` 编译失败，原因是 `DatabaseMigrationService` 与 `setMigrateOnStartup(boolean)` 不存在，符合预期。
- 任务 1 绿灯：同一命令通过，验证 MySQL/Flyway 依赖解析、迁移开关配置和空 JDBC URL 防护。
- 用户补充架构基线后，阶段规格和计划已调整为 MySQL；Milvus、Redis、RocketMQ 作为后续按需接入的基础设施，不在本阶段引入运行依赖。
- 任务 2 红灯：`/Users/Masons/Ai/Maven/bin/mvn -Dmaven.repo.local=/Users/Masons/Ai/Maven_Ai_Store -q -pl server -Dtest=DatabaseMigrationServiceTest test` 失败，Flyway 只创建 `flyway_schema_history`，核心领域表不存在，符合预期。
- 任务 2 绿灯：同一命令通过，H2 MySQL 模式成功应用 `V18_1__create_core_domain_tables.sql`，第二次 `migrate()` 判定 schema 已是最新。
- 任务 3 红灯：`/Users/Masons/Ai/Maven/bin/mvn -Dmaven.repo.local=/Users/Masons/Ai/Maven_Ai_Store -q -pl server -Dtest=DatabaseMigrationRunnerTest test` 编译失败，原因是 `DatabaseMigrationRunner` 不存在，符合预期。
- 任务 3 绿灯：同一命令通过，验证默认文件模式不迁移、JDBC 但开关关闭不迁移、JDBC 且开关打开时迁移一次。
- 集成调试：服务端全量测试首次发现 `JdbcPersistenceSpringTest` 误走文件 Store。根因是 `application.yml` 新增默认 `matrixcode.persistence.mode=file` 后，测试中的 `SpringApplicationBuilder.properties(...)` 优先级低于配置文件。修复为使用命令行参数覆盖 JDBC 模式，并增加三类 JDBC Store 注入断言。
- 集成验证：`/Users/Masons/Ai/Maven/bin/mvn -Dmaven.repo.local=/Users/Masons/Ai/Maven_Ai_Store -q -pl server test` 通过，46 份 Surefire 报告、257 条测试、0 失败、0 错误、0 跳过。
- 桌面验证：`npm test` 通过，3 个测试文件、69 条测试；`npm run build` 通过；`npm run tauri:build -- --help` 通过。
- Compose 验证：`docker compose config` 通过，本地依赖已切到 MySQL 8.4 + Redis 8。
- 文档验证：`docs/development/local-run.md` 已改为 MySQL JDBC 示例，并新增第十八阶段 Flyway 迁移验证说明；相关第十八阶段文档无旧数据库配置残留。
- Obsidian 图谱：已将 `MatrixCode/阶段成果/18 第十八阶段进度待补证据.md` 重命名为 `18 MySQL Flyway 领域表迁移基础.md`，并更新项目首页、项目总览、阶段索引、模块地图、技术栈、验证风险和持久化模块页。
