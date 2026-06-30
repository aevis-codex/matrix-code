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
  [[ ! -f "${path}" ]] || {
    echo "断言失败：文件应已清理 ${path}" >&2
    exit 1
  }
}

bad_gate_env="${TMP_DIR}/bad-gate.env"
backup_dir="${TMP_DIR}/backups"
mkdir -p "${backup_dir}"
write_env "${bad_gate_env}" false
if output="$(MATRIXCODE_BACKUP_PRUNE_DRY_RUN=true "${ROOT_DIR}/scripts/prune-production-mysql-backups.sh" "${bad_gate_env}" "${backup_dir}" 7 2>&1)"; then
  echo "断言失败：轮转脚本应拒绝未开启生产门禁的配置" >&2
  echo "${output}" >&2
  exit 1
fi
assert_contains "${output}" "MATRIXCODE_PRODUCTION_CHECK 必须为 true"

good_env="${TMP_DIR}/good.env"
write_env "${good_env}" true
if output="$(MATRIXCODE_BACKUP_PRUNE_DRY_RUN=true "${ROOT_DIR}/scripts/prune-production-mysql-backups.sh" "${good_env}" "${backup_dir}" 0 2>&1)"; then
  echo "断言失败：轮转脚本应拒绝 0 天保留期" >&2
  echo "${output}" >&2
  exit 1
fi
assert_contains "${output}" "保留天数必须大于等于 1"

if output="$(MATRIXCODE_BACKUP_PRUNE_DRY_RUN=true "${ROOT_DIR}/scripts/prune-production-mysql-backups.sh" "${good_env}" "/" 7 2>&1)"; then
  echo "断言失败：轮转脚本应拒绝危险目录" >&2
  echo "${output}" >&2
  exit 1
fi
assert_contains "${output}" "备份目录过于危险"

old_backup="${backup_dir}/matrix_code-20200101000000.sql.gz"
old_checksum="${old_backup}.sha256"
recent_backup="${backup_dir}/matrix_code-29990101000000.sql.gz"
unrelated_backup="${backup_dir}/other-20200101000000.sql.gz"
touch "${old_backup}" "${old_checksum}" "${recent_backup}" "${unrelated_backup}"
touch -t 202001010000 "${old_backup}" "${old_checksum}" "${unrelated_backup}"

output="$(MATRIXCODE_BACKUP_PRUNE_DRY_RUN=true "${ROOT_DIR}/scripts/prune-production-mysql-backups.sh" "${good_env}" "${backup_dir}" 1 2>&1)"
assert_contains "${output}" "MySQL 备份轮转干运行"
assert_contains "${output}" "${old_backup}"
assert_file_exists "${old_backup}"
assert_file_exists "${old_checksum}"
assert_file_exists "${recent_backup}"
assert_file_exists "${unrelated_backup}"

output="$("${ROOT_DIR}/scripts/prune-production-mysql-backups.sh" "${good_env}" "${backup_dir}" 1 2>&1)"
assert_contains "${output}" "MySQL 备份轮转完成"
assert_file_missing "${old_backup}"
assert_file_missing "${old_checksum}"
assert_file_exists "${recent_backup}"
assert_file_exists "${unrelated_backup}"

echo "MySQL 备份轮转脚本测试通过。"
