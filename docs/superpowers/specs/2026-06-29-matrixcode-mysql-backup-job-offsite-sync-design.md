# MatrixCode MySQL 定时备份任务异地同步设计

## 背景

第 133 阶段已经提供独立的 MySQL 备份异地同步脚本。若生产环境只依赖人工单独执行同步，定时备份仍可能只停留在本机目录。因此第 134 阶段把异地同步作为定时备份任务的可选步骤接入。

## 目标

- `run-production-mysql-backup.sh` 在 `MATRIXCODE_BACKUP_OFFSITE_ENABLED=true` 时自动调用 `sync-production-mysql-backups.sh`。
- 默认关闭，不改变已有生产备份任务行为。
- `MATRIXCODE_BACKUP_JOB_DRY_RUN=true` 时同步步骤也进入 dry-run，不创建真实备份目录或异地目标。
- 真实同步仍复用第 133 阶段的显式确认门禁：`MATRIXCODE_BACKUP_OFFSITE_CONFIRM=true`。

## 非目标

- 不新增 systemd service 或 timer。
- 不自动删除异地目标上的历史备份。
- 不管理远端 SSH 凭据。
- 不自动执行数据库恢复。

## 行为设计

备份任务顺序：

1. 执行 MySQL 备份或备份 dry-run。
2. 执行本地轮转或轮转 dry-run。
3. 如果 `MATRIXCODE_BACKUP_OFFSITE_ENABLED=true`，执行异地同步或同步 dry-run。

dry-run 设计：

- 备份脚本本身不会写真实备份文件。
- wrapper 创建临时 `matrix_code-dry-run.sql.gz` 和 `.sha256` 文件，只用于驱动同步脚本校验目标配置。
- 临时目录在脚本退出时清理。

## 验证

阶段测试：

```bash
bash scripts/run-production-mysql-backup-test.sh
```

聚合门禁：

```bash
bash scripts/verify-production-readiness.sh
```
