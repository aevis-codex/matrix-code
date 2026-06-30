#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
ARCHIVE_FILE="${1:-}"
TARGET_DIR="${2:-/opt/matrixcode}"

is_true() {
  [[ "${1:-}" == "true" || "${1:-}" == "TRUE" || "${1:-}" == "1" ]]
}

fail() {
  echo "生产发布安装错误：$1" >&2
  exit 1
}

resolve_parent_target() {
  local target="$1"
  [[ "${target}" == "/" ]] && {
    printf '/\n'
    return 0
  }
  local parent
  parent="$(dirname "${target}")"
  [[ -d "${parent}" ]] || fail "目标父目录不存在：${parent}"
  (cd "${parent}" && printf '%s/%s\n' "$(pwd -P)" "$(basename "${target}")")
}

reject_dangerous_target() {
  local target="$1"
  case "${target}" in
    /|/tmp|/var|/var/backups|/opt|"${ROOT_DIR}")
      fail "安装目标目录过于危险：${target}"
      ;;
  esac
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

assert_extracted_release() {
  local dir="$1"
  [[ -f "${dir}/matrixcode-server.jar" ]] || fail "归档缺少 matrixcode-server.jar"
  [[ -f "${dir}/bin/run-matrixcode-server.sh" ]] || fail "归档缺少启动脚本"
  [[ -f "${dir}/ops/env/matrixcode.env.example" ]] || fail "归档缺少生产 env 示例"
}

[[ -n "${ARCHIVE_FILE}" ]] || fail "缺少发布归档路径"
[[ -f "${ARCHIVE_FILE}" ]] || fail "发布归档不存在：${ARCHIVE_FILE}"

resolved_target="$(resolve_parent_target "${TARGET_DIR}")"
reject_dangerous_target "${resolved_target}"
verify_checksum "${ARCHIVE_FILE}"

if is_true "${MATRIXCODE_INSTALL_DRY_RUN:-false}"; then
  echo "生产发布安装 dry-run 通过：${ARCHIVE_FILE} -> ${resolved_target}"
  exit 0
fi

is_true "${MATRIXCODE_INSTALL_CONFIRM:-false}" || fail "真实安装必须设置 MATRIXCODE_INSTALL_CONFIRM=true"

tmp_dir="$(mktemp -d)"
trap 'rm -rf "${tmp_dir}"' EXIT
tar -xzf "${ARCHIVE_FILE}" -C "${tmp_dir}"
assert_extracted_release "${tmp_dir}"

timestamp="$(date +%Y%m%d%H%M%S)"
if [[ -e "${resolved_target}" ]]; then
  backup_dir="${resolved_target}.previous.${timestamp}"
  [[ ! -e "${backup_dir}" ]] || fail "备份目录已存在：${backup_dir}"
  mv "${resolved_target}" "${backup_dir}"
  echo "已备份旧版本：${backup_dir}"
fi

mkdir -p "${resolved_target}"
cp -R "${tmp_dir}/." "${resolved_target}/"

echo "生产发布安装完成：${resolved_target}"
