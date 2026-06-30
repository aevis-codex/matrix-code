#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
TMP_DIR="$(mktemp -d)"
trap 'rm -rf "${TMP_DIR}"' EXIT

write_env() {
  local env_file="$1"
  local production_check="$2"
  local db_credential="$3"
  cat >"${env_file}" <<EOF_ENV
MATRIXCODE_PRODUCTION_CHECK=${production_check}
MATRIXCODE_MYSQL_HOST=127.0.0.1
MATRIXCODE_MYSQL_PORT=3306
MATRIXCODE_MYSQL_DATABASE=matrix_code
MATRIXCODE_PERSISTENCE_JDBC_USERNAME=matrixcode
MATRIXCODE_PERSISTENCE_JDBC_PASSWORD=${db_credential}
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

bad_gate_env="${TMP_DIR}/bad-gate.env"
write_env "${bad_gate_env}" false "local-test-credential-123456"
if output="$(MATRIXCODE_BACKUP_DRY_RUN=true "${ROOT_DIR}/scripts/backup-production-mysql.sh" "${bad_gate_env}" "${TMP_DIR}/backup" 2>&1)"; then
  echo "断言失败：备份脚本应拒绝未开启生产门禁的配置" >&2
  echo "${output}" >&2
  exit 1
fi
assert_contains "${output}" "MATRIXCODE_PRODUCTION_CHECK 必须为 true"

placeholder_env="${TMP_DIR}/placeholder.env"
write_env "${placeholder_env}" true "replace_with_database_credential"
if output="$(MATRIXCODE_BACKUP_DRY_RUN=true "${ROOT_DIR}/scripts/backup-production-mysql.sh" "${placeholder_env}" "${TMP_DIR}/backup" 2>&1)"; then
  echo "断言失败：备份脚本应拒绝占位数据库凭据" >&2
  echo "${output}" >&2
  exit 1
fi
assert_contains "${output}" "MATRIXCODE_PERSISTENCE_JDBC_PASSWORD 不能使用占位值"

good_env="${TMP_DIR}/good.env"
write_env "${good_env}" true "local-test-credential-123456"
output="$(MATRIXCODE_BACKUP_DRY_RUN=true "${ROOT_DIR}/scripts/backup-production-mysql.sh" "${good_env}" "${TMP_DIR}/backup" 2>&1)"
assert_contains "${output}" "MySQL 备份干运行通过"
assert_contains "${output}" "127.0.0.1:3306/matrix_code"

echo "MySQL 生产备份脚本测试通过。"
