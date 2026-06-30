#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
ENV_FILE="${1:-${ENV_FILE:-${ROOT_DIR}/.env.local}}"
BACKUP_DIR="${2:-${MATRIXCODE_BACKUP_DIR:-${ROOT_DIR}/backups/mysql}}"

is_true() {
  [[ "${1:-}" == "true" || "${1:-}" == "TRUE" || "${1:-}" == "1" ]]
}

fail() {
  echo "MySQL 生产备份配置错误：$1" >&2
  exit 1
}

require_value() {
  local name="$1"
  local value="${!name:-}"
  [[ -n "${value}" ]] || fail "${name} 不能为空"
}

reject_placeholder() {
  local name="$1"
  local value="${!name:-}"
  case "${value}" in
    change-me*|replace_with*|example*|your_*)
      fail "${name} 不能使用占位值"
      ;;
  esac
}

if [[ ! -f "${ENV_FILE}" ]]; then
  fail "缺少环境文件 ${ENV_FILE}"
fi

set -a
source "${ENV_FILE}"
set +a

is_true "${MATRIXCODE_PRODUCTION_CHECK:-false}" || fail "MATRIXCODE_PRODUCTION_CHECK 必须为 true"
require_value MATRIXCODE_MYSQL_HOST
require_value MATRIXCODE_MYSQL_PORT
require_value MATRIXCODE_MYSQL_DATABASE
require_value MATRIXCODE_PERSISTENCE_JDBC_USERNAME
require_value MATRIXCODE_PERSISTENCE_JDBC_PASSWORD
reject_placeholder MATRIXCODE_PERSISTENCE_JDBC_PASSWORD

if is_true "${MATRIXCODE_BACKUP_DRY_RUN:-false}"; then
  echo "MySQL 备份干运行通过：${MATRIXCODE_MYSQL_HOST}:${MATRIXCODE_MYSQL_PORT}/${MATRIXCODE_MYSQL_DATABASE} -> ${BACKUP_DIR}"
  exit 0
fi

if ! command -v mysqldump >/dev/null 2>&1; then
  fail "未找到 mysqldump，请先在生产机器安装 MySQL client"
fi

mkdir -p "${BACKUP_DIR}"
chmod 700 "${BACKUP_DIR}"

timestamp="$(date +%Y%m%d%H%M%S)"
backup_file="${BACKUP_DIR}/${MATRIXCODE_MYSQL_DATABASE}-${timestamp}.sql.gz"

MYSQL_PWD="${MATRIXCODE_PERSISTENCE_JDBC_PASSWORD}" mysqldump \
  --host="${MATRIXCODE_MYSQL_HOST}" \
  --port="${MATRIXCODE_MYSQL_PORT}" \
  --user="${MATRIXCODE_PERSISTENCE_JDBC_USERNAME}" \
  --single-transaction \
  --routines \
  --triggers \
  --events \
  --databases "${MATRIXCODE_MYSQL_DATABASE}" \
  | gzip -c >"${backup_file}"

chmod 600 "${backup_file}"

if command -v sha256sum >/dev/null 2>&1; then
  sha256sum "${backup_file}" >"${backup_file}.sha256"
elif command -v shasum >/dev/null 2>&1; then
  shasum -a 256 "${backup_file}" >"${backup_file}.sha256"
fi

echo "MySQL 备份完成：${backup_file}"
