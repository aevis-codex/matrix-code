# MatrixCode 第 151 阶段：JDBC 快照兼容层默认只读设计

## 背景

第 150 阶段后，完整 JDBC 工作台流程已经不再产生 `workbench-state`、`local-execution` 或 `runtime-notifications` 主快照写入。但 `JdbcWorkbenchStateStore` 仍保留完整写方法，容易让后续维护误以为 `matrixcode_state_snapshots` 仍是生产主写路径。

## 目标

- JDBC 模式下，`JdbcWorkbenchStateStore` 默认不再写入新的 `workbench-state` 快照。
- 保留旧快照读取能力，继续支持正式表为空时的历史数据回填。
- 提供显式 legacy 开关，便于历史测试、回滚或人工迁移场景短期开启旧写入。
- 将该边界纳入生产就绪聚合门禁。

## 配置

- `MATRIXCODE_PERSISTENCE_JDBC_LEGACY_SNAPSHOT_WRITES_ENABLED=false`

默认值为 `false`。只有显式设置为 `true` 时，`JdbcWorkbenchStateStore` 才允许写入 `matrixcode_state_snapshots` 的 `workbench-state` 切片。

## 实现

- `PersistenceModeProperties.Jdbc` 新增 `legacySnapshotWritesEnabled`。
- `application.yml` 新增环境变量映射。
- `JdbcWorkbenchStateStore.writeSnapshot()` 在默认关闭时直接返回，保持同进程内存状态更新，但不持久化旧快照。
- 旧快照读取、损坏快照降级为空快照、正式仓储空表回填路径保持不变。
- 新增 `scripts/verify-jdbc-snapshot-boundary.sh`，并纳入 `scripts/verify-production-readiness.sh`，聚合门禁扩展为 17 项。

## 验证

- 红灯：`JdbcWorkbenchStateStoreTest` 先因三参构造器和配置项缺失编译失败。
- 目标测试：`JdbcWorkbenchStateStoreTest`、`MybatisPlusRoleModelBindingRepositoryTest`、`MybatisPlusLocalWorkspaceActivityRepositoryTest`、`JdbcPersistenceSpringTest` 8 条通过。
- 服务端全量：`mvn -pl server test` 621 条通过、0 failures、0 errors、8 skipped。
- 桌面端：`npm test` 138 条通过；`npm run build` 通过。
- 门禁：`verify-jdbc-snapshot-boundary.sh`、`verify-production-deployment-assets.sh`、`verify-production-readiness.sh` 通过，生产就绪聚合门禁 17/17。
- 真实运行：`MATRIXCODE_PROTOCOL_CHECK=true ./scripts/check-real-runtime.sh .env.local` 通过，真实 MySQL `matrix_code` 维持 Flyway `150.1`，真实协议级检查 2 条通过。

## 回溯结论

JDBC 快照层正式收敛为历史兼容和空表回填层，不再作为真实上线主写路径。后续缺口收敛到长期模型成本趋势分析、跨节点会话验证、生产实机发布复验，以及少量用户归属字段回溯。
