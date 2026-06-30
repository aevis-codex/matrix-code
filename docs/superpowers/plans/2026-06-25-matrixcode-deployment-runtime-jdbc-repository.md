# 部署运行态正式 MySQL 仓储实现计划

> **面向 AI 代理的工作者：** 必需子技能：使用 superpowers:subagent-driven-development（推荐）或 superpowers:executing-plans 逐任务实现此计划。步骤使用复选框（`- [ ]`）语法来跟踪进度。

**目标：** 将部署操作、部署健康检查、Compose 环境和 Compose 操作迁移到正式 MySQL 仓储。

**架构：** 新增 `DeploymentRuntimeRepository` 聚合接口和 JDBC 实现。三个部署服务在 JDBC 模式下优先读写正式表，旧 `workbench-state` 仅在正式表为空时回填。

**技术栈：** Java 21、Spring Boot、Flyway、JDBC、H2 MySQL mode、AssertJ。

---

### 任务 1：红灯测试

**文件：**
- 创建：`server/src/test/java/com/matrixcode/persistence/JdbcDeploymentRuntimeRepositoryTest.java`
- 创建：`server/src/test/java/com/matrixcode/deployment/DeploymentRuntimeRepositoryServiceTest.java`

- [x] 编写 JDBC 仓储测试，引用尚未存在的 `JdbcDeploymentRuntimeRepository` 和 `DeploymentRuntimeRepository`。
- [x] 编写服务接入测试，验证正式仓储优先和旧快照回填。
- [x] 运行：

```bash
/Users/Masons/Ai/Maven/bin/mvn -Dmaven.repo.local=/Users/Masons/Ai/Maven_Ai_Store -q -pl server -Dtest=JdbcDeploymentRuntimeRepositoryTest,DeploymentRuntimeRepositoryServiceTest test
```

预期：编译失败，缺少仓储类型。

### 任务 2：迁移和仓储

**文件：**
- 创建：`server/src/main/java/com/matrixcode/deployment/application/DeploymentRuntimeRepository.java`
- 创建：`server/src/main/java/com/matrixcode/persistence/application/JdbcDeploymentRuntimeRepository.java`
- 创建：`server/src/main/resources/db/migration/V34_1__create_deployment_runtime_tables.sql`

- [x] 新增聚合接口，方法与 `WorkbenchStateStore` 的部署运行态切片保持一致。
- [x] 新增 Flyway 迁移，创建四张正式表和项目/目标/时间索引。
- [x] 新增 JDBC 实现，支持 load、replace/upsert、最小项目 upsert 和迁移触发。
- [x] 运行任务 1 测试，预期仓储测试通过，服务接入测试仍可能失败。

### 任务 3：服务接入

**文件：**
- 修改：`server/src/main/java/com/matrixcode/deployment/application/DeploymentOperationService.java`
- 修改：`server/src/main/java/com/matrixcode/deployment/application/DeploymentHealthService.java`
- 修改：`server/src/main/java/com/matrixcode/deployment/application/ComposeEnvironmentService.java`

- [x] 构造器增加可选 `DeploymentRuntimeRepository`。
- [x] 加载顺序改为正式表优先、旧快照空表回填。
- [x] 保存路径改为 JDBC 模式写正式表，非 JDBC 模式写旧 `WorkbenchStateStore`。
- [x] 保持历史上限 20 条、排序和校验行为不变。
- [x] 运行任务 1 测试，预期全部通过。

### 任务 4：回归验证和图谱

**文件：**
- 修改：`MatrixCode/1 项目首页.md`
- 修改：`MatrixCode/3 阶段索引.md`
- 修改：`MatrixCode/5 技术栈与运行约定.md`
- 修改：`MatrixCode/11 部署健康检查与运维记录.md`
- 新增：`MatrixCode/阶段成果/34 部署运行态正式 MySQL 仓储.md`

- [x] 运行关联测试、服务端全量测试、`git diff --check`、敏感信息扫描。
- [x] 回溯第 11、13、14、15、32、33 阶段，确认没有偏离真实可上线目标。
- [x] 更新 Obsidian 图谱，记录阶段成果、验证证据和剩余风险。
