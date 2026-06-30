# Agent 运行记录正式仓储设计

## 背景

MatrixCode 已经具备角色智能体配置、模型网关、编码智能体执行准备、受控 patch、本地审批和用户归属。后续要达到真实可上线，需要把每次 Agent 运行、步骤事件、工具调用和操作者归属沉淀到正式 MySQL 主路径，而不是只存在工作台投影或日志里。

用户新增上线要求：

- 新建表必须写表注释和字段注释。
- 新增核心方法必须写清楚职责、输入边界、副作用和失败语义。

## 目标

- 新增 Agent 运行记录正式表，表和字段全部带详细注释。
- 新增 JDBC 仓储，支持创建运行记录、追加事件、读取最近运行。
- 增加测试校验表和字段注释能通过 H2 MySQL 模式读取。
- 增加迁移注释门禁，要求后续新增建表脚本必须包含表注释和字段注释。
- 核心仓储方法写 JavaDoc，说明业务语义和副作用。
- 仓储实现使用 MyBatis-Plus；H2 仅作为测试兼容数据库，正式运行使用 MySQL。

## 非目标

- 不在本阶段实现完整 Agent 调度器。
- 不改变现有编码智能体执行准备、受控 patch 或本地审批流程。
- 不引入 Redis/RocketMQ 作为运行依赖。
- 不把核心编排绑定到 LangGraph 或单供应商 SDK。

## 方案

### 数据模型

新增表：

- `matrixcode_agent_runs`：一次角色智能体运行的主记录。
- `matrixcode_agent_run_events`：运行过程中的状态变化、步骤事件、工具调用摘要和错误摘要。

关键字段：

- `project_id`：项目。
- `role_key`：角色，使用 `PRODUCT`、`DEVELOPER`、`TESTER`、`OPERATIONS`。
- `agent_kind`：智能体类型，例如 `coding`、`product`、`tester`、`operations`。
- `actor_user_id`：触发本次运行的用户。
- `provider_id`、`model_name`：本次运行使用的模型供应商和模型。
- `status`：`QUEUED`、`RUNNING`、`SUCCEEDED`、`FAILED`、`CANCELED`。
- `goal`：本次运行目标。
- `summary`：运行摘要。

### 仓储边界

新增 `AgentRuntimeRepository` 应用接口和 `MybatisPlusAgentRuntimeRepository` 实现。

方法：

- `saveRun(AgentRunRecord run)`：创建或覆盖运行主记录，同时确保项目和用户存在。
- `appendEvent(AgentRunEventRecord event)`：追加运行事件，不删除历史事件。
- `recentRuns(String projectId, int limit)`：按创建时间倒序读取项目最近运行。
- `eventsForRun(String runId)`：按发生时间读取运行事件。

实现边界：

- `MatrixCodeDataSourceConfiguration` 只在 `matrixcode.persistence.mode=jdbc` 时创建 DataSource。
- `@SpringBootApplication` 排除默认 DataSource 自动配置，避免 file 模式在测试 classpath 存在 H2 时误创建嵌入库。
- 领域记录不直接加 ORM 注解；`persistence.mybatis.entity` 负责表映射和领域转换。
- Mapper 使用 MyBatis-Plus `BaseMapper`，后续正式仓储迁移以该模式为模板。

### 注释门禁

新增测试扫描 `V42_` 及之后的 Flyway SQL：

- 每个 `create table` 语句必须带表级 `comment='...'`。
- 每个普通字段行必须带 `comment '...'`。
- 约束、主键、索引不按字段处理。

历史迁移不修改，避免真实 MySQL 已执行迁移的 Flyway checksum 失效。后续如果需要给历史表补 MySQL 元数据注释，应通过新迁移或独立维护脚本处理。

## Agent 框架选择

推荐主链路：Spring Boot + Spring AI + 自研工作流状态机。

理由：

- 当前项目主服务是 Java/Spring Boot，业务事务、审计、审批和持久化都在同一进程内。
- Spring AI 与 Spring Boot 集成更自然，适合承载多供应商模型、工具调用、RAG、向量库和 Chat Memory。
- MCP 适合作为工具协议边界，而不是替代业务编排。
- LangGraph 适合复杂长流程、checkpoint 和 human-in-the-loop，可作为独立 worker 或设计参考。
- OpenAI Agents SDK 的 handoffs、guardrails、tracing 值得参考，但 MatrixCode 要支持千问、DeepSeek、Kimi、豆包，不应把核心运行时绑死在单供应商 SDK。

## 验证

- 迁移测试：H2 MySQL 模式执行全量 Flyway，确认新表存在且注释可从 `information_schema` 读取。
- 注释门禁测试：扫描 V42 之后迁移，确认新建表和字段均有注释。
- 仓储测试：Spring JDBC 模式启动 MyBatis-Plus 仓储，保存 run、追加 event、读取最近运行和事件。
- 服务端定向测试和服务端全量测试通过。
- 真实预检和真实集成按现有环境变量验证；真实 MySQL schema 使用 `matrix_code`。
