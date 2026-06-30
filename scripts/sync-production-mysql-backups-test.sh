#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
TMP_DIR="$(mktemp -d)"
trap 'rm -rf "${TMP_DIR}"' EXIT

write_env() {
  local env_file="$1"
  local production_check="$2"
  cat >"${env_file}" <<EOF_ENV
MATRIXCODE_PRODUCTION_CHECK=${production_check}
MATRIXCODE_MYSQL_DATABASE=matrix_code
MATRIXCODE_BACKUP_DIR=${TMP_DIR}/backups
MATRIXCODE_BACKUP_OFFSITE_TARGET=${TMP_DIR}/offsite
EOF_ENV
}

assert_contains() {
  local haystack="$1"
  local needle="$2"
  if [[ "${haystack}" != *"${needle}"* ]]; then
    echo "断言失败：输出中缺少 ${needle}" >&2
    echo "${haystack}" >&2
    exit 1
  fi
}

assert_file_exists() {
  local path="$1"
  [[ -f "${path}" ]] || {
    echo "断言失败：文件应存在 ${path}" >&2
    exit 1
  }
}

assert_file_missing() {
  local path="$1"
  [[ ! -e "${path}" ]] || {
    echo "断言失败：文件不应存在 ${path}" >&2
    exit 1
  }
}

mkdir -p "${TMP_DIR}/backups"
printf 'matrix backup\n' >"${TMP_DIR}/backups/matrix_code-20260101010101.sql.gz"
printf 'checksum\n' >"${TMP_DIR}/backups/matrix_code-20260101010101.sql.gz.sha256"
printf 'other backup\n' >"${TMP_DIR}/backups/other-20260101010101.sql.gz"

bad_gate_env="${TMP_DIR}/bad-gate.env"
write_env "${bad_gate_env}" false
if output="$(MATRIXCODE_BACKUP_OFFSITE_DRY_RUN=true "${ROOT_DIR}/scripts/sync-production-mysql-backups.sh" "${bad_gate_env}" 2>&1)"; then
  echo "断言失败：异地同步应拒绝未开启生产门禁的配置" >&2
  echo "${output}" >&2
  exit 1
fi
assert_contains "${output}" "MATRIXCODE_PRODUCTION_CHECK 必须为 true"

missing_target_env="${TMP_DIR}/missing-target.env"
write_env "${missing_target_env}" true
if output="$(MATRIXCODE_BACKUP_OFFSITE_TARGET= MATRIXCODE_BACKUP_OFFSITE_DRY_RUN=true "${ROOT_DIR}/scripts/sync-production-mysql-backups.sh" "${missing_target_env}" 2>&1)"; then
  echo "断言失败：异地同步应拒绝空目标地址" >&2
  echo "${output}" >&2
  exit 1
fi
assert_contains "${output}" "MATRIXCODE_BACKUP_OFFSITE_TARGET 不能为空"

good_env="${TMP_DIR}/good.env"
write_env "${good_env}" true
if output="$(MATRIXCODE_BACKUP_OFFSITE_TARGET=/tmp MATRIXCODE_BACKUP_OFFSITE_DRY_RUN=true "${ROOT_DIR}/scripts/sync-production-mysql-backups.sh" "${good_env}" 2>&1)"; then
  echo "断言失败：异地同步应拒绝危险目标目录" >&2
  echo "${output}" >&2
  exit 1
fi
assert_contains "${output}" "目标目录不安全"

output="$(MATRIXCODE_BACKUP_OFFSITE_DRY_RUN=true "${ROOT_DIR}/scripts/sync-production-mysql-backups.sh" "${good_env}" 2>&1)"
assert_contains "${output}" "MySQL 备份异地同步干运行通过"
assert_contains "${output}" "matrix_code-20260101010101.sql.gz"
assert_file_missing "${TMP_DIR}/offsite"

if output="$("${ROOT_DIR}/scripts/sync-production-mysql-backups.sh" "${good_env}" 2>&1)"; then
  echo "断言失败：真实异地同步应要求显式确认" >&2
  echo "${output}" >&2
  exit 1
fi
assert_contains "${output}" "MATRIXCODE_BACKUP_OFFSITE_CONFIRM 必须为 true"

output="$(MATRIXCODE_BACKUP_OFFSITE_CONFIRM=true "${ROOT_DIR}/scripts/sync-production-mysql-backups.sh" "${good_env}" 2>&1)"
assert_contains "${output}" "MySQL 备份异地同步完成"
assert_file_exists "${TMP_DIR}/offsite/matrix_code-20260101010101.sql.gz"
assert_file_exists "${TMP_DIR}/offsite/matrix_code-20260101010101.sql.gz.sha256"
assert_file_missing "${TMP_DIR}/offsite/other-20260101010101.sql.gz"

output="$(MATRIXCODE_BACKUP_OFFSITE_DRY_RUN=true MATRIXCODE_BACKUP_OFFSITE_TARGET="backup@example.com:/srv/matrixcode/mysql" "${ROOT_DIR}/scripts/sync-production-mysql-backups.sh" "${good_env}" 2>&1)"
assert_contains "${output}" "backup@example.com:/srv/matrixcode/mysql"
assert_contains "${output}" "MySQL 备份异地同步干运行通过"

echo "MySQL 备份异地同步脚本测试通过。"
