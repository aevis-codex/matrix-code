#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
ENV_FILE="${1:-${ENV_FILE:-${ROOT_DIR}/.env.local}}"
BACKUP_DIR="${2:-${MATRIXCODE_BACKUP_DIR:-${ROOT_DIR}/backups/mysql}}"
RETENTION_DAYS="${3:-${MATRIXCODE_BACKUP_RETENTION_DAYS:-14}}"

is_true() {
  [[ "${1:-}" == "true" || "${1:-}" == "TRUE" || "${1:-}" == "1" ]]
}

fail() {
  echo "MySQL 备份轮转配置错误：$1" >&2
  exit 1
}

require_value() {
  local name="$1"
  local value="${!name:-}"
  [[ -n "${value}" ]] || fail "${name} 不能为空"
}

resolve_existing_dir() {
  local dir="$1"
  [[ -d "${dir}" ]] || fail "备份目录不存在：${dir}"
  (cd "${dir}" && pwd -P)
}

reject_dangerous_dir() {
  local dir="$1"
  case "${dir}" in
    /|/tmp|/var|/var/backups|/opt|/opt/matrixcode|"${ROOT_DIR}")
      fail "备份目录过于危险：${dir}"
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
require_value MATRIXCODE_MYSQL_DATABASE

if [[ ! "${RETENTION_DAYS}" =~ ^[0-9]+$ ]]; then
  fail "保留天数必须为正整数"
fi

if (( RETENTION_DAYS < 1 )); then
  fail "保留天数必须大于等于 1"
fi

resolved_backup_dir="$(resolve_existing_dir "${BACKUP_DIR}")"
reject_dangerous_dir "${resolved_backup_dir}"

candidate_list="$(mktemp)"
trap 'rm -f "${candidate_list}"' EXIT

find "${resolved_backup_dir}" \
  -maxdepth 1 \
  -type f \
  \( -name "${MATRIXCODE_MYSQL_DATABASE}-*.sql.gz" -o -name "${MATRIXCODE_MYSQL_DATABASE}-*.sql.gz.sha256" \) \
  -mtime +"${RETENTION_DAYS}" \
  -print >"${candidate_list}"

candidate_count="$(wc -l <"${candidate_list}" | tr -d ' ')"

if is_true "${MATRIXCODE_BACKUP_PRUNE_DRY_RUN:-false}"; then
  echo "MySQL 备份轮转干运行：将清理 ${candidate_count} 个过期文件，保留 ${RETENTION_DAYS} 天。"
  if [[ "${candidate_count}" != "0" ]]; then
    sed 's/^/- /' "${candidate_list}"
  fi
  exit 0
fi

while IFS= read -r backup_file; do
  [[ -n "${backup_file}" ]] || continue
  rm -f "${backup_file}"
  if [[ "${backup_file}" == *.sql.gz ]]; then
    rm -f "${backup_file}.sha256"
  fi
done <"${candidate_list}"

echo "MySQL 备份轮转完成：清理 ${candidate_count} 个过期文件，保留 ${RETENTION_DAYS} 天。"
