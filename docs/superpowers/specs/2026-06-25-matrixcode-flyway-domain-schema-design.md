# MatrixCode 第十八阶段 Flyway 领域表迁移基础设计规格

第十七阶段已经把三类核心状态切换到可选 JDBC 快照表，但 `matrixcode_state_snapshots` 仍是 JSON 过渡层。要走向真实可上线运行，下一步需要建立正式数据库迁移机制和领域表骨架。第十八阶段只做“迁移基础 + 表结构边界”，不把现有服务一次性切到关系表读写。

## 目标

- 引入 Flyway 迁移执行能力，并通过配置控制是否启动时自动迁移。
- 在 `application.yml` 预留数据库模式、JDBC 地址、账号、密码、快照表名和迁移开关，不影响默认文件模式。
- 新增第一批生产领域表：用户、项目、项目成员、角色智能体配置、协作会话、文档、Bug、部署目标、运行态提醒、本地任务、审批审计、项目事件。
- 使用 H2 MySQL 兼容模式验证迁移脚本可执行，并保持 MySQL 兼容写法。
- 保持当前运行路径稳定：默认 `matrixcode.persistence.mode=file`，不需要数据库即可启动和测试。
- 明确后续生产基础设施基线：业务数据使用 MySQL，向量检索使用 Milvus，缓存使用 Redis，消息使用 RocketMQ；本阶段只引入业务库迁移。

## 非目标

- 不在本阶段把 `DocumentService`、`BugService`、`WorkbenchService` 等服务改为关系表读写。
- 不实现登录、注册、会话、Token 或密码加密。
- 不实现完整用户权限判定。
- 不引入 Milvus、Redis、RocketMQ 读写逻辑；这些基础设施在实际业务阶段需要时再接入。
- 不迁移已有快照 payload 到领域表。
- 不实现完整编码智能体协议、沙箱、工具调用或长上下文压缩。

## 方案选择

方案一是直接把所有服务仓储替换为 JDBC 关系表实现。这个方案最终必须做，但会一次性影响文档、Bug、工作台、模型、提醒、本地执行和部署多个模块，风险过大。

方案二是继续只维护 JDBC 快照表。它能恢复状态，但无法支撑生产级查询、权限、审计和数据演进。

推荐方案是先引入 Flyway 与领域表骨架。它可以提前验证真实数据库迁移链路，给后续逐模块切仓储提供稳定边界，同时不破坏当前已验证的 MVP 工作台。

## 配置

`application.yml` 增加默认配置：

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

默认不自动迁移，避免本地文件模式和没有数据库的开发环境被阻断。上线前或集成测试中显式设置 `MATRIXCODE_PERSISTENCE_MODE=jdbc` 与 `MATRIXCODE_PERSISTENCE_JDBC_MIGRATE_ON_STARTUP=true`。

## 迁移组件

新增 `DatabaseMigrationService`：

- 依赖 `PersistenceModeProperties`。
- 当 `mode=jdbc` 且 `jdbc.migrateOnStartup=true` 时执行 Flyway。
- JDBC URL 为空时抛出明确错误。
- 迁移位置固定为 `classpath:db/migration`。
- 迁移失败时抛出 `IllegalStateException`，让服务启动失败，避免使用半迁移数据库。

新增 `DatabaseMigrationRunner`：

- Spring `ApplicationRunner`。
- 默认文件模式下不执行任何动作。
- JDBC 自动迁移开关打开时调用 `DatabaseMigrationService.migrate()`.

## 第一批领域表

迁移脚本 `V18_1__create_core_domain_tables.sql` 创建以下表：

- `matrixcode_users`：用户身份骨架。
- `matrixcode_projects`：项目主表。
- `matrixcode_project_members`：项目成员与角色。
- `matrixcode_role_agent_configs`：每个项目、每个角色智能体的独立配置，包括系统提示词、用户提示词模板、模型、主题色和字体。
- `matrixcode_collaboration_sessions`：多人实时协作会话骨架。
- `matrixcode_collaboration_participants`：协作会话参与者、角色和在线状态。
- `matrixcode_documents`：文档版本与冻结状态。
- `matrixcode_bugs`：Bug 状态流转主数据。
- `matrixcode_deployment_targets`：部署目标。
- `matrixcode_runtime_notifications`：运行态提醒与已读状态。
- `matrixcode_local_execution_tasks`：本地执行任务。
- `matrixcode_audit_records`：审批审计记录。
- `matrixcode_project_events`：项目事件流。

表结构优先选择 MySQL 和 H2 都能执行的类型：`varchar`、`text`、`int`、`bigint`、`boolean`、`decimal`、`timestamp`。JSON 快照拆表后的复杂 payload 后续再逐步细化。

## 角色智能体配置边界

MatrixCode 的产品形态是多人实时协作的智能体控制台。每个角色都有自己的智能体，尤其是开发编码智能体，需要向 Codex、Claude Code 这类成熟工具学习：任务规划、上下文选择、工具调用、代码修改、测试验证和回溯记录都应可配置、可审计、可替换。

第十八阶段先在 MySQL 领域表中落下配置边界：

- 角色维度：产品、设计、开发、测试、运维以及后续自定义角色都通过 `role_key` 承接。
- 提示词维度：保留 `system_prompt` 与 `user_prompt_template`，后续可从数据库加载角色提示词。
- 模型维度：保留模型提供商、模型名称、温度、工具契约版本，和现有模型网关配置形成迁移路径。
- UI 维度：保留主题色、字体族、字号和显示顺序，支持每个角色智能体在控制台拥有独立视觉标识。
- 协作维度：保留协作会话与参与者表，后续 WebSocket、Redis presence 和 RocketMQ 事件流可在此基础上扩展。

## 测试策略

- `DatabaseMigrationServiceTest` 使用 H2 MySQL 模式执行 Flyway，验证所有核心表和 Flyway 历史表存在。
- 测试重复执行 `migrate()`，验证 Flyway 幂等处理已应用迁移。
- `DatabaseMigrationRunnerTest` 验证默认文件模式不执行迁移，JDBC 模式且开关打开时会执行迁移。
- 服务端全量测试确认默认文件模式不需要数据库。
- 桌面端测试和构建确认前端无回归。

## 验收标准

- 默认文件模式下服务端全量测试通过，无需数据库。
- JDBC + H2 MySQL 测试模式下 Flyway 能创建所有第十八阶段核心表。
- `application.yml` 中存在真实部署所需的数据库配置项。
- 本地运行文档说明如何开启迁移，以及当前阶段只建表不切仓储。
- Obsidian `MatrixCode` 图谱更新第十八阶段成果、模块地图、项目首页和验证风险。

## 后续阶段入口

第十九阶段建议开始“角色智能体配置中心”最小纵切：从 MySQL 加载项目级角色智能体配置，支持系统提示词、用户提示词模板、模型绑定和角色视觉标识的读写。完成配置中心后，再推进用户身份、协作 presence、编码智能体工具协议、Milvus 上下文检索、Redis 缓存和 RocketMQ 事件流。
