# MatrixCode 第十七阶段 JDBC 快照持久化设计规格

第十四阶段到第十六阶段已经把运行态提醒、本地执行状态和项目工作台状态分别写入本地 JSON 快照。当前项目已经能跨服务端重启恢复 MVP 主线，但这些快照仍分散在本地文件中，离生产级 PostgreSQL 持久化还有一个关键边界：应用需要能把同一组快照切换到数据库保存，并且保留现有服务接口和本地验证方式。

第十七阶段目标是引入可选的 JDBC 快照持久化模式。默认仍使用文件快照；当配置 `matrixcode.persistence.mode=jdbc` 时，运行态提醒、本地执行状态和工作台状态都写入同一张数据库快照表。该阶段不拆领域表、不引入登录体系，也不改变现有 API 返回结构。

## 目标

- 增加生产级迁移边界：三份快照可以通过 JDBC 写入 PostgreSQL 兼容表。
- 保持默认本地体验：未配置 JDBC 时继续使用 `.matrixcode/*.json` 文件。
- 让存储切换可测试：服务重建后能从 JDBC 恢复提醒、工作区、任务、审计、文档、Bug、部署、Compose、模型请求和事件。
- 明确后续数据库拆表路径：先保存稳定快照，再逐步把高价值领域迁移为正式关系表。

## 非目标

- 不把所有领域对象一次性拆成关系型表。
- 不引入用户登录、租户、权限和成员身份。
- 不实现数据库迁移工具链或 Flyway 版本管理。
- 不保存本地命令输出之外的敏感凭证，不读取 SSH 或模型供应商密钥。

## 方案选择

方案一是直接把每个领域对象拆成数据库表。优点是接近最终生产模型；缺点是本阶段会同时牵动文档、Bug、部署、模型、事件、本地执行和提醒，范围过大，且容易在还没有身份系统时提前固化错误关系。

方案二是只写迁移文档，不改代码。优点是风险低；缺点是没有可运行证明，无法验证当前 JSON 快照能否安全进入数据库。

推荐方案是新增 JDBC 快照模式。它用一张表按 `slice_key` 保存三类快照 JSON：`runtime-notifications`、`local-execution`、`workbench-state`。这样可以真实走数据库读写和重启恢复，又不会提前拆分尚在演进的领域模型。

## 存储模型

新增表建议名为 `matrixcode_state_snapshots`：

```sql
create table if not exists matrixcode_state_snapshots (
  slice_key varchar(80) primary key,
  version integer not null,
  payload text not null,
  updated_at timestamp not null
);
```

`slice_key` 固定为三类：

- `runtime-notifications`：保存 `RuntimeNotificationSnapshot`。
- `local-execution`：保存 `LocalExecutionSnapshot`。
- `workbench-state`：保存 `WorkbenchStateSnapshot`。

`version` 保存快照版本号。当前三类快照都使用版本 1；读取时如果版本不兼容或 JSON 损坏，该分区返回空快照，应用继续启动。

## 配置

新增统一配置：

```yaml
matrixcode:
  persistence:
    mode: file
    jdbc:
      url: jdbc:postgresql://localhost:5432/matrixcode
      username: matrixcode
      password: matrixcode
      table-name: matrixcode_state_snapshots
```

环境变量形式：

```bash
MATRIXCODE_PERSISTENCE_MODE=jdbc
MATRIXCODE_PERSISTENCE_JDBC_URL=jdbc:postgresql://localhost:5432/matrixcode
MATRIXCODE_PERSISTENCE_JDBC_USERNAME=matrixcode
MATRIXCODE_PERSISTENCE_JDBC_PASSWORD=matrixcode
```

默认 `mode=file`，继续使用第十四到十六阶段的三份文件路径配置。只有 `mode=jdbc` 时才启用 JDBC 存储 Bean。

## 架构

新增 `com.matrixcode.persistence.application` 包：

- `PersistenceModeProperties`：读取 `matrixcode.persistence.mode` 和 JDBC 连接配置。
- `JdbcSnapshotRepository`：封装 JDBC 连接、建表、读取和 upsert。只处理字符串 payload，不理解领域对象。
- `JdbcRuntimeNotificationStore`：实现 `RuntimeNotificationStore`，序列化和反序列化 `RuntimeNotificationSnapshot`。
- `JdbcLocalExecutionStateStore`：实现 `LocalExecutionStateStore`，沿用当前分区保存方法，更新 `local-execution` 切片。
- `JdbcWorkbenchStateStore`：实现 `WorkbenchStateStore`，沿用当前分区保存方法，更新 `workbench-state` 切片。

现有三个文件存储 Bean 增加条件：

- `matrixcode.persistence.mode` 缺失或为 `file` 时启用。
- `matrixcode.persistence.mode=jdbc` 时禁用，由 JDBC 存储 Bean 替代。

JDBC 实现使用 JDK `DriverManager` 和 PostgreSQL 驱动，不引入 Spring Data 或 ORM。这样不会在文件模式下触发 Spring Boot 数据源自动配置，也不会要求本地开发必须启动数据库。

## 错误处理

- JDBC URL 为空且启用了 `jdbc` 模式时，应用启动失败并给出清晰异常。
- 建表和写入失败时抛出 `IllegalStateException`，让用户知道持久化未成功。
- 单个切片读取失败、版本不兼容或 JSON 损坏时，返回该切片空快照，不影响其他切片恢复。
- 表名只允许字母、数字和下划线，避免配置值进入 DDL 时产生注入风险。

## 测试策略

- `JdbcSnapshotRepositoryTest` 使用 H2 内存数据库验证建表、写入、覆盖更新和表名校验。
- `JdbcRuntimeNotificationStoreTest` 验证提醒快照保存后重建存储可恢复，损坏 payload 返回空快照。
- `JdbcLocalExecutionStateStoreTest` 验证工作区、任务、日志和审计分区保存后可恢复，分区更新不会丢失其他分区。
- `JdbcWorkbenchStateStoreTest` 验证文档、Bug、部署、Compose、模型请求、事件、文件操作、Git diff、工作流和验收分区保存后可恢复，分区更新不会丢失其他分区。
- Spring 集成测试在 H2 JDBC 模式下启动两次上下文，通过现有 API 写入完整状态并验证重启恢复。
- 文件模式现有全量测试继续通过，证明默认体验未被 JDBC 依赖破坏。

## 本地验证

PostgreSQL 验证使用现有 `docker-compose.yml`：

```bash
docker compose up -d postgres
MATRIXCODE_PERSISTENCE_MODE=jdbc \
MATRIXCODE_PERSISTENCE_JDBC_URL=jdbc:postgresql://localhost:5432/matrixcode \
MATRIXCODE_PERSISTENCE_JDBC_USERNAME=matrixcode \
MATRIXCODE_PERSISTENCE_JDBC_PASSWORD=matrixcode \
/Users/Masons/Ai/Maven/bin/mvn -Dmaven.repo.local=/Users/Masons/Ai/Maven_Ai_Store -pl server spring-boot:run
```

准备工作台状态后重启服务端，桌面端和 `/api/projects/demo/workbench` 应恢复同一组状态。数据库中应存在三行快照记录。

## 验收标准

- 默认文件模式下服务端全量测试通过，不要求本地启动 PostgreSQL。
- JDBC 模式下三类存储的单元测试和 Spring 重启集成测试通过。
- 启用 JDBC 后不再写入默认 `.matrixcode/runtime-notifications.json`、`.matrixcode/local-execution.json`、`.matrixcode/workbench-state.json`。
- 文档说明文件模式和 JDBC 模式的切换方式、验证命令和当前阶段限制。

## 后续阶段入口

第十八阶段可以在 JDBC 快照模式上继续做两类工作：一是为用户、项目、文档、Bug、部署和提醒拆正式关系表；二是引入登录和团队成员身份，让提醒已读、审批责任和事件归属具备用户维度。
