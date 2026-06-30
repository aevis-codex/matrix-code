#!/usr/bin/env bash
set -euo pipefail

ARCHIVE_FILE="${1:-}"
REMOTE_TARGET="${MATRIXCODE_REMOTE_RELEASE_TARGET:-}"
REMOTE_DIR="${MATRIXCODE_REMOTE_RELEASE_DIR:-/tmp/matrixcode-releases}"

## 判断配置开关是否显式开启，兼容 true、TRUE 和 1 三种写法。
is_true() {
  [[ "${1:-}" == "true" || "${1:-}" == "TRUE" || "${1:-}" == "1" ]]
}

## 输出统一错误前缀并终止脚本，避免在配置不完整时触发真实远程复制。
fail() {
  echo "生产发布远程复制错误：$1" >&2
  exit 1
}

## 校验发布归档旁边的 sha256 文件，避免损坏产物被上传到服务器。
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

## 限制远端目录为可预期的绝对路径，并拒绝系统级高风险目录。
reject_unsafe_remote_dir() {
  local remote_dir="$1"
  [[ "${remote_dir}" == /* ]] || fail "远端目录必须是绝对路径：${remote_dir}"

  case "${remote_dir%/}" in
    ""|"/"|"/tmp"|"/var"|"/var/backups"|"/opt"|"/opt/matrixcode")
      fail "远端目录不安全：${remote_dir}"
      ;;
  esac

  [[ "${remote_dir}" =~ ^/[A-Za-z0-9._/@=-]+$ ]] || fail "远端目录包含不安全字符：${remote_dir}"
}

## 校验 SSH 目标只作为 scp/ssh 目标使用，不允许混入路径或 shell 分隔符。
validate_remote_target() {
  local remote_target="$1"
  [[ -n "${remote_target}" ]] || fail "MATRIXCODE_REMOTE_RELEASE_TARGET 不能为空"
  [[ "${remote_target}" != *"/"* ]] || fail "远端目标不能包含路径：${remote_target}"
  [[ "${remote_target}" != *" "* ]] || fail "远端目标不能包含空格：${remote_target}"
  [[ "${remote_target}" != *";"* && "${remote_target}" != *"|"* && "${remote_target}" != *"&"* ]] || fail "远端目标包含不安全字符：${remote_target}"
}

[[ -n "${ARCHIVE_FILE}" ]] || fail "缺少发布归档路径"
[[ -f "${ARCHIVE_FILE}" ]] || fail "发布归档不存在：${ARCHIVE_FILE}"
verify_checksum "${ARCHIVE_FILE}"
validate_remote_target "${REMOTE_TARGET}"
reject_unsafe_remote_dir "${REMOTE_DIR}"

manifest_file="${ARCHIVE_FILE%.tar.gz}.manifest"
upload_files=("${ARCHIVE_FILE}" "${ARCHIVE_FILE}.sha256")
if [[ -f "${manifest_file}" ]]; then
  upload_files+=("${manifest_file}")
fi

if is_true "${MATRIXCODE_REMOTE_RELEASE_DRY_RUN:-false}"; then
  echo "生产发布远程复制 dry-run 通过：${ARCHIVE_FILE} -> ${REMOTE_TARGET}:${REMOTE_DIR%/}/"
  exit 0
fi

is_true "${MATRIXCODE_REMOTE_RELEASE_CONFIRM:-false}" || fail "真实远程复制必须设置 MATRIXCODE_REMOTE_RELEASE_CONFIRM=true"

command -v ssh >/dev/null 2>&1 || fail "未找到 ssh"
command -v scp >/dev/null 2>&1 || fail "未找到 scp"

ssh "${REMOTE_TARGET}" "mkdir -p '${REMOTE_DIR%/}' && chmod 700 '${REMOTE_DIR%/}'"
scp "${upload_files[@]}" "${REMOTE_TARGET}:${REMOTE_DIR%/}/"

echo "生产发布远程复制完成：${REMOTE_TARGET}:${REMOTE_DIR%/}/"
