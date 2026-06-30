# MatrixCode 生产部署运行手册

## 目标

本手册覆盖单机生产部署基线：后端 Jar、桌面端 Web 静态产物、systemd 进程管理、Nginx 反向代理、启动前真实依赖门禁和回滚步骤。当前阶段不执行远程 SSH 自动部署，不读取真实凭据，不替代云厂商或容器平台的发布系统。

## 产物构建

在仓库根目录执行：

```bash
bash scripts/verify-production-deployment-assets.sh
bash scripts/verify-production-readiness.sh
bash scripts/build-production-server.sh
bash scripts/package-production-release.sh
```

默认发布目录为 `dist/matrixcode-server`，可通过 `MATRIXCODE_DIST_DIR` 覆盖。目录内包含：

- `matrixcode-server.jar`
- `bin/run-matrixcode-server.sh`
- `scripts/check-real-runtime.sh`
- `scripts/backup-production-mysql.sh`
- `scripts/prune-production-mysql-backups.sh`
- `scripts/run-production-mysql-backup.sh`
- `scripts/sync-production-mysql-backups.sh`
- `scripts/probe-production-health.sh`
- `scripts/upload-production-release.sh`
- `scripts/smoke-production-release.sh`
- `scripts/generate-production-release-candidate.sh`
- `scripts/deploy-production-release.sh`
- `scripts/verify-remote-release-target.sh`
- `scripts/restart-production-services.sh`
- `scripts/rollback-production-release.sh`
- `desktop/`
- `ops/systemd/matrixcode.service`
- `ops/systemd/matrixcode-mysql-backup.service`
- `ops/systemd/matrixcode-mysql-backup.timer`
- `ops/systemd/matrixcode-health-probe.service`
- `ops/systemd/matrixcode-health-probe.timer`
- `ops/nginx/matrixcode.conf`
- `ops/env/matrixcode.env.example`

`scripts/package-production-release.sh` 会在 `dist/releases/` 生成 `*.tar.gz`、`*.tar.gz.sha256` 和 `*.manifest`，用于传输到服务器、校验和回滚登记。

发布归档生成后，先对最终归档执行 smoke。该步骤会校验 `sha256`、解压归档、检查包内启动脚本和真实运行检查脚本，并使用生产 env 副本强制 dry-run，不启动长期 Java 进程：

```bash
bash scripts/smoke-production-release.sh dist/releases/matrixcode-server-YYYYMMDDHHMMSS.tar.gz .env.local
```

smoke 通过后再进入远程复制、服务器安装和重启步骤。

发布窗口前生成发布候选验收报告。该报告会校验归档 SHA256，读取 manifest，并把生产就绪聚合门禁、发布包 smoke、真实协议级检查和可选远程发布 dry-run 的结果写入 Markdown，同时把完整日志放到同名目录：

```bash
bash scripts/generate-production-release-candidate.sh dist/releases/matrixcode-server-YYYYMMDDHHMMSS.tar.gz .env.local
```

默认报告路径为 `dist/releases/matrixcode-server-YYYYMMDDHHMMSS.release-candidate.md`，日志目录为 `dist/releases/matrixcode-server-YYYYMMDDHHMMSS.release-candidate.d/`。报告只记录归档路径、校验和、Git 提交、env 文件指纹和日志路径，不记录真实密钥或 prompt 内容。只需要生成结构化清单、不执行门禁时，可临时设置 `MATRIXCODE_RELEASE_CANDIDATE_SKIP_CHECKS=true`，该模式不能作为上线验收依据。

已生成的候选包低敏摘要记录在 `docs/deployment/release-candidate-log.md`；完整日志仍保留在 `dist/releases/*.release-candidate.d/`，不提交仓库。

真实上线前的远程目标接入审计记录在 `docs/deployment/go-live-readiness-log.md`。该记录只保存低敏端口探测、SSH BatchMode 结果和上线恢复条件，不保存任何凭据。

### 上线后健康快照

服务启动或重启成功后，生成一份低敏健康快照，作为发布验收记录的一部分：

```bash
/opt/matrixcode/scripts/capture-production-health-snapshot.sh /etc/matrixcode/matrixcode.env /var/log/matrixcode/health-snapshots/$(date +%Y%m%d%H%M%S).md
```

快照会读取 `/actuator/health` 和 `/actuator/info`，记录 Git 提交、env 文件 SHA256、关键上线配置和 RocketMQ 中继开关状态。快照不记录数据库密码、API Key、Sa-Token、模型响应、完整 prompt、向量正文或工具输出。健康状态不是 `UP` 时脚本仍会写出快照并返回非零退出码。

### RocketMQ 项目事件中继门禁

多人实时协作的项目事件流可以通过 RocketMQ 做跨节点中继。生产 env 示例默认关闭中继，避免 TCP 可达但真实 topic 发送不可用时影响单实例上线。生产环境启用时，至少配置：

```bash
MATRIXCODE_ROCKETMQ_NAME_SERVER=127.0.0.1:9876
MATRIXCODE_ROCKETMQ_TOPIC_PREFIX=matrixcode
MATRIXCODE_ROCKETMQ_EVENT_RELAY_ENABLED=true
MATRIXCODE_ROCKETMQ_PROTOCOL_CHECK=true
MATRIXCODE_ROCKETMQ_EVENT_RELAY_TOPIC_SUFFIX=project-events
MATRIXCODE_ROCKETMQ_EVENT_RELAY_TAG=project-event
```

上线前不要只看 9876 端口连通性。需要打开真实收发门禁：

```bash
MATRIXCODE_PROTOCOL_CHECK=true \
MATRIXCODE_ROCKETMQ_PROTOCOL_CHECK=true \
  bash scripts/check-real-runtime.sh /etc/matrixcode/matrixcode.env
```

该检查会向 `matrixcode-project-events` 发送一条低敏 `ProjectEvent`，并等待同一消费者收到消息。如果出现 `sendDefaultImpl call timeout` 或 broker 地址为内网容器名，通常需要在 RocketMQ broker 侧修正 `brokerIP1`、10911 端口暴露、防火墙、代理或 Topic 权限后再发布。

如果部署机已经配置好 SSH 权限，可以先做远程发布目标预检。默认只校验本地配置，不连接远端；显式设置 `MATRIXCODE_REMOTE_RELEASE_PREFLIGHT_CONNECT=true` 后，会用 SSH 做只读命令能力检查：

```bash
MATRIXCODE_REMOTE_RELEASE_TARGET=deploy@example.com \
MATRIXCODE_REMOTE_RELEASE_DIR=/srv/matrixcode/releases \
MATRIXCODE_REMOTE_APP_DIR=/opt/matrixcode \
MATRIXCODE_REMOTE_HEALTH_PROBE_URL=http://127.0.0.1:8080/actuator/health \
MATRIXCODE_REMOTE_INFO_PROBE_URL=http://127.0.0.1:8080/actuator/info \
MATRIXCODE_REMOTE_ENV_FILE=/etc/matrixcode/matrixcode.env \
MATRIXCODE_REMOTE_HEALTH_SNAPSHOT_DIR=/var/log/matrixcode/health-snapshots \
  bash scripts/verify-remote-release-target.sh
```

开启 `MATRIXCODE_REMOTE_RELEASE_PREFLIGHT_CONNECT=true` 时，预检会通过 SSH 检查 `tar`、`mktemp`、`systemctl`、`curl`、应用目录父目录、健康快照脚本、远程 env 可读性和快照目录可写性。

预检通过后，可以使用远程发布编排脚本串联复制、服务器侧安装、服务重启和健康探测。先执行 dry-run：

```bash
MATRIXCODE_DEPLOY_DRY_RUN=true \
MATRIXCODE_REMOTE_RELEASE_TARGET=deploy@example.com \
MATRIXCODE_REMOTE_RELEASE_DIR=/srv/matrixcode/releases \
MATRIXCODE_REMOTE_APP_DIR=/opt/matrixcode \
MATRIXCODE_REMOTE_HEALTH_PROBE_URL=http://127.0.0.1:8080/actuator/health \
MATRIXCODE_REMOTE_INFO_PROBE_URL=http://127.0.0.1:8080/actuator/info \
MATRIXCODE_REMOTE_ENV_FILE=/etc/matrixcode/matrixcode.env \
MATRIXCODE_REMOTE_HEALTH_SNAPSHOT_DIR=/var/log/matrixcode/health-snapshots \
  bash scripts/deploy-production-release.sh dist/releases/matrixcode-server-YYYYMMDDHHMMSS.tar.gz
```

确认目标主机、发布暂存目录、应用目录、服务名和健康探测地址无误后，再显式确认执行：

```bash
MATRIXCODE_DEPLOY_CONFIRM=true \
MATRIXCODE_REMOTE_RELEASE_TARGET=deploy@example.com \
MATRIXCODE_REMOTE_RELEASE_DIR=/srv/matrixcode/releases \
MATRIXCODE_REMOTE_APP_DIR=/opt/matrixcode \
MATRIXCODE_REMOTE_SYSTEMD_SERVICE=matrixcode \
MATRIXCODE_REMOTE_HEALTH_PROBE_URL=http://127.0.0.1:8080/actuator/health \
MATRIXCODE_REMOTE_INFO_PROBE_URL=http://127.0.0.1:8080/actuator/info \
MATRIXCODE_REMOTE_ENV_FILE=/etc/matrixcode/matrixcode.env \
MATRIXCODE_REMOTE_HEALTH_SNAPSHOT_DIR=/var/log/matrixcode/health-snapshots \
  bash scripts/deploy-production-release.sh dist/releases/matrixcode-server-YYYYMMDDHHMMSS.tar.gz
```

编排脚本会在远程重启成功后默认生成低敏健康快照。需要临时跳过时设置 `MATRIXCODE_DEPLOY_CAPTURE_HEALTH_SNAPSHOT=false`，但这不能作为完整上线验收依据。编排脚本不保存 SSH 密钥或 sudo 凭据；真实执行依赖部署机和目标服务器提前配置最小权限。

复制到服务器前先执行 dry-run：

```bash
MATRIXCODE_REMOTE_RELEASE_DRY_RUN=true \
MATRIXCODE_REMOTE_RELEASE_TARGET=deploy@example.com \
MATRIXCODE_REMOTE_RELEASE_DIR=/tmp/matrixcode-releases \
  bash scripts/upload-production-release.sh dist/releases/matrixcode-server-YYYYMMDDHHMMSS.tar.gz
```

确认目标主机、远端目录和校验文件后，再显式确认复制：

```bash
MATRIXCODE_REMOTE_RELEASE_CONFIRM=true \
MATRIXCODE_REMOTE_RELEASE_TARGET=deploy@example.com \
MATRIXCODE_REMOTE_RELEASE_DIR=/tmp/matrixcode-releases \
  bash scripts/upload-production-release.sh dist/releases/matrixcode-server-YYYYMMDDHHMMSS.tar.gz
```

远程复制脚本只上传归档、sha256 和 manifest，不执行安装、不执行 `systemctl restart` 或 `nginx reload`。

归档传输到服务器后，可先执行安装 dry-run：

```bash
MATRIXCODE_INSTALL_DRY_RUN=true /opt/matrixcode/scripts/install-production-release.sh /tmp/matrixcode-server.tar.gz /opt/matrixcode
```

确认归档、sha256 和目标路径无误后，再显式确认安装：

```bash
MATRIXCODE_INSTALL_CONFIRM=true /opt/matrixcode/scripts/install-production-release.sh /tmp/matrixcode-server.tar.gz /opt/matrixcode
```

安装脚本会把旧目录移动为 `/opt/matrixcode.previous.YYYYMMDDHHMMSS`，再解压新归档。本脚本不执行 `systemctl restart` 或 `nginx reload`，这些动作仍按本手册步骤显式执行。

安装完成后，先执行服务重启 dry-run：

```bash
MATRIXCODE_RESTART_DRY_RUN=true \
MATRIXCODE_SYSTEMD_SERVICE=matrixcode \
MATRIXCODE_HEALTH_PROBE_URL=http://127.0.0.1:8080/actuator/health \
  /opt/matrixcode/scripts/restart-production-services.sh
```

确认服务名、健康检查地址和 Nginx reload 策略后，再显式确认重启：

```bash
MATRIXCODE_RESTART_CONFIRM=true \
MATRIXCODE_SYSTEMD_SERVICE=matrixcode \
MATRIXCODE_RELOAD_NGINX=true \
MATRIXCODE_NGINX_SERVICE=nginx \
MATRIXCODE_HEALTH_PROBE_URL=http://127.0.0.1:8080/actuator/health \
  /opt/matrixcode/scripts/restart-production-services.sh
```

重启脚本只执行本机 `systemctl restart`、健康检查和可选 `systemctl reload nginx`，不保存 sudo 凭据，不远程登录其他主机。真实执行必须设置 `MATRIXCODE_RESTART_CONFIRM=true`。

## 服务器目录

推荐目录约定：

```bash
sudo useradd --system --home /opt/matrixcode --shell /usr/sbin/nologin matrixcode
sudo mkdir -p /opt/matrixcode /etc/matrixcode /var/log/matrixcode
sudo cp -R dist/matrixcode-server/. /opt/matrixcode/
sudo cp /opt/matrixcode/ops/env/matrixcode.env.example /etc/matrixcode/matrixcode.env
sudo chown -R matrixcode:matrixcode /opt/matrixcode /var/log/matrixcode
sudo chmod 640 /etc/matrixcode/matrixcode.env
```

`/etc/matrixcode/matrixcode.env` 只写环境变量名和真实值，不提交仓库。生产必须保持：

- `MATRIXCODE_PRODUCTION_CHECK=true`
- `MATRIXCODE_PROTOCOL_CHECK=true`
- `MATRIXCODE_AUTH_REQUIRE_SA_TOKEN=true`
- `MATRIXCODE_AUTH_SESSION_STORE=redis`
- `MATRIXCODE_RUN_PREFLIGHT=true`

第 153 阶段后，`MATRIXCODE_PROTOCOL_CHECK=true` 会在 Redis Session 模式下同时执行 Sa-Token Redis Session 真实写读删集成测试。

首个项目 Owner 初始化只在首次建库时临时开启，确认成员创建后关闭 `MATRIXCODE_BOOTSTRAP_INITIAL_PROJECT_ENABLED`。

## 启动前检查

在服务机上执行：

```bash
sudo -u matrixcode MATRIXCODE_PRODUCTION_DRY_RUN=true /opt/matrixcode/bin/run-matrixcode-server.sh
```

该命令会加载 `/etc/matrixcode/matrixcode.env`，执行 `check-real-runtime.sh`，校验 MySQL、Milvus、Redis、RocketMQ、Sa-Token、执行代理凭据和协议级集成链路。

## systemd

安装服务：

```bash
sudo cp /opt/matrixcode/ops/systemd/matrixcode.service /etc/systemd/system/matrixcode.service
sudo systemctl daemon-reload
sudo systemctl enable matrixcode
sudo systemctl start matrixcode
sudo systemctl status matrixcode --no-pager
```

查看日志：

```bash
journalctl -u matrixcode -f
```

健康检查：

```bash
curl -fsS http://127.0.0.1:8080/actuator/health
```

主动健康探测和 webhook 告警模板见 `docs/deployment/health-alerting.md`。

## Nginx

安装反向代理模板：

```bash
sudo cp /opt/matrixcode/ops/nginx/matrixcode.conf /etc/nginx/conf.d/matrixcode.conf
sudo nginx -t
sudo systemctl reload nginx
```

模板行为：

- `/api/` 代理到 `127.0.0.1:8080`。
- `/api/projects/{projectId}/events/stream` 关闭缓冲，支持 SSE。
- `/actuator/health` 和 `/actuator/info` 可被探活访问。
- 其他 `/actuator/` 默认返回 404。
- `/` 读取 `/opt/matrixcode/desktop` 静态产物。

上线时必须把 `server_name matrixcode.example.com` 改成真实域名。HTTPS 入口使用 `/opt/matrixcode/ops/nginx/matrixcode-https.conf`，证书申请、替换和验证步骤见 `docs/deployment/tls-and-domain.md`。

## 回滚

推荐每次发布前保留上一版目录：

```bash
sudo cp -a /opt/matrixcode /opt/matrixcode.previous
```

先执行回滚 dry-run：

```bash
MATRIXCODE_ROLLBACK_DRY_RUN=true \
  /opt/matrixcode/scripts/rollback-production-release.sh \
  /opt/matrixcode.previous.YYYYMMDDHHMMSS \
  /opt/matrixcode
```

确认上一版目录和目标目录无误后，再显式确认回滚：

```bash
sudo MATRIXCODE_ROLLBACK_CONFIRM=true \
  MATRIXCODE_ROLLBACK_AUDIT_LOG=/var/log/matrixcode/rollback-audit.jsonl \
  /opt/matrixcode/scripts/rollback-production-release.sh \
  /opt/matrixcode.previous.YYYYMMDDHHMMSS \
  /opt/matrixcode
sudo MATRIXCODE_RESTART_CONFIRM=true \
  MATRIXCODE_SYSTEMD_SERVICE=matrixcode \
  MATRIXCODE_HEALTH_PROBE_URL=http://127.0.0.1:8080/actuator/health \
  /opt/matrixcode/scripts/restart-production-services.sh
```

回滚脚本会把当前目标目录移动为 `/opt/matrixcode.failed.YYYYMMDDHHMMSS`，再复制上一版目录到 `/opt/matrixcode`。配置 `MATRIXCODE_ROLLBACK_AUDIT_LOG` 后，真实回滚会追加一行 JSONL 审计记录。本脚本不执行数据库回滚，不自动删除失败目录，不执行远程 SSH。

数据库迁移已经由 Flyway 管理。执行涉及不可逆 DDL 的阶段前，需要单独做 MySQL 备份；备份与恢复原则见 `docs/deployment/backup-restore.md`。本手册不把数据库回滚自动化，避免误删真实业务数据。
