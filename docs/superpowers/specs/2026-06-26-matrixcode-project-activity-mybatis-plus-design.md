# MatrixCode 第 49 阶段设计：项目活动 MyBatis-Plus 仓储迁移

## 背景

第 36 阶段已经把模型请求和项目事件迁入正式 MySQL 表：`matrixcode_model_requests` 和 `matrixcode_project_events`。第 40 阶段又为模型请求补充 `actor_user_id`，用于用户级责任链。当前正式表读写主路径仍由 `JdbcProjectActivityRepository` 承担，与“正式 ORM 使用 MyBatis-Plus”的上线约束不一致。

## 目标

将 `ProjectActivityRepository` 的 Spring Bean 主路径迁移为 MyBatis-Plus 实现，保持模型网关、项目事件总线和旧快照回填语义不变。

## 范围

- 新增 `ModelRequestEntity`，映射 `matrixcode_model_requests`。
- 新增 `ProjectEventEntity`，映射 `matrixcode_project_events`。
- 新增 `ModelRequestMapper` 和 `ProjectEventMapper`。
- 新增 `MybatisPlusProjectActivityRepository`，实现 `ProjectActivityRepository`。
- `JdbcProjectActivityRepository` 退出 Spring Bean，仅保留直接构造单测。
- 更新 Spring 上下文测试，确认 JDBC 模式下主 Bean 使用 MyBatis-Plus。

## 非目标

- 不新增 Flyway DDL。
- 不改变模型请求或项目事件 API。
- 不引入 Redis/RocketMQ 作为运行依赖。
- 不改变旧快照回填策略。

## 数据语义

- `saveModelRequests()` 继续按项目执行全量替换：先删除该项目模型请求，再写入当前快照。
- `saveProjectEvents()` 继续按项目执行全量替换：先删除该项目事件，再写入当前快照。
- 写入模型请求前，自动补齐项目外键和非空 `actor_user_id` 对应用户外键。
- 读取排序保持旧 JDBC 行为：`project_id`、`created_at`、`id`。

## 验证策略

- 红测：JDBC 模式下 `ProjectActivityRepository` Bean 仍为旧 JDBC 实现，测试应失败。
- 局部测试：MyBatis-Plus 项目活动仓储、旧 JDBC 项目活动仓储和 Spring 持久化上下文测试通过。
- 关联回归：模型网关、项目事件总线、工作台控制器和项目活动仓储服务测试通过。
- 服务端全量测试通过。
- 真实验证：使用 `.env.local` 真实 MySQL、Milvus、Redis、RocketMQ 和真实模型配置，执行 `RealRuntimeIntegrationTest`；再通过真实 API 触发模型请求和项目事件写读，并查询正式表。

## 回溯对齐

- 对齐最初需求：项目活动属于多人协作控制台的运行过程证据，应进入真实 MySQL 正式表。
- 对齐 ORM 约束：正式业务数据主路径逐步迁移到 MyBatis-Plus。
- 对齐 Agent 控制台目标：模型请求与事件流是 Agent 运行审计、用户归属和后续 trace 视图的基础数据面。
