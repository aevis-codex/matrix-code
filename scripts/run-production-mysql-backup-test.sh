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
MATRIXCODE_MYSQL_HOST=127.0.0.1
MATRIXCODE_MYSQL_PORT=3306
MATRIXCODE_MYSQL_DATABASE=matrix_code
MATRIXCODE_PERSISTENCE_JDBC_USERNAME=matrixcode
MATRIXCODE_PERSISTENCE_JDBC_PASSWORD=local-test-credential-123456
MATRIXCODE_BACKUP_DIR=${TMP_DIR}/backups
MATRIXCODE_BACKUP_RETENTION_DAYS=7
MATRIXCODE_BACKUP_OFFSITE_ENABLED=false
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

bad_gate_env="${TMP_DIR}/bad-gate.env"
write_env "${bad_gate_env}" false
if output="$(MATRIXCODE_BACKUP_JOB_DRY_RUN=true "${ROOT_DIR}/scripts/run-production-mysql-backup.sh" "${bad_gate_env}" 2>&1)"; then
  echo "断言失败：备份任务应拒绝未开启生产门禁的配置" >&2
  echo "${output}" >&2
  exit 1
fi
assert_contains "${output}" "MATRIXCODE_PRODUCTION_CHECK 必须为 true"

good_env="${TMP_DIR}/good.env"
write_env "${good_env}" true
output="$(MATRIXCODE_BACKUP_JOB_DRY_RUN=true "${ROOT_DIR}/scripts/run-production-mysql-backup.sh" "${good_env}" 2>&1)"
assert_contains "${output}" "MySQL 备份干运行通过"
assert_contains "${output}" "MySQL 备份轮转干运行"
assert_contains "${output}" "MySQL 生产备份任务完成"
[[ ! -d "${TMP_DIR}/backups" ]] || {
  echo "断言失败：dry-run 不应创建真实备份目录" >&2
  exit 1
}

offsite_env="${TMP_DIR}/offsite.env"
write_env "${offsite_env}" true
cat >>"${offsite_env}" <<EOF_ENV
MATRIXCODE_BACKUP_OFFSITE_ENABLED=true
EOF_ENV
output="$(MATRIXCODE_BACKUP_JOB_DRY_RUN=true "${ROOT_DIR}/scripts/run-production-mysql-backup.sh" "${offsite_env}" 2>&1)"
assert_contains "${output}" "MySQL 备份干运行通过"
assert_contains "${output}" "MySQL 备份轮转干运行"
assert_contains "${output}" "MySQL 备份异地同步干运行通过"
assert_contains "${output}" "MySQL 生产备份任务完成"
[[ ! -d "${TMP_DIR}/backups" ]] || {
  echo "断言失败：启用异地同步 dry-run 时不应创建真实备份目录" >&2
  exit 1
}
[[ ! -d "${TMP_DIR}/offsite" ]] || {
  echo "断言失败：启用异地同步 dry-run 时不应创建真实异地目标目录" >&2
  exit 1
}

echo "MySQL 生产备份任务脚本测试通过。"
