#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
ENV_FILE="${1:-${ENV_FILE:-${ROOT_DIR}/.env.local}}"

fail() {
  echo "MySQL 生产备份任务配置错误：$1" >&2
  exit 1
}

## 判断配置开关是否显式开启，兼容 true、TRUE 和 1 三种写法。
is_true() {
  [[ "${1:-}" == "true" || "${1:-}" == "TRUE" || "${1:-}" == "1" ]]
}

if [[ ! -f "${ENV_FILE}" ]]; then
  fail "缺少环境文件 ${ENV_FILE}"
fi

set -a
source "${ENV_FILE}"
set +a

backup_dir="${MATRIXCODE_BACKUP_DIR:-/var/backups/matrixcode/mysql}"
retention_days="${MATRIXCODE_BACKUP_RETENTION_DAYS:-14}"
sync_dir="${backup_dir}"

if is_true "${MATRIXCODE_BACKUP_JOB_DRY_RUN:-false}"; then
  export MATRIXCODE_BACKUP_DRY_RUN=true
  export MATRIXCODE_BACKUP_PRUNE_DRY_RUN=true
  prune_dir="$(mktemp -d)"
  sync_dir="$(mktemp -d)"
  database_name="${MATRIXCODE_MYSQL_DATABASE:-matrix_code}"
  printf 'dry-run backup\n' >"${sync_dir}/${database_name}-dry-run.sql.gz"
  printf 'dry-run checksum\n' >"${sync_dir}/${database_name}-dry-run.sql.gz.sha256"
  trap 'rm -rf "${prune_dir}" "${sync_dir}"' EXIT
else
  mkdir -p "${backup_dir}"
  prune_dir="${backup_dir}"
fi

bash "${ROOT_DIR}/scripts/backup-production-mysql.sh" "${ENV_FILE}" "${backup_dir}"
bash "${ROOT_DIR}/scripts/prune-production-mysql-backups.sh" "${ENV_FILE}" "${prune_dir}" "${retention_days}"

if is_true "${MATRIXCODE_BACKUP_OFFSITE_ENABLED:-false}"; then
  if is_true "${MATRIXCODE_BACKUP_JOB_DRY_RUN:-false}"; then
    export MATRIXCODE_BACKUP_OFFSITE_DRY_RUN=true
  fi
  bash "${ROOT_DIR}/scripts/sync-production-mysql-backups.sh" "${ENV_FILE}" "${sync_dir}"
fi

echo "MySQL 生产备份任务完成：${backup_dir}，保留 ${retention_days} 天。"
