#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"

fail() {
  echo "生产部署资产验证失败：$1" >&2
  exit 1
}

assert_file() {
  local path="$1"
  [[ -f "${ROOT_DIR}/${path}" ]] || fail "缺少文件 ${path}"
}

assert_executable() {
  local path="$1"
  [[ -x "${ROOT_DIR}/${path}" ]] || fail "文件不可执行 ${path}"
}

assert_contains() {
  local path="$1"
  local text="$2"
  grep -Fq "${text}" "${ROOT_DIR}/${path}" || fail "${path} 缺少 ${text}"
}

assert_file ops/bin/run-matrixcode-server.sh
assert_file ops/systemd/matrixcode.service
assert_file ops/systemd/matrixcode-mysql-backup.service
assert_file ops/systemd/matrixcode-mysql-backup.timer
assert_file ops/systemd/matrixcode-health-probe.service
assert_file ops/systemd/matrixcode-health-probe.timer
assert_file ops/nginx/matrixcode.conf
assert_file ops/nginx/matrixcode-https.conf
assert_file ops/env/matrixcode.env.example
assert_file scripts/build-production-server.sh
assert_file scripts/package-production-release.sh
assert_file scripts/backup-production-mysql.sh
assert_file scripts/prune-production-mysql-backups.sh
assert_file scripts/run-production-mysql-backup.sh
assert_file scripts/sync-production-mysql-backups.sh
assert_file scripts/probe-production-health.sh
assert_file scripts/capture-production-health-snapshot.sh
assert_file scripts/install-production-release.sh
assert_file scripts/upload-production-release.sh
assert_file scripts/verify-remote-release-target.sh
assert_file scripts/restart-production-services.sh
assert_file scripts/rollback-production-release.sh
assert_file scripts/verify-production-readiness.sh
assert_file scripts/verify-jdbc-snapshot-boundary.sh
assert_file docs/deployment/production-runbook.md
assert_file docs/deployment/health-alerting.md
assert_file docs/deployment/release-candidate-log.md
assert_file docs/deployment/go-live-readiness-log.md

assert_executable ops/bin/run-matrixcode-server.sh
assert_executable scripts/build-production-server.sh
assert_executable scripts/package-production-release.sh
assert_executable scripts/backup-production-mysql.sh
assert_executable scripts/prune-production-mysql-backups.sh
assert_executable scripts/run-production-mysql-backup.sh
assert_executable scripts/sync-production-mysql-backups.sh
assert_executable scripts/probe-production-health.sh
assert_executable scripts/capture-production-health-snapshot.sh
assert_executable scripts/install-production-release.sh
assert_executable scripts/upload-production-release.sh
assert_executable scripts/verify-remote-release-target.sh
assert_executable scripts/restart-production-services.sh
assert_executable scripts/rollback-production-release.sh
assert_executable scripts/verify-production-readiness.sh
assert_executable scripts/verify-jdbc-snapshot-boundary.sh

bash -n \
  "${ROOT_DIR}/ops/bin/run-matrixcode-server.sh" \
  "${ROOT_DIR}/scripts/build-production-server.sh" \
  "${ROOT_DIR}/scripts/package-production-release.sh" \
  "${ROOT_DIR}/scripts/backup-production-mysql.sh" \
  "${ROOT_DIR}/scripts/prune-production-mysql-backups.sh" \
  "${ROOT_DIR}/scripts/run-production-mysql-backup.sh" \
  "${ROOT_DIR}/scripts/sync-production-mysql-backups.sh" \
  "${ROOT_DIR}/scripts/probe-production-health.sh" \
  "${ROOT_DIR}/scripts/capture-production-health-snapshot.sh" \
  "${ROOT_DIR}/scripts/install-production-release.sh" \
  "${ROOT_DIR}/scripts/upload-production-release.sh" \
  "${ROOT_DIR}/scripts/verify-remote-release-target.sh" \
  "${ROOT_DIR}/scripts/restart-production-services.sh" \
  "${ROOT_DIR}/scripts/rollback-production-release.sh" \
  "${ROOT_DIR}/scripts/verify-production-readiness.sh" \
  "${ROOT_DIR}/scripts/verify-jdbc-snapshot-boundary.sh"

assert_contains ops/bin/run-matrixcode-server.sh 'MATRIXCODE_RUN_PREFLIGHT'
assert_contains ops/bin/run-matrixcode-server.sh 'MATRIXCODE_PRODUCTION_DRY_RUN'
assert_contains ops/bin/run-matrixcode-server.sh 'check-real-runtime.sh'
assert_contains ops/bin/run-matrixcode-server.sh 'java'
assert_contains ops/systemd/matrixcode.service 'User=matrixcode'
assert_contains ops/systemd/matrixcode.service 'EnvironmentFile=/etc/matrixcode/matrixcode.env'
assert_contains ops/systemd/matrixcode.service 'ExecStart=/opt/matrixcode/bin/run-matrixcode-server.sh'
assert_contains ops/systemd/matrixcode.service 'Restart=on-failure'
assert_contains ops/systemd/matrixcode-mysql-backup.service 'ExecStart=/opt/matrixcode/scripts/run-production-mysql-backup.sh'
assert_contains ops/systemd/matrixcode-mysql-backup.timer 'OnCalendar=*-*-* 03:20:00'
assert_contains ops/systemd/matrixcode-health-probe.service 'ExecStart=/opt/matrixcode/scripts/probe-production-health.sh'
assert_contains ops/systemd/matrixcode-health-probe.timer 'OnUnitActiveSec=1min'
assert_contains ops/nginx/matrixcode.conf 'proxy_buffering off'
assert_contains ops/nginx/matrixcode.conf 'location /api/'
assert_contains ops/nginx/matrixcode.conf 'location = /actuator/health'
assert_contains ops/nginx/matrixcode.conf 'try_files $uri $uri/ /index.html'
assert_contains ops/nginx/matrixcode-https.conf 'listen 443 ssl http2;'
assert_contains ops/nginx/matrixcode-https.conf 'Strict-Transport-Security'
assert_contains ops/nginx/matrixcode-https.conf 'proxy_buffering off'
assert_contains ops/env/matrixcode.env.example 'MATRIXCODE_PRODUCTION_CHECK=true'
assert_contains ops/env/matrixcode.env.example 'MATRIXCODE_PROTOCOL_CHECK=true'
assert_contains ops/env/matrixcode.env.example 'MATRIXCODE_AUTH_REQUIRE_SA_TOKEN=true'
assert_contains ops/env/matrixcode.env.example 'MATRIXCODE_AUTH_SESSION_STORE=redis'
assert_contains ops/env/matrixcode.env.example 'MATRIXCODE_ROCKETMQ_PROTOCOL_CHECK='
assert_contains ops/env/matrixcode.env.example 'MATRIXCODE_ROCKETMQ_EVENT_RELAY_ENABLED='
assert_contains ops/env/matrixcode.env.example 'MATRIXCODE_ROCKETMQ_EVENT_RELAY_TOPIC_SUFFIX='
assert_contains ops/env/matrixcode.env.example 'MATRIXCODE_ROCKETMQ_EVENT_RELAY_CONSUMER_GROUP_SUFFIX='
assert_contains ops/env/matrixcode.env.example 'MATRIXCODE_HEALTH_PROBE_URL='
assert_contains ops/env/matrixcode.env.example 'MATRIXCODE_INFO_PROBE_URL='
assert_contains ops/env/matrixcode.env.example 'MATRIXCODE_HEALTH_SNAPSHOT_OUTPUT='
assert_contains ops/env/matrixcode.env.example 'MATRIXCODE_ALERT_WEBHOOK_URL='
assert_contains ops/env/matrixcode.env.example 'MATRIXCODE_ALERT_WEBHOOK_FORMAT='
assert_contains ops/env/matrixcode.env.example 'MATRIXCODE_ALERT_SEVERITY='
assert_contains ops/env/matrixcode.env.example 'MATRIXCODE_ALERT_ESCALATION_OWNER='
assert_contains ops/env/matrixcode.env.example 'MATRIXCODE_ALERT_RUNBOOK_URL='
assert_contains ops/env/matrixcode.env.example 'MATRIXCODE_ALERT_SILENCE_UNTIL_EPOCH_SECONDS='
assert_contains ops/env/matrixcode.env.example 'MATRIXCODE_BACKUP_DIR='
assert_contains ops/env/matrixcode.env.example 'MATRIXCODE_BACKUP_RETENTION_DAYS='
assert_contains ops/env/matrixcode.env.example 'MATRIXCODE_BACKUP_OFFSITE_ENABLED='
assert_contains ops/env/matrixcode.env.example 'MATRIXCODE_BACKUP_OFFSITE_TARGET='
assert_contains ops/env/matrixcode.env.example 'MATRIXCODE_REMOTE_RELEASE_TARGET='
assert_contains ops/env/matrixcode.env.example 'MATRIXCODE_REMOTE_APP_DIR='
assert_contains ops/env/matrixcode.env.example 'MATRIXCODE_REMOTE_RELEASE_PREFLIGHT_CONNECT='
assert_contains ops/env/matrixcode.env.example 'MATRIXCODE_REMOTE_INFO_PROBE_URL='
assert_contains ops/env/matrixcode.env.example 'MATRIXCODE_REMOTE_ENV_FILE='
assert_contains ops/env/matrixcode.env.example 'MATRIXCODE_REMOTE_HEALTH_SNAPSHOT_DIR='
assert_contains ops/env/matrixcode.env.example 'MATRIXCODE_DEPLOY_CONFIRM='
assert_contains ops/env/matrixcode.env.example 'MATRIXCODE_DEPLOY_CAPTURE_HEALTH_SNAPSHOT='
assert_contains ops/env/matrixcode.env.example 'MATRIXCODE_RESTART_CONFIRM='
assert_contains ops/env/matrixcode.env.example 'MATRIXCODE_ROLLBACK_CONFIRM='
assert_contains ops/env/matrixcode.env.example 'MATRIXCODE_ROLLBACK_AUDIT_LOG='
assert_contains scripts/build-production-server.sh 'matrixcode-server.jar'
assert_contains scripts/build-production-server.sh 'matrixcode-https.conf'
assert_contains scripts/build-production-server.sh 'run-production-mysql-backup.sh'
assert_contains scripts/build-production-server.sh 'sync-production-mysql-backups.sh'
assert_contains scripts/build-production-server.sh 'probe-production-health.sh'
assert_contains scripts/build-production-server.sh 'capture-production-health-snapshot.sh'
assert_contains scripts/build-production-server.sh 'install-production-release.sh'
assert_contains scripts/build-production-server.sh 'upload-production-release.sh'
assert_contains scripts/build-production-server.sh 'deploy-production-release.sh'
assert_contains scripts/build-production-server.sh 'verify-remote-release-target.sh'
assert_contains scripts/build-production-server.sh 'restart-production-services.sh'
assert_contains scripts/build-production-server.sh 'rollback-production-release.sh'
assert_contains scripts/install-production-release.sh 'MATRIXCODE_INSTALL_CONFIRM=true'
assert_contains scripts/upload-production-release.sh 'MATRIXCODE_REMOTE_RELEASE_CONFIRM=true'
assert_contains scripts/verify-remote-release-target.sh 'MATRIXCODE_REMOTE_RELEASE_PREFLIGHT_CONNECT'
assert_contains scripts/verify-remote-release-target.sh 'MATRIXCODE_REMOTE_ENV_FILE'
assert_contains scripts/verify-remote-release-target.sh 'capture-production-health-snapshot.sh'
assert_contains scripts/deploy-production-release.sh 'MATRIXCODE_DEPLOY_CONFIRM=true'
assert_contains scripts/deploy-production-release.sh 'MATRIXCODE_DEPLOY_CAPTURE_HEALTH_SNAPSHOT'
assert_contains scripts/deploy-production-release.sh 'capture-production-health-snapshot.sh'
assert_contains scripts/restart-production-services.sh 'MATRIXCODE_RESTART_CONFIRM=true'
assert_contains scripts/rollback-production-release.sh 'MATRIXCODE_ROLLBACK_CONFIRM=true'
assert_contains scripts/rollback-production-release.sh 'MATRIXCODE_ROLLBACK_AUDIT_LOG'
assert_contains scripts/probe-production-health.sh 'MATRIXCODE_ALERT_SILENCE_UNTIL_EPOCH_SECONDS'
assert_contains scripts/probe-production-health.sh 'MATRIXCODE_ALERT_WEBHOOK_FORMAT'
assert_contains scripts/probe-production-health.sh 'feishu'
assert_contains scripts/verify-production-readiness.sh '生产就绪聚合门禁通过'
assert_contains scripts/verify-production-readiness.sh 'verify-jdbc-snapshot-boundary.sh'
assert_contains scripts/verify-jdbc-snapshot-boundary.sh 'JDBC 快照边界验证通过'
assert_contains scripts/package-production-release.sh 'sha256'
assert_contains scripts/package-production-release.sh 'manifest'
assert_contains scripts/package-production-release.sh 'sync-production-mysql-backups.sh'
assert_contains scripts/package-production-release.sh 'upload-production-release.sh'
assert_contains scripts/package-production-release.sh 'restart-production-services.sh'
assert_contains scripts/package-production-release.sh 'rollback-production-release.sh'
assert_contains docs/deployment/health-alerting.md 'systemd timer'
assert_contains docs/deployment/health-alerting.md 'MATRIXCODE_ALERT_WEBHOOK_URL'
assert_contains docs/deployment/health-alerting.md 'MATRIXCODE_ALERT_WEBHOOK_FORMAT'
assert_contains docs/deployment/health-alerting.md 'ops/alerting/matrixcode-escalation-schedule.example.md'
assert_contains ops/alerting/matrixcode-escalation-schedule.example.md 'P1'
assert_contains ops/alerting/matrixcode-escalation-schedule.example.md '升级路径'
assert_contains docs/deployment/production-runbook.md 'systemd'
assert_contains docs/deployment/production-runbook.md 'Nginx'
assert_contains docs/deployment/production-runbook.md '回滚'
assert_contains docs/deployment/release-candidate-log.md '生产发布候选记录'
assert_contains docs/deployment/go-live-readiness-log.md '真实上线就绪审计记录'

echo "生产部署资产验证通过。"
