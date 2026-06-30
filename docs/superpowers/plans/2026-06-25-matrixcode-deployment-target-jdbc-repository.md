# 部署目标正式 MySQL 仓储实现计划

> **面向 AI 代理的工作者：** 必需子技能：使用 superpowers:subagent-driven-development（推荐）或 superpowers:executing-plans 逐任务实现此计划。步骤使用复选框（`- [ ]`）语法来跟踪进度。

**目标：** 让 JDBC 模式下的部署目标优先读写正式 `matrixcode_deployment_targets` 表，并兼容从旧 `workbench-state` 快照回填。

**架构：** 新增 `DeploymentTargetRepository` 抽象和 `JdbcDeploymentTargetRepository` 实现。`DeploymentTargetService` 按文档、Bug 仓储模式延迟加载：正式表非空优先，正式表为空则从 `WorkbenchStateStore` 恢复并回填。

**技术栈：** Java 21、Spring Boot、Flyway、H2 MySQL 模式、JUnit 5。

---

## 文件结构

- 创建 `server/src/main/java/com/matrixcode/deployment/application/DeploymentTargetRepository.java`
- 创建 `server/src/main/java/com/matrixcode/persistence/application/JdbcDeploymentTargetRepository.java`
- 创建 `server/src/main/resources/db/migration/V32_1__extend_deployment_targets_for_domain_store.sql`
- 创建 `server/src/test/java/com/matrixcode/persistence/JdbcDeploymentTargetRepositoryTest.java`
- 修改 `server/src/main/java/com/matrixcode/deployment/application/DeploymentTargetService.java`
- 修改 `server/src/test/java/com/matrixcode/deployment/DeploymentTargetServiceTest.java`
- 修改 `server/src/test/java/com/matrixcode/persistence/JdbcPersistenceSpringTest.java`
- 创建 `docs/superpowers/specs/2026-06-25-matrixcode-deployment-target-jdbc-repository-design.md`
- 创建 `docs/superpowers/plans/2026-06-25-matrixcode-deployment-target-jdbc-repository.md`

## 任务 1：部署目标仓储红绿

- [x] **步骤 1：编写失败的正式表仓储测试**

在 `JdbcDeploymentTargetRepositoryTest` 中新增：

```java
@Test
void 保存后从正式部署目标表恢复完整字段() throws Exception {
    var jdbcUrl = "jdbc:h2:mem:deployment_targets_" + System.nanoTime()
            + ";MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1";
    migrate(jdbcUrl);
    var repository = new JdbcDeploymentTargetRepository(properties(jdbcUrl));
    var updatedAt = Instant.parse("2026-06-25T12:00:00Z");
    var target = new DeploymentTarget(
            "target-1",
            "demo",
            "预发环境",
            "https://pre.example.com",
            "deploy@example.com",
            "按发布单部署",
            "https://pre.example.com/health",
            "回滚上一版本",
            DeploymentTargetStatus.RELEASE_READY,
            false,
            updatedAt
    );

    repository.save(List.of(target));

    var restored = repository.load();
    assertThat(restored).singleElement().satisfies(value -> {
        assertThat(value.id()).isEqualTo("target-1");
        assertThat(value.projectId()).isEqualTo("demo");
        assertThat(value.environmentName()).isEqualTo("预发环境");
        assertThat(value.environmentUrl()).isEqualTo("https://pre.example.com");
        assertThat(value.sshAddress()).isEqualTo("deploy@example.com");
        assertThat(value.deployNote()).isEqualTo("按发布单部署");
        assertThat(value.healthCheckUrl()).isEqualTo("https://pre.example.com/health");
        assertThat(value.rollbackNote()).isEqualTo("回滚上一版本");
        assertThat(value.status()).isEqualTo(DeploymentTargetStatus.RELEASE_READY);
        assertThat(value.remoteExecuted()).isFalse();
        assertThat(value.updatedAt()).isEqualTo(updatedAt);
    });
    assertThat(projectCount(jdbcUrl)).isEqualTo(1);
}
```

- [x] **步骤 2：运行测试验证失败**

运行：`/Users/Masons/Ai/Maven/bin/mvn -Dmaven.repo.local=/Users/Masons/Ai/Maven_Ai_Store -q -pl server -Dtest=JdbcDeploymentTargetRepositoryTest test`

预期：FAIL，类 `JdbcDeploymentTargetRepository` 或扩展字段尚不存在。

- [x] **步骤 3：新增仓储接口、Flyway 迁移和 JDBC 实现**

新增 `DeploymentTargetRepository`，新增 `V32_1__extend_deployment_targets_for_domain_store.sql`，实现 `JdbcDeploymentTargetRepository`，读写 `matrixcode_deployment_targets` 完整字段。

- [x] **步骤 4：运行仓储测试验证通过**

运行同上命令，预期 PASS。

## 任务 2：服务层正式表优先和旧快照回填

- [x] **步骤 1：编写失败的服务层正式仓储测试**

在 `DeploymentTargetServiceTest` 中增加内存仓储测试：

```java
@Test
void 有正式仓储时部署目标不再写入工作台快照() {
    var store = new InMemoryWorkbenchStateStore();
    var repository = new RecordingDeploymentTargetRepository();
    var service = new DeploymentTargetService(store, repository);

    var target = service.configure("project-1", "测试环境", "https://test.example.com",
            "deploy@example.com", "部署", "https://test.example.com/health", "回滚");

    assertThat(repository.saved).containsExactly(target);
    assertThat(store.load().deploymentTargets()).isEmpty();
}
```

- [x] **步骤 2：编写失败的旧快照回填测试**

在 `DeploymentTargetServiceTest` 中增加：

```java
@Test
void 正式仓储为空时从旧快照恢复并回填() {
    var store = new InMemoryWorkbenchStateStore();
    var legacy = deploymentTarget("target-legacy");
    store.saveDeploymentTargets(List.of(legacy));
    var repository = new RecordingDeploymentTargetRepository();

    var service = new DeploymentTargetService(store, repository);

    assertThat(service.listByProject("project-1")).containsExactly(legacy);
    assertThat(repository.saved).containsExactly(legacy);
}
```

- [x] **步骤 3：运行服务层测试验证失败**

运行：`/Users/Masons/Ai/Maven/bin/mvn -Dmaven.repo.local=/Users/Masons/Ai/Maven_Ai_Store -q -pl server -Dtest=DeploymentTargetServiceTest test`

预期：FAIL，服务层尚未接收正式仓储。

- [x] **步骤 4：改造 `DeploymentTargetService`**

按 `DocumentService` 和 `BugService` 模式注入 `ObjectProvider<DeploymentTargetRepository>`，增加延迟加载、仓储优先、旧快照回填和仓储保存路径。

- [x] **步骤 5：运行服务层测试验证通过**

运行同上命令，预期 PASS。

## 任务 3：Spring 回归、全量验证和图谱

- [x] **步骤 1：补 Spring JDBC 回归断言**

在 `JdbcPersistenceSpringTest` 中断言：

- Spring 上下文中存在 `JdbcDeploymentTargetRepository`。
- 第二轮上下文恢复后，部署目标仍可从工作台视图读取。
- `matrixcode_deployment_targets` 计数等于 1。

- [x] **步骤 2：运行关联测试**

运行：

```bash
/Users/Masons/Ai/Maven/bin/mvn -Dmaven.repo.local=/Users/Masons/Ai/Maven_Ai_Store -q -pl server -Dtest=JdbcDeploymentTargetRepositoryTest,DeploymentTargetServiceTest,JdbcPersistenceSpringTest test
```

- [x] **步骤 3：运行服务端全量测试**

运行：`/Users/Masons/Ai/Maven/bin/mvn -Dmaven.repo.local=/Users/Masons/Ai/Maven_Ai_Store -q -pl server test`

- [x] **步骤 4：提交前检查**

运行 `git diff --check`、计划/规格占位扫描和敏感片段精确扫描。

- [x] **步骤 5：更新 Obsidian**

新增第 32 阶段成果页，并更新首页、总览、阶段索引、模块地图、验证风险、部署健康检查与运维记录、状态持久化与数据库迁移。

- [x] **步骤 6：提交**

运行：`git commit -m "feat: 增加部署目标 JDBC 仓储"`

---

## 执行记录

- 2026-06-25：规格和计划创建完成，进入 TDD 执行。
- 红灯 1：运行 `JdbcDeploymentTargetRepositoryTest`，编译失败，缺少 `JdbcDeploymentTargetRepository`。
- 绿灯 1：新增部署目标仓储接口、Flyway 扩展迁移和 JDBC 实现后，`JdbcDeploymentTargetRepositoryTest` 通过。
- 红灯 2：运行 `DeploymentTargetServiceTest`，编译失败，`DeploymentTargetService` 缺少带正式仓储的构造路径。
- 绿灯 2：改造 `DeploymentTargetService` 为正式仓储优先、旧快照回填后，`DeploymentTargetServiceTest` 通过。
- Spring 回归：补 `JdbcPersistenceSpringTest` 断言后，`JdbcDeploymentTargetRepositoryTest,DeploymentTargetServiceTest,JdbcPersistenceSpringTest` 关联测试通过。
- 服务端全量测试：`/Users/Masons/Ai/Maven/bin/mvn -Dmaven.repo.local=/Users/Masons/Ai/Maven_Ai_Store -q -pl server test` 完成，Surefire 汇总 `Tests run: 298, Failures: 0, Errors: 0, Skipped: 0`。
- 提交前检查：`git diff --check` 通过；计划/规格占位扫描无命中；敏感片段精确扫描无命中。
- Obsidian 图谱：新增 `MatrixCode/阶段成果/32 部署目标正式 MySQL 仓储.md`，并更新首页、总览、阶段索引、模块地图、验证风险、部署健康检查与运维记录、状态持久化与数据库迁移。
- 提交：`feat: 增加部署目标 JDBC 仓储`。
