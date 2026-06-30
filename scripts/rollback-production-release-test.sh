#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
TMP_DIR="$(mktemp -d)"
trap 'rm -rf "${TMP_DIR}"' EXIT

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

create_release_dir() {
  local dir="$1"
  local marker="$2"
  mkdir -p "${dir}/bin" "${dir}/ops/env"
  printf '%s\n' "${marker}" >"${dir}/matrixcode-server.jar"
  printf '#!/usr/bin/env bash\n' >"${dir}/bin/run-matrixcode-server.sh"
  printf 'MATRIXCODE_APP_HOME=/opt/matrixcode\n' >"${dir}/ops/env/matrixcode.env.example"
}

previous_dir="${TMP_DIR}/matrixcode.previous.20260629170000"
target_dir="${TMP_DIR}/matrixcode"
create_release_dir "${previous_dir}" "previous-release"
create_release_dir "${target_dir}" "current-release"

if output="$(MATRIXCODE_ROLLBACK_DRY_RUN=true "${ROOT_DIR}/scripts/rollback-production-release.sh" "${previous_dir}" "/" 2>&1)"; then
  echo "断言失败：回滚脚本应拒绝危险目标目录" >&2
  echo "${output}" >&2
  exit 1
fi
assert_contains "${output}" "回滚目标目录过于危险"

output="$(MATRIXCODE_ROLLBACK_DRY_RUN=true "${ROOT_DIR}/scripts/rollback-production-release.sh" "${previous_dir}" "${target_dir}" 2>&1)"
assert_contains "${output}" "生产发布回滚 dry-run 通过"
assert_contains "${output}" "${previous_dir}"
assert_contains "$(cat "${target_dir}/matrixcode-server.jar")" "current-release"

if output="$("${ROOT_DIR}/scripts/rollback-production-release.sh" "${previous_dir}" "${target_dir}" 2>&1)"; then
  echo "断言失败：真实回滚必须显式确认" >&2
  echo "${output}" >&2
  exit 1
fi
assert_contains "${output}" "MATRIXCODE_ROLLBACK_CONFIRM=true"

audit_log="${TMP_DIR}/rollback-audit.jsonl"
output="$(MATRIXCODE_ROLLBACK_AUDIT_LOG="${audit_log}" MATRIXCODE_ROLLBACK_CONFIRM=true "${ROOT_DIR}/scripts/rollback-production-release.sh" "${previous_dir}" "${target_dir}" 2>&1)"
assert_contains "${output}" "生产发布回滚完成"
assert_contains "$(cat "${target_dir}/matrixcode-server.jar")" "previous-release"
assert_file_exists "${target_dir}/bin/run-matrixcode-server.sh"
assert_file_exists "${audit_log}"
audit_record="$(cat "${audit_log}")"
assert_contains "${audit_record}" '"action":"rollback"'
assert_contains "${audit_record}" '"status":"SUCCEEDED"'
assert_contains "${audit_record}" "\"previousDir\":\"${previous_dir}\""
assert_contains "${audit_record}" "\"targetDir\":\"${target_dir}\""

failed_count="$(find "${TMP_DIR}" -maxdepth 1 -type d -name 'matrixcode.failed.*' | wc -l | tr -d ' ')"
[[ "${failed_count}" != "0" ]] || {
  echo "断言失败：真实回滚应备份当前目录" >&2
  exit 1
}

echo "生产发布回滚脚本测试通过。"
