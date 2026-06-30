# MatrixCode 第 45 阶段设计：历史表字段注释补齐

## 背景

第 42 阶段开始新增表已要求表注释和字段注释，但第 18 到第 40 阶段的历史正式表存在注释缺口。真实库已经执行过这些迁移，不能直接修改历史 SQL，否则会造成 Flyway checksum 风险。

## 目标

- 通过新的 Flyway 迁移补齐历史正式表注释和字段注释。
- 保持真实 MySQL `matrix_code` 可迁移。
- 保持 H2 仅测试场景可迁移。
- 增加测试约束：所有 `matrixcode_%` 正式表和字段都必须有注释。

## 技术方案

- 新增 Java Flyway 迁移 `V45_1__backfill_historical_schema_comments`。
- H2 测试库使用 `COMMENT ON TABLE` 和 `COMMENT ON COLUMN`。
- MySQL 真实库使用 `ALTER TABLE ... COMMENT = ...` 和动态 `ALTER TABLE ... MODIFY COLUMN ... COMMENT ...`。
- 注释内容维护在常量映射里，避免修改历史 SQL。

## 非目标

- 不改变表结构、索引、外键或业务数据。
- 不迁移手写 JDBC 仓储到 MyBatis-Plus。
- 不新增 Redis/RocketMQ 业务依赖。

## 验收标准

- `DatabaseMigrationServiceTest` 能验证所有 `matrixcode_%` 表和字段注释非空。
- 真实 MySQL Flyway 迁移到 v45.1。
- 服务端相关测试通过。
- 敏感信息扫描无命中。
