# MatrixCode 第 145 阶段设计：Bug 仓储 MyBatis-Plus 迁移

## 背景

Bug 正式表 `matrixcode_bugs` 已在第 29 阶段完成业务落表，但 Spring JDBC 模式下主路径仍由 `JdbcBugRepository` 手写 SQL 读写。该状态与“正式上线 ORM 使用 MyBatis-Plus”的约束不一致，也会让后续缺陷治理、阶段验收和问题追踪继续依赖两套仓储风格。

## 推荐方案

新增 `BugEntity`、`BugMapper` 和 `MybatisPlusBugRepository`。在 `matrixcode.persistence.mode=jdbc` 时，`BugRepository` 的 Spring 主 Bean 改为 MyBatis-Plus 实现。旧 `JdbcBugRepository` 去掉 Spring Bean 注解，保留直接构造测试，作为迁移兼容参照。

本阶段不新增 DDL，不修改表结构，继续复用 `matrixcode_bugs` 与既有表字段注释。保存语义保持旧仓储行为：

- 按 Bug ID 增量 upsert。
- 写入前补齐 `matrixcode_projects` 兜底项目记录。
- 读取排序保持 `project_id, title, id`。
- `description` 继续与领域 `steps` 同步，兼容第 18 阶段基础字段。

## 验收标准

- Spring JDBC 模式下 `BugRepository` Bean 类名包含 `MybatisPlusBugRepository`。
- Bug 标题、严重级别、状态、复现步骤、期望结果、实际结果、创建角色、当前负责人、最近备注和更新时间可完整保存并恢复。
- 同 ID 二次保存执行 upsert，不重复插入。
- 默认 file 模式不创建 DataSource 和 Bug 仓储 Bean。
- `JdbcPersistenceSpringTest` 保持重启恢复行为通过。

## 回溯

- 对齐用户要求：正式业务数据使用 MySQL，正式 ORM 使用 MyBatis-Plus，H2 只用于测试。
- 对齐第 29 阶段：继续复用 Bug 正式表，不改变 Bug API 和桌面端契约。
- 对齐第 144 阶段：延续“新 MyBatis-Plus 主 Bean + 旧 JDBC 兼容测试”的低风险迁移方式。
