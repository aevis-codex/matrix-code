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

release_root="${TMP_DIR}/release-root"
mkdir -p "${release_root}/bin" "${release_root}/ops/env"
touch "${release_root}/matrixcode-server.jar"
touch "${release_root}/bin/run-matrixcode-server.sh"
touch "${release_root}/ops/env/matrixcode.env.example"

archive="${TMP_DIR}/matrixcode-test.tar.gz"
tar -czf "${archive}" -C "${release_root}" .
if command -v sha256sum >/dev/null 2>&1; then
  sha256sum "${archive}" >"${archive}.sha256"
else
  shasum -a 256 "${archive}" >"${archive}.sha256"
fi

if output="$(MATRIXCODE_INSTALL_DRY_RUN=true "${ROOT_DIR}/scripts/install-production-release.sh" "${archive}" "/" 2>&1)"; then
  echo "断言失败：安装脚本应拒绝危险目标目录" >&2
  echo "${output}" >&2
  exit 1
fi
assert_contains "${output}" "安装目标目录过于危险"

target="${TMP_DIR}/matrixcode"
output="$(MATRIXCODE_INSTALL_DRY_RUN=true "${ROOT_DIR}/scripts/install-production-release.sh" "${archive}" "${target}" 2>&1)"
assert_contains "${output}" "生产发布安装 dry-run 通过"
[[ ! -e "${target}" ]] || {
  echo "断言失败：dry-run 不应创建目标目录" >&2
  exit 1
}

if output="$("${ROOT_DIR}/scripts/install-production-release.sh" "${archive}" "${target}" 2>&1)"; then
  echo "断言失败：真实安装必须显式确认" >&2
  echo "${output}" >&2
  exit 1
fi
assert_contains "${output}" "MATRIXCODE_INSTALL_CONFIRM=true"

output="$(MATRIXCODE_INSTALL_CONFIRM=true "${ROOT_DIR}/scripts/install-production-release.sh" "${archive}" "${target}" 2>&1)"
assert_contains "${output}" "生产发布安装完成"
assert_file_exists "${target}/matrixcode-server.jar"
assert_file_exists "${target}/bin/run-matrixcode-server.sh"

output="$(MATRIXCODE_INSTALL_CONFIRM=true "${ROOT_DIR}/scripts/install-production-release.sh" "${archive}" "${target}" 2>&1)"
assert_contains "${output}" "已备份旧版本"
assert_file_exists "${target}/matrixcode-server.jar"
backup_count="$(find "${TMP_DIR}" -maxdepth 1 -type d -name 'matrixcode.previous.*' | wc -l | tr -d ' ')"
[[ "${backup_count}" != "0" ]] || {
  echo "断言失败：重复安装应备份旧版本" >&2
  exit 1
}

echo "生产发布安装脚本测试通过。"
