# MatrixCode 生产备份与恢复

## 范围

当前备份基线覆盖业务数据 MySQL `matrix_code`。Milvus 当前承载向量上下文，可由业务文档和模型上下文重新写入；如后续把不可重建向量数据作为正式资产，需要单独增加 Milvus snapshot 或对象存储备份策略。

## 备份前提

生产机器需要安装 MySQL client，并确保 `mysqldump` 在 `PATH` 中。

环境文件使用 `/etc/matrixcode/matrixcode.env` 或等价文件，必须配置：

- `MATRIXCODE_PRODUCTION_CHECK=true`
- `MATRIXCODE_MYSQL_HOST`
- `MATRIXCODE_MYSQL_PORT`
- `MATRIXCODE_MYSQL_DATABASE`
- `MATRIXCODE_PERSISTENCE_JDBC_USERNAME`
- `MATRIXCODE_PERSISTENCE_JDBC_PASSWORD`

脚本会拒绝明显占位的数据库凭据。

## dry-run

```bash
MATRIXCODE_BACKUP_DRY_RUN=true ./scripts/backup-production-mysql.sh /etc/matrixcode/matrixcode.env /var/backups/matrixcode/mysql
```

dry-run 只校验环境变量和输出目标，不连接数据库。

## 执行备份

```bash
./scripts/backup-production-mysql.sh /etc/matrixcode/matrixcode.env /var/backups/matrixcode/mysql
```

脚本会生成：

- `matrix_code-YYYYMMDDHHMMSS.sql.gz`
- 可选的 `matrix_code-YYYYMMDDHHMMSS.sql.gz.sha256`

备份文件权限为 `600`，备份目录权限为 `700`。

## 备份轮转

生产保留期通过第三个参数或 `MATRIXCODE_BACKUP_RETENTION_DAYS` 控制，默认建议不少于 14 天。先执行 dry-run：

```bash
MATRIXCODE_BACKUP_PRUNE_DRY_RUN=true ./scripts/prune-production-mysql-backups.sh /etc/matrixcode/matrixcode.env /var/backups/matrixcode/mysql 14
```

确认输出中的文件均可清理后，再执行真实轮转：

```bash
./scripts/prune-production-mysql-backups.sh /etc/matrixcode/matrixcode.env /var/backups/matrixcode/mysql 14
```

脚本只清理当前数据库名前缀的 `*.sql.gz` 和 `*.sql.gz.sha256` 文件，并拒绝 `/`、`/tmp`、`/var`、`/var/backups`、`/opt`、`/opt/matrixcode` 和仓库根目录等危险路径。

## 异地同步

本机备份完成后，需要把 `matrix_code` 的备份文件同步到离机位置。发布包内包含：

- `/opt/matrixcode/scripts/sync-production-mysql-backups.sh`

配置项：

- `MATRIXCODE_BACKUP_OFFSITE_ENABLED`：设为 `true` 后，定时备份任务会在本机备份和轮转后调用异地同步脚本；默认关闭。
- `MATRIXCODE_BACKUP_OFFSITE_TARGET`：异地目标。支持本机挂载目录，例如 `/mnt/matrixcode-offsite/mysql`；也支持 `rsync` 远端，例如 `backup@example.com:/srv/backups/matrixcode/mysql`。
- `MATRIXCODE_BACKUP_OFFSITE_DRY_RUN`：设为 `true` 时只校验配置并列出待同步文件。
- `MATRIXCODE_BACKUP_OFFSITE_CONFIRM`：真实执行必须显式设为 `true`。

先执行 dry-run：

```bash
MATRIXCODE_BACKUP_OFFSITE_DRY_RUN=true \
  /opt/matrixcode/scripts/sync-production-mysql-backups.sh /etc/matrixcode/matrixcode.env
```

确认目标和文件列表后，再执行真实同步：

```bash
MATRIXCODE_BACKUP_OFFSITE_CONFIRM=true \
  /opt/matrixcode/scripts/sync-production-mysql-backups.sh /etc/matrixcode/matrixcode.env
```

脚本只同步当前数据库名前缀的文件：

- `matrix_code-YYYYMMDDHHMMSS.sql.gz`
- `matrix_code-YYYYMMDDHHMMSS.sql.gz.sha256`

脚本不会删除异地目标上的历史文件，也不会保存 SSH 密钥。使用远端目标时，生产机器需要提前配置只允许写入备份目录的 SSH 凭据。

定时备份任务也支持同步 dry-run：

```bash
MATRIXCODE_BACKUP_JOB_DRY_RUN=true \
  /opt/matrixcode/scripts/run-production-mysql-backup.sh /etc/matrixcode/matrixcode.env
```

当环境文件中 `MATRIXCODE_BACKUP_OFFSITE_ENABLED=true` 时，任务 dry-run 会用临时占位备份文件演练异地同步配置，不写入真实备份目录或异地目标。

## 定时备份

发布包内包含定时备份 wrapper 和 systemd timer：

- `/opt/matrixcode/scripts/run-production-mysql-backup.sh`
- `/opt/matrixcode/ops/systemd/matrixcode-mysql-backup.service`
- `/opt/matrixcode/ops/systemd/matrixcode-mysql-backup.timer`

先执行 dry-run：

```bash
MATRIXCODE_BACKUP_JOB_DRY_RUN=true /opt/matrixcode/scripts/run-production-mysql-backup.sh /etc/matrixcode/matrixcode.env
```

安装 timer：

```bash
sudo cp /opt/matrixcode/ops/systemd/matrixcode-mysql-backup.service /etc/systemd/system/matrixcode-mysql-backup.service
sudo cp /opt/matrixcode/ops/systemd/matrixcode-mysql-backup.timer /etc/systemd/system/matrixcode-mysql-backup.timer
sudo systemctl daemon-reload
sudo systemctl enable --now matrixcode-mysql-backup.timer
systemctl list-timers matrixcode-mysql-backup.timer
```

默认每天 03:20 执行一次，失败状态通过 systemd 记录；第 143 阶段后可复用健康探测告警格式接入统一告警。

## 恢复原则

恢复是高风险操作，不能由 MatrixCode 自动执行。建议流程：

1. 停止写入流量或进入维护窗口。
2. 备份当前线上库。
3. 在临时库验证备份文件可解压、可导入、核心表可查询。
4. 确认目标库、备份文件、操作者和回滚窗口。
5. 手工执行导入。

示例命令：

```bash
set -a
source /etc/matrixcode/matrixcode.env
set +a
gunzip -c /var/backups/matrixcode/mysql/matrix_code-YYYYMMDDHHMMSS.sql.gz \
  | MYSQL_PWD="${MATRIXCODE_PERSISTENCE_JDBC_PASSWORD}" mysql \
      --host="${MATRIXCODE_MYSQL_HOST}" \
      --port="${MATRIXCODE_MYSQL_PORT}" \
      --user="${MATRIXCODE_PERSISTENCE_JDBC_USERNAME}"
```

恢复后执行：

```bash
MATRIXCODE_PRODUCTION_DRY_RUN=true /opt/matrixcode/bin/run-matrixcode-server.sh
curl -fsS http://127.0.0.1:8080/actuator/health
```

## 验证命令

```bash
bash scripts/backup-production-mysql-test.sh
bash scripts/prune-production-mysql-backups-test.sh
bash scripts/run-production-mysql-backup-test.sh
bash scripts/sync-production-mysql-backups-test.sh
```
