#!/usr/bin/env bash
set -euo pipefail

ARCHIVE_FILE="${1:-}"
ENV_FILE="${2:-${ENV_FILE:-}}"

fail() {
  echo "生产发布包 smoke 错误：$1" >&2
  exit 1
}

absolute_file() {
  local path="$1"
  local dir
  dir="$(dirname "${path}")"
  [[ -d "${dir}" ]] || fail "路径父目录不存在：${dir}"
  printf '%s/%s\n' "$(cd "${dir}" && pwd -P)" "$(basename "${path}")"
}

verify_checksum() {
  local archive="$1"
  local checksum="${archive}.sha256"
  [[ -f "${checksum}" ]] || fail "缺少校验文件 ${checksum}"
  if command -v sha256sum >/dev/null 2>&1; then
    sha256sum -c "${checksum}" >/dev/null
  elif command -v shasum >/dev/null 2>&1; then
    shasum -a 256 -c "${checksum}" >/dev/null
  else
    fail "未找到 sha256sum 或 shasum"
  fi
}

assert_release_layout() {
  local dir="$1"
  [[ -f "${dir}/matrixcode-server.jar" ]] || fail "归档缺少 matrixcode-server.jar"
  [[ -x "${dir}/bin/run-matrixcode-server.sh" ]] || fail "归档缺少可执行启动脚本"
  [[ -x "${dir}/scripts/check-real-runtime.sh" ]] || fail "归档缺少可执行真实运行检查脚本"
}

[[ -n "${ARCHIVE_FILE}" ]] || fail "缺少发布归档路径"
[[ -n "${ENV_FILE}" ]] || fail "缺少生产 env 文件路径"
[[ -f "${ARCHIVE_FILE}" ]] || fail "发布归档不存在：${ARCHIVE_FILE}"
[[ -f "${ENV_FILE}" ]] || fail "生产 env 文件不存在：${ENV_FILE}"

archive_file="$(absolute_file "${ARCHIVE_FILE}")"
env_file="$(absolute_file "${ENV_FILE}")"
verify_checksum "${archive_file}"

tmp_dir="$(mktemp -d)"
trap 'rm -rf "${tmp_dir}"' EXIT

tar -xzf "${archive_file}" -C "${tmp_dir}"
assert_release_layout "${tmp_dir}"

smoke_env="${tmp_dir}/matrixcode-smoke.env"
cp "${env_file}" "${smoke_env}"
printf '\nMATRIXCODE_PRODUCTION_DRY_RUN=true\n' >>"${smoke_env}"

MATRIXCODE_APP_HOME="${tmp_dir}" \
MATRIXCODE_ENV_FILE="${smoke_env}" \
  "${tmp_dir}/bin/run-matrixcode-server.sh"

echo "生产发布包 smoke 通过：${archive_file}"
