# MatrixCode MySQL 备份异地同步设计

## 背景

前置阶段已经完成 MySQL `matrix_code` 本机备份、备份轮转和 systemd 定时任务。本机轮转只能覆盖误删除或短期恢复场景，无法覆盖单机磁盘损坏、机器丢失或目录误清理。因此第 133 阶段增加受控的异地同步脚本。

## 目标

- 支持把 `matrix_code-*.sql.gz` 和对应 `.sha256` 文件同步到本机挂载目录或 `rsync` 远端。
- dry-run 默认不写入目标目录，只校验配置并列出待同步文件。
- 真实同步必须显式设置 `MATRIXCODE_BACKUP_OFFSITE_CONFIRM=true`。
- 拒绝明显危险的本地目标路径，避免误把备份复制到系统根目录或项目目录。

## 非目标

- 不自动删除异地目标上的历史备份。
- 不管理 SSH 密钥或远端账号。
- 不自动执行数据库恢复。
- 不把 Milvus snapshot 纳入本阶段，Milvus 当前仍按可重建向量上下文处理。

## 行为设计

脚本：`scripts/sync-production-mysql-backups.sh`

输入：

- 环境文件：默认 `.env.local`，生产推荐 `/etc/matrixcode/matrixcode.env`。
- 源目录：默认 `MATRIXCODE_BACKUP_DIR`，兜底 `/var/backups/matrixcode/mysql`。
- 目标：优先使用命令行第三个参数，其次使用调用方环境变量，再使用环境文件中的 `MATRIXCODE_BACKUP_OFFSITE_TARGET`。

门禁：

- `MATRIXCODE_PRODUCTION_CHECK=true`
- `MATRIXCODE_MYSQL_DATABASE` 非空。
- `MATRIXCODE_BACKUP_OFFSITE_TARGET` 非空。
- 真实执行必须 `MATRIXCODE_BACKUP_OFFSITE_CONFIRM=true`。

安全边界：

- 本地目标必须是绝对路径。
- 本地目标拒绝 `/`、`/tmp`、`/var`、`/var/backups`、`/opt`、`/opt/matrixcode`、项目目录和源备份目录。
- 远端目标使用 `rsync -az`，脚本不传递删除参数。

## 验证

阶段测试：

```bash
bash scripts/sync-production-mysql-backups-test.sh
```

聚合门禁：

```bash
bash scripts/verify-production-readiness.sh
```
