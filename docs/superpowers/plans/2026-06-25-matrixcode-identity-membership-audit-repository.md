# 身份、成员与用户级审计正式仓储实现计划

> **面向 AI 代理的工作者：** 必需子技能：使用 superpowers:subagent-driven-development（推荐）或 superpowers:executing-plans 逐任务实现此计划。步骤使用复选框（`- [ ]`）语法来跟踪进度。

**目标：** 把用户、项目成员和用户级审计查询接入正式 MySQL 仓储。

**架构：** 新增 identity 领域端口和服务，JDBC 适配器读写 `matrixcode_users`、`matrixcode_project_members`、`matrixcode_audit_records`。本地执行审计写入时确保用户存在并填充 `actor_user_id`。

**技术栈：** Java 21、Spring Boot、Flyway、MySQL/H2 MySQL mode、JUnit 5、AssertJ。

---

## 文件结构

- 创建：`server/src/main/java/com/matrixcode/identity/domain/MatrixUser.java`
- 创建：`server/src/main/java/com/matrixcode/identity/domain/ProjectMember.java`
- 创建：`server/src/main/java/com/matrixcode/identity/domain/UserAuditRecord.java`
- 创建：`server/src/main/java/com/matrixcode/identity/application/ProjectIdentityRepository.java`
- 创建：`server/src/main/java/com/matrixcode/identity/application/ProjectIdentityService.java`
- 创建：`server/src/main/java/com/matrixcode/persistence/application/JdbcProjectIdentityRepository.java`
- 创建：`server/src/test/java/com/matrixcode/persistence/JdbcProjectIdentityRepositoryTest.java`
- 创建：`server/src/test/java/com/matrixcode/localexecution/LocalExecutionIdentityAuditTest.java`
- 修改：`server/src/main/java/com/matrixcode/persistence/application/JdbcLocalExecutionStateStore.java`
- 修改：`server/src/test/java/com/matrixcode/persistence/application/RealRuntimeIntegrationTest.java`

### 任务 1：JDBC 身份仓储红灯

- [x] **步骤 1：编写失败测试**

创建 `server/src/test/java/com/matrixcode/persistence/JdbcProjectIdentityRepositoryTest.java`，覆盖：

```java
@Test
void 保存用户成员并按项目和用户查询() throws Exception {
    var repository = new JdbcProjectIdentityRepository(properties(jdbcUrl));
    repository.ensureUser(new MatrixUser("user-dev", "dev", "开发同学", "dev@example.com", "ACTIVE", fixed, fixed));
    repository.ensureProject("demo", "MatrixCode Demo", "owner-1", "身份成员");
    repository.ensureMember(new ProjectMember("member-1", "demo", "user-dev", "DEVELOPER", "ACTIVE", fixed, fixed, fixed));

    assertThat(repository.members("demo")).extracting(ProjectMember::userId).containsExactly("user-dev");
    assertThat(repository.projectsForUser("user-dev")).containsExactly("demo");
}
```

- [x] **步骤 2：运行测试验证失败**

运行：

```bash
/Users/Masons/Ai/Maven/bin/mvn -Dmaven.repo.local=/Users/Masons/Ai/Maven_Ai_Store -q -pl server -Dtest=JdbcProjectIdentityRepositoryTest test
```

预期：编译失败，缺少 identity 类型和 `JdbcProjectIdentityRepository`。

### 任务 2：最小身份仓储实现

- [x] **步骤 3：新增领域模型和端口**

实现不可变 record：

- `MatrixUser`
- `ProjectMember`
- `UserAuditRecord`
- `ProjectIdentityRepository`

校验由服务层做，record 保持数据承载。

- [x] **步骤 4：实现 JDBC 仓储**

`JdbcProjectIdentityRepository` 提供：

- `ensureUser(MatrixUser user)`
- `ensureProject(String projectId, String name, String ownerUserId, String currentStage)`
- `ensureMember(ProjectMember member)`
- `members(String projectId)`
- `projectsForUser(String userId)`
- `auditRecords(String projectId, String userId)`

- [x] **步骤 5：运行仓储测试验证通过**

运行同任务 1 命令，预期 PASS。

### 任务 3：本地执行审计写入用户归属

- [x] **步骤 6：编写失败测试**

创建 `server/src/test/java/com/matrixcode/localexecution/LocalExecutionIdentityAuditTest.java`：

```java
@Test
void jdbc审计写入时填充用户字段并确保用户存在() throws Exception {
    var store = new JdbcLocalExecutionStateStore(new JdbcSnapshotRepository(properties(jdbcUrl)), new ObjectMapper());
    var task = new ExecutionTask("task-1", "demo", "workspace-1", "user-dev", "SHELL", "git status",
            ApprovalDecision.ALLOW, ExecutionTaskStatus.SUCCEEDED, 0, "ok", "", 5,
            fixed, "user-reviewer", "允许", fixed, "", "", "", null);
    store.saveTasks(Map.of("demo", List.of(task)), Map.of());
    store.saveAuditRecords(List.of(new AuditRecord("audit-1", "task-1", "user-reviewer", "SHELL", "/tmp/matrixcode", "git status", ApprovalDecision.ALLOW, fixed)));

    assertThat(actorUserId("audit-1")).isEqualTo("user-reviewer");
    assertThat(userCount("user-reviewer")).isEqualTo(1);
}
```

- [x] **步骤 7：运行测试验证失败**

运行：

```bash
/Users/Masons/Ai/Maven/bin/mvn -Dmaven.repo.local=/Users/Masons/Ai/Maven_Ai_Store -q -pl server -Dtest=LocalExecutionIdentityAuditTest test
```

预期：断言失败或外键相关失败，`actor_user_id` 未填充。

- [x] **步骤 8：修改 JDBC 本地执行存储**

在 `replaceAuditRecords` 写入前调用 `ensureUser(connection, record.actorId())`，并把 insert 第 3 个参数从 `null` 改为 `record.actorId()`。

- [x] **步骤 9：运行测试验证通过**

运行同步骤 7 命令，预期 PASS。

### 任务 4：服务层入口与真实集成验证

- [x] **步骤 10：新增用户级审计查询断言**

补充 `JdbcProjectIdentityRepositoryTest` 的用户级审计查询断言：

```java
assertThat(repository.auditRecords("demo", "user-dev"))
    .extracting(UserAuditRecord::actorUserId)
    .containsExactly("user-dev");
```

- [x] **步骤 11：实现 `ProjectIdentityService`**

提供：

- `ensureProjectOwner(projectId, ownerUserId, displayName)`
- `ensureMember(projectId, userId, roleKey, displayName)`
- `members(projectId)`
- `projectsForUser(userId)`
- `auditRecords(projectId, userId)`

- [x] **步骤 12：增强真实运行集成**

在 `RealRuntimeIntegrationTest` 中验证真实 MySQL `matrix_code` 上可写读用户、成员、审计。

### 任务 5：回归、文档与图谱

- [x] **步骤 13：局部回归**

运行：

```bash
/Users/Masons/Ai/Maven/bin/mvn -Dmaven.repo.local=/Users/Masons/Ai/Maven_Ai_Store -q -pl server -Dtest=JdbcProjectIdentityRepositoryTest,LocalExecutionIdentityAuditTest,JdbcLocalExecutionStateStoreTest test
```

- [x] **步骤 14：服务端全量测试**

运行：

```bash
/Users/Masons/Ai/Maven/bin/mvn -Dmaven.repo.local=/Users/Masons/Ai/Maven_Ai_Store -q -pl server test
```

- [x] **步骤 15：真实运行验证**

运行：

```bash
set -a; source .env.local; set +a; ./scripts/check-real-runtime.sh
set -a; source .env.local; set +a; /Users/Masons/Ai/Maven/bin/mvn -Dmaven.repo.local=/Users/Masons/Ai/Maven_Ai_Store -q -pl server -Dmatrixcode.real-runtime-test=true -Dtest=RealRuntimeIntegrationTest test
```

- [x] **步骤 16：更新 Obsidian 图谱**

新增 `MatrixCode/阶段成果/38 身份成员与用户级审计正式仓储.md`，并更新项目首页、项目总览、阶段索引、模块地图、验证与风险、状态持久化与数据库迁移。
