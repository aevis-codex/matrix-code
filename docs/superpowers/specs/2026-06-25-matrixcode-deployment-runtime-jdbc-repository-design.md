# 部署运行态正式 MySQL 仓储设计

## 背景

第 32 阶段已将部署目标迁移到正式 MySQL 表，但部署操作、健康检查、Compose 环境和 Compose 操作仍由 `workbench-state` 快照承载。真实上线后，这些数据属于运维审计和发布追踪链路，不能长期依赖单个 JSON/快照切片。

## 目标

- 为部署操作、部署健康检查、Compose 环境、Compose 操作建立正式 MySQL 表。
- 让 JDBC 模式下的服务优先读写正式表，旧 `workbench-state` 仅作为空表回填来源。
- 保持现有工作台 API、桌面端字段和运行态事件不变。
- 不引入 Redis/RocketMQ 业务依赖；本阶段只处理持久化。

## 推荐方案

采用单个 `DeploymentRuntimeRepository` 聚合仓储，覆盖四类部署运行态数据：

- `matrixcode_deployment_operations`
- `matrixcode_deployment_health_checks`
- `matrixcode_compose_environments`
- `matrixcode_compose_operations`

理由：

- 三个服务目前共同维护运维运行态，聚合仓储可以减少重复的 JDBC 可选依赖和回填逻辑。
- 服务层仍保持业务边界：部署操作、健康检查、Compose 操作各自保留现有校验和运行逻辑。
- 旧快照回填可以一次性表达为“正式表为空时导入旧数据”，避免长期双写。

## 数据流

1. Spring JDBC 模式启用时注册 `JdbcDeploymentRuntimeRepository`。
2. 服务启动时：
   - 若正式表有数据，加载正式表。
   - 若正式表为空，读取 `workbench-state` 中对应切片并写入正式表。
3. 新增运行态记录时：
   - 服务继续维护内存中的最近 20 条限制。
   - JDBC 模式写正式表；非 JDBC 模式继续写 `WorkbenchStateStore`。
4. 工作台读取路径不变，仍通过现有服务聚合摘要。

## 错误处理

- JDBC URL 为空或 SQL 执行失败时抛出带中文上下文的 `IllegalStateException`。
- `project_id` 不存在时仓储自动 upsert 最小项目记录，沿用现有正式仓储模式。
- 运行态表不强制外键到部署目标或 Compose 环境，降低旧快照回填顺序对启动的影响；通过索引保留查询能力。

## 验证

- 新增 JDBC 仓储测试，覆盖四类数据保存和恢复。
- 新增服务接入测试，覆盖正式仓储优先和旧快照空表回填。
- 运行关联测试、服务端全量测试、diff 检查和敏感信息扫描。
