# 文档中心正式 MySQL 仓储设计

## 背景

MatrixCode 已在第 18 阶段创建 `matrixcode_documents` 表，但 `DocumentService` 仍主要通过 `WorkbenchStateStore.saveDocuments(...)` 写入工作台快照。第 27 阶段新增的编码智能体交付回溯也复用 `DocumentService.createDraft(...)`，因此交付证据仍会进入快照层。

真实上线运行需要把产品冻结文档、交接文档、接口文档、数据库脚本、部署文档、测试报告、验收记录和编码智能体交付回溯写入正式领域表。

## 目标

- 新增文档正式仓储接口，让 `DocumentService` 可在 JDBC 模式下优先读写 `matrixcode_documents`。
- 保留文件/内存快照模式，避免影响当前本地开发和现有测试。
- JDBC 模式首次启动时，如果正式文档表为空且快照中已有文档，自动回填正式表。
- 保留文档版本字段：`parentVersionId`、`frozenBy`、`frozenAt`。
- 不在本阶段迁移 Bug、部署、提醒、本地执行或审批审计仓储。

## 方案

采用与角色智能体配置一致的模式：

- `DocumentRepository` 定义 `load()` 和 `save(List<DocumentVersion>)`。
- `JdbcDocumentRepository` 在 `matrixcode.persistence.mode=jdbc` 时启用。
- `DocumentService` 构造时接收可选 `DocumentRepository`，延迟加载：
  - 若正式仓储存在且有数据，使用正式仓储。
  - 若正式仓储为空，读取 `WorkbenchStateStore` 快照文档并回填正式仓储。
  - 后续写入在正式仓储存在时只写正式表，否则写快照。
- 新增 Flyway 迁移补齐文档版本字段。

## 表结构补齐

现有 `matrixcode_documents` 表已有核心字段：`id`、`project_id`、`document_type`、`title`、`status`、`version`、`frozen`、`content`、`created_at`、`updated_at`。

本阶段新增：

- `parent_version_id varchar(64)`：冻结版本变更草稿的父版本。
- `frozen_by varchar(120)`：冻结操作者。
- `frozen_at timestamp`：冻结时间。

`status` 继续映射 `DocumentState`；`frozen` 作为冗余布尔字段，便于后续查询。

## 风险控制

- 不修改已有文档领域模型，降低影响面。
- JDBC 仓储只做 upsert，不做删除，因为当前业务没有删除文档能力。
- 写入前自动确保项目行存在，沿用角色智能体仓储的兼容策略。
- 真实凭据不写入仓库；测试继续使用 H2 MySQL 模式。

## 验收标准

- `JdbcDocumentRepositoryTest` 能保存并恢复草稿、冻结文档和变更草稿的完整字段。
- `DocumentServiceTest` 能证明正式仓储优先，并能从快照回填正式仓储。
- JDBC Spring 重启测试能从正式 `matrixcode_documents` 表恢复文档。
- 服务端全量测试通过。
- `git diff --check` 通过。
- 精确敏感信息扫描无命中。

## 回溯对齐

- 对齐“真实可上线运行项目”目标：文档中心不再只依赖快照。
- 对齐“多人实时协作智能体控制台”目标：交付证据成为正式领域数据，后续可被不同角色和智能体引用。
- 对齐 Redis/RocketMQ 决策：本阶段不引入缓存或消息依赖。
