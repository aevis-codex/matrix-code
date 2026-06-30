# Bug 正式 MySQL 仓储设计

## 背景

文档中心已在第 28 阶段切到正式 `matrixcode_documents` 表优先。测试角色的 Bug 创建与流转仍由 `BugService` 写入 `WorkbenchStateStore` 快照。第 18 阶段已经创建 `matrixcode_bugs` 表，但表结构还不能完整表达 `ProjectBug` 的复现步骤、预期结果、实际结果和最后流转备注。

## 目标

- 新增 Bug 正式仓储接口，让 `BugService` 在 JDBC 模式下优先读写 `matrixcode_bugs`。
- 保留文件/内存快照模式。
- JDBC 模式首次启动时，如果正式 Bug 表为空且快照中已有 Bug，自动回填正式表。
- 补齐 `matrixcode_bugs` 对 `ProjectBug` 字段的表达。
- 不在本阶段引入 Redis/RocketMQ，也不扩展 Bug 评论、附件或多用户分派。

## 方案

- 新增 `BugRepository`，定义 `load()` 和 `save(List<ProjectBug>)`。
- 新增 `JdbcBugRepository`，在 `matrixcode.persistence.mode=jdbc` 下启用。
- `BugService` 使用可选正式仓储：
  - 正式仓储有数据时优先恢复正式表。
  - 正式仓储为空时从快照恢复并回填正式表。
  - 正式仓储存在时写正式表，否则写快照。
- 新增 Flyway 迁移补齐字段：
  - `reproduction_steps`
  - `expected_result`
  - `actual_result`
  - `last_note`

## 验收标准

- `JdbcBugRepositoryTest` 能保存并恢复 Bug 的标题、严重级别、状态、复现步骤、预期、实际、创建角色、当前负责人、最后备注和更新时间。
- `BugServiceTest` 能证明正式仓储优先和快照回填。
- `JdbcPersistenceSpringTest` 能证明 Spring JDBC 模式启用 `JdbcBugRepository`，重启后从正式表恢复 Bug。
- 服务端全量测试通过。
- `git diff --check` 通过。
- 精确敏感信息扫描无命中。

## 回溯对齐

- 对齐测试角色闭环：Bug 创建、确认、修复、回归和关闭状态具备上线级持久化基础。
- 对齐多人协作目标：缺陷证据可被开发、测试、产品和智能体跨角色引用。
- 对齐基础设施决策：本阶段继续只使用 MySQL/Flyway，不引入 Redis/RocketMQ。
