# MatrixCode 第 147 阶段设计：工作流进度 MyBatis-Plus 仓储迁移

## 背景

工作流进度仓储承载工作流项、工作流事件和验收投影。当前 JDBC 模式 Spring 主路径仍由 `JdbcWorkbenchProgressRepository` 手写 SQL 读写，和“正式上线 ORM 使用 MyBatis-Plus”的约束不一致。

## 方案

新增工作流项、工作流事件和验收投影实体与 Mapper，并以 `MybatisPlusWorkbenchProgressRepository` 作为 `WorkbenchProgressRepository` 的 JDBC 模式主 Bean。旧 `JdbcWorkbenchProgressRepository` 去掉 Spring Bean 注解，保留直接构造测试，确保迁移后仍可对照旧行为。

## 行为保持

- 工作流项按 `project_id, updated_at, id` 读取。
- 保存工作流项时先补齐项目外键，再按 ID upsert。
- 保存工作流事件时只处理已存在工作流项，缺失工作流项的事件继续跳过。
- 同一工作流项保存事件时先删除旧事件，再写入新事件列表。
- 验收投影以 `project_id` 为主键 upsert。
- 不新增 DDL，继续复用第 37 和第 45 阶段已有表结构与注释。

## 验收

- Spring JDBC 模式下 `WorkbenchProgressRepository` Bean 类名包含 `MybatisPlusWorkbenchProgressRepository`。
- `JdbcWorkbenchProgressRepositoryTest` 保持通过，证明旧实现兼容路径仍可用。
- `JdbcPersistenceSpringTest` 确认完整 Spring 上下文使用 MyBatis-Plus 工作流进度仓储。
- 服务端全量、桌面端测试、桌面端构建、生产就绪聚合门禁和敏感扫描通过。

## 回溯

- 对齐第 37 阶段：不改变工作流和验收投影表结构，只替换主仓储实现。
- 对齐第 144 到 146 阶段：沿用“新 MyBatis-Plus 主 Bean + 旧 JDBC 兼容测试”的低风险迁移模式。
- 对齐最初上线要求：正式业务数据继续使用 MySQL，正式 ORM 主路径继续收敛到 MyBatis-Plus。
