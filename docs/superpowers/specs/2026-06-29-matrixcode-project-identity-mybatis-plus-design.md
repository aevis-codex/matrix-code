# MatrixCode 第 146 阶段设计：项目身份仓储 MyBatis-Plus 迁移

## 背景

项目身份仓储承载用户、项目、成员、用户级审计和项目邀请。当前 Spring JDBC 模式下主路径仍由 `JdbcProjectIdentityRepository` 手写 SQL 读写，虽然功能完整，但与“正式上线 ORM 使用 MyBatis-Plus”的约束不一致。

## 推荐方案

新增项目成员、用户审计、项目邀请实体和 Mapper，并以 `MybatisPlusProjectIdentityRepository` 作为 `ProjectIdentityRepository` 的 JDBC 模式主 Bean。既有 `MatrixUserEntity` 与 `MatrixProjectEntity` 继续复用；旧 `JdbcProjectIdentityRepository` 去掉 Spring Bean 注解，保留直接构造测试。

本阶段不新增 DDL，不修改表结构。保存和查询语义保持旧仓储行为：

- 用户和项目按 ID upsert。
- 成员按项目、用户、角色 upsert；`replaceMember(...)` 会保留目标角色并把同用户其他角色标记为 `REMOVED`。
- `projectsForUser(...)` 只返回 `ACTIVE` 成员所属项目。
- 用户级审计只写低敏摘要，按 `occurred_at/created_at, sort_order, id` 排序读取。
- 项目邀请只保存 token hash，不保存明文 token；重发时替换 token hash。

## 验收标准

- Spring JDBC 模式下 `ProjectIdentityRepository` Bean 类名包含 `MybatisPlusProjectIdentityRepository`。
- 成员新增、角色替换、禁用成员项目不可见、旧角色隐藏等行为保持不变。
- 用户级审计写入和读取保持不变。
- 项目邀请保存、按 ID 读取、按 token hash 读取、token hash 替换、过期待处理查询保持不变。
- 默认 file 模式不创建 DataSource 和项目身份仓储 Bean。

## 回溯

- 对齐用户要求：正式业务数据使用 MySQL，正式 ORM 使用 MyBatis-Plus，H2 只用于测试。
- 对齐第 38、39、140、141 阶段：身份成员、用户审计和邀请治理的 API 契约不变，只替换仓储实现。
- 对齐第 82 到 116 阶段：Sa-Token 登录、会话治理和项目权限守卫继续依赖同一身份仓储接口。
