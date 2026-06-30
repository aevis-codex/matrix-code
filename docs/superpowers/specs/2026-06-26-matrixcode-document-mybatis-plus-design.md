# MatrixCode 第 50 阶段设计：文档中心 MyBatis-Plus 仓储迁移

## 背景

文档中心已经在第 28 阶段具备正式 MySQL 表 `matrixcode_documents`，但 Spring Bean 主路径仍由 `JdbcDocumentRepository` 直接拼写 SQL 完成读写。该路径和“正式 ORM 使用 MyBatis-Plus”的上线约束不一致，也会影响冻结需求、编码智能体交接文档、测试报告和验收记录的后续 trace 统一治理。

## 目标

将 `DocumentRepository` 在 `matrixcode.persistence.mode=jdbc` 下的正式 Bean 切换为 `MybatisPlusDocumentRepository`。旧 `JdbcDocumentRepository` 保留直接构造测试，作为迁移期间的兼容参考，不再参与 Spring 正式上下文。

## 范围

- 新增 `DocumentEntity` 映射 `matrixcode_documents`。
- 新增 `DocumentMapper` 继承 MyBatis-Plus `BaseMapper`。
- 新增 `MybatisPlusDocumentRepository` 实现 `DocumentRepository`。
- 更新 `JdbcDocumentRepository`，移除 Spring Bean 注解和自动注入构造器。
- 更新 Spring 持久化测试，断言正式 Bean 使用 MyBatis-Plus。
- 增加冻结文档和交接文档的读写验证，覆盖“冻结后文档必须能从工作台读回”的核心行为。

## 非目标

- 不修改 `DocumentService` 的业务语义。
- 不新增 DDL；本阶段复用已带表注释和字段注释的 `matrixcode_documents`。
- 不改变 REST API 或桌面端展示契约。
- 不把 Redis、RocketMQ 接入文档读写路径。

## 数据与行为

`DocumentEntity` 负责领域对象和正式表字段互转：

- `document_type` 对应 `DocumentType`。
- `status` 对应 `DocumentState`。
- `frozen` 由 `state == FROZEN` 推导。
- `created_at` 对应领域 `createdAt`。
- `updated_at` 使用 `frozenAt` 优先，否则使用 `createdAt`，保持旧 JDBC 行为。
- `frozen_by`、`frozen_at`、`parent_version_id` 使用 MyBatis-Plus 的 `FieldStrategy.ALWAYS`，确保从冻结态回退测试或草稿更新时可以写入空值。

`MybatisPlusDocumentRepository.save()` 延续旧语义：按文档 ID 逐条 upsert，不清空其他项目或其他文档。写入前使用 `MatrixProjectMapper` 补齐项目外键。

## 错误处理

仓储不吞异常。MyBatis-Plus 或事务异常向上抛出，由 Spring 测试和真实集成暴露。项目外键补齐失败时，事务整体回滚。

## 验证策略

1. TDD 红灯：新增 `MybatisPlusDocumentRepositoryTest`，先断言 JDBC 模式下 `DocumentRepository` Bean 名称包含 `MybatisPlusDocumentRepository`，当前应失败。
2. 绿灯：新增 Entity/Mapper/Repository，旧 JDBC 退出 Bean。
3. 局部验证：文档仓储测试、旧 JDBC 直接测试、Spring 持久化测试。
4. 关联回归：`DocumentServiceTest`、`WorkbenchControllerTest`、`JdbcPersistenceSpringTest`。
5. 全量验证：服务端全量测试。
6. 真实验证：真实 MySQL/模型/工作台链路中生成并冻结文档，确认工作台读回冻结文档和编码智能体交接文档。
7. 静态验证：`git diff --check`、敏感信息扫描、正式资源 H2 口径扫描。

## 回溯对齐

- 对齐“正式业务数据使用 MySQL，正式 ORM 使用 MyBatis-Plus”的约束。
- 对齐用户对冻结后交接文档可见性的关注，验证必须覆盖工作台读回。
- 延续第 46、47、48、49 阶段的低风险仓储迁移模板。
