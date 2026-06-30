# MatrixCode 第 156 阶段：项目事件与交付回溯用户归属

## 背景

模型请求已经保存 `actorUserId`，Agent Runtime 也有用户责任审计，但项目事件和编码智能体交付回溯仍有一段责任链不完整：工作台能看到“发生了交付回溯”，但不能稳定追溯该项目事件来自哪个角色和哪个用户。

## 目标

- `ProjectEvent` 支持来源角色和来源 ID。
- JDBC 与 MyBatis-Plus 项目活动仓储读写 `matrixcode_project_events.source_role/source_id`。
- 编码智能体交付回溯发布项目事件时写入当前角色和操作者。
- 编码智能体交付回溯的 Agent 运行事件 payload 写入 `actorUserId`。
- 桌面端 API 类型同步新增字段，兼容旧事件和 SSE 旧数据。

## 实现

- `ProjectEvent` 增加 `sourceRole` 和 `sourceId`，保留旧构造方法，历史调用默认空来源。
- `JdbcProjectActivityRepository` 查询、写入项目事件时读写 `source_role` 和 `source_id`。
- `ProjectEventEntity` 在领域对象和 MyBatis-Plus 实体之间映射来源字段。
- `CodingAgentHandoffService` 发布 `CODING_AGENT_HANDOFF_RECORDED` 时写入 `role.name()` 和 `actorId`。
- `HANDOFF_RECORDED` 与 `TOOL_TRACE` 运行事件 payload 增加 `actorUserId`。
- `desktop/src/api/client.ts` 的 `ProjectEvent` 类型新增可选 `sourceRole/sourceId`。

## 验证

- 红灯：`CodingAgentTaskServiceTest`、`JdbcProjectActivityRepositoryTest`、`MybatisPlusProjectActivityRepositoryTest` 先因 `ProjectEvent` 缺少 `sourceRole/sourceId` 和新构造方法失败。
- 绿灯：同一目标测试通过。
- 横向目标回归：`CodingAgentTaskServiceTest`、`JdbcProjectActivityRepositoryTest`、`MybatisPlusProjectActivityRepositoryTest`、`ProjectEventStreamTest`、`ProjectEventControllerPermissionTest`、`ProjectActivityRepositoryServiceTest`、`WorkbenchServiceTest` 通过。
- 服务端全量：103 个测试报告文件，619 条测试，0 failures，0 errors，0 skipped。
- 桌面端全量：3 个测试文件，139 条测试通过；桌面端构建通过。
- 生产就绪聚合门禁：19/19 通过。
- 真实协议级检查：MySQL、Milvus、Redis、RocketMQ 连通；真实集成 3 条通过。
- 静态检查：`git diff --check` 和真实凭据精确扫描通过。

## 回溯结论

本阶段对齐“多人实时协作的智能体控制台”目标，把协作事件从匿名消息推进为带来源角色和用户的事件。实现复用既有正式表字段，没有新增 DDL，也没有改变项目事件 SSE 权限、模型网关缓存策略、向量上下文或本地执行审批边界。
