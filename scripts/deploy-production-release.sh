#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
ARCHIVE_FILE="${1:-}"
REMOTE_TARGET="${MATRIXCODE_REMOTE_RELEASE_TARGET:-}"
REMOTE_RELEASE_DIR="${MATRIXCODE_REMOTE_RELEASE_DIR:-/tmp/matrixcode-releases}"
REMOTE_APP_DIR="${MATRIXCODE_REMOTE_APP_DIR:-/opt/matrixcode}"
SYSTEMD_SERVICE="${MATRIXCODE_REMOTE_SYSTEMD_SERVICE:-${MATRIXCODE_SYSTEMD_SERVICE:-matrixcode}}"
NGINX_SERVICE="${MATRIXCODE_REMOTE_NGINX_SERVICE:-${MATRIXCODE_NGINX_SERVICE:-nginx}}"
HEALTH_PROBE_URL="${MATRIXCODE_REMOTE_HEALTH_PROBE_URL:-${MATRIXCODE_HEALTH_PROBE_URL:-http://127.0.0.1:8080/actuator/health}}"
INFO_PROBE_URL="${MATRIXCODE_REMOTE_INFO_PROBE_URL:-${MATRIXCODE_INFO_PROBE_URL:-${HEALTH_PROBE_URL%/health}/info}}"
RELOAD_NGINX="${MATRIXCODE_REMOTE_RELOAD_NGINX:-${MATRIXCODE_RELOAD_NGINX:-false}}"
CAPTURE_HEALTH_SNAPSHOT="${MATRIXCODE_DEPLOY_CAPTURE_HEALTH_SNAPSHOT:-true}"
REMOTE_ENV_FILE="${MATRIXCODE_REMOTE_ENV_FILE:-/etc/matrixcode/matrixcode.env}"
REMOTE_HEALTH_SNAPSHOT_DIR="${MATRIXCODE_REMOTE_HEALTH_SNAPSHOT_DIR:-/var/log/matrixcode/health-snapshots}"

## 判断配置开关是否显式开启，兼容 true、TRUE 和 1 三种写法。
is_true() {
  [[ "${1:-}" == "true" || "${1:-}" == "TRUE" || "${1:-}" == "1" ]]
}

## 输出统一错误前缀并终止脚本，避免配置不完整时触发远程发布动作。
fail() {
  echo "生产发布编排错误：$1" >&2
  exit 1
}

## 校验发布归档旁边的 sha256 文件，保证编排入口和上传入口使用同一归档可信边界。
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

## 校验远端 SSH 目标，拒绝路径、空格和 shell 分隔符，避免把目标字符串变成命令片段。
validate_remote_target() {
  local remote_target="$1"
  [[ -n "${remote_target}" ]] || fail "MATRIXCODE_REMOTE_RELEASE_TARGET 不能为空"
  [[ "${remote_target}" != *"/"* ]] || fail "远端目标不能包含路径：${remote_target}"
  [[ "${remote_target}" != *" "* ]] || fail "远端目标不能包含空格：${remote_target}"
  [[ "${remote_target}" != *";"* && "${remote_target}" != *"|"* && "${remote_target}" != *"&"* ]] || fail "远端目标包含不安全字符：${remote_target}"
}

## 校验远端目录为普通绝对路径，拒绝系统根目录和 MatrixCode 应用目录本身作为暂存目录。
validate_remote_release_dir() {
  local remote_dir="$1"
  [[ "${remote_dir}" == /* ]] || fail "远端发布目录必须是绝对路径：${remote_dir}"
  case "${remote_dir%/}" in
    ""|"/"|"/tmp"|"/var"|"/var/backups"|"/opt"|"/opt/matrixcode")
      fail "远端发布目录不安全：${remote_dir}"
      ;;
  esac
  [[ "${remote_dir}" =~ ^/[A-Za-z0-9._/@=-]+$ ]] || fail "远端发布目录包含不安全字符：${remote_dir}"
}

## 校验远端应用目录，真实发布会覆盖该目录，因此必须拒绝根目录和系统级目录。
validate_remote_app_dir() {
  local app_dir="$1"
  [[ "${app_dir}" == /* ]] || fail "应用目录必须是绝对路径：${app_dir}"
  case "${app_dir%/}" in
    ""|"/"|"/tmp"|"/var"|"/var/backups"|"/opt")
      fail "应用目录不安全：${app_dir}"
      ;;
  esac
  [[ "${app_dir}" =~ ^/[A-Za-z0-9._/@=-]+$ ]] || fail "应用目录包含不安全字符：${app_dir}"
}

## 校验远端环境文件路径，健康快照会读取该文件，因此必须是明确的普通绝对路径。
validate_remote_env_file() {
  local env_file="$1"
  [[ "${env_file}" == /* ]] || fail "远端环境文件必须是绝对路径：${env_file}"
  [[ "${env_file}" != */ ]] || fail "远端环境文件不能是目录：${env_file}"
  case "${env_file}" in
    ""|"/"|"/tmp"|"/var"|"/var/log"|"/opt"|"/etc")
      fail "远端环境文件路径不安全：${env_file}"
      ;;
  esac
  [[ "${env_file}" =~ ^/[A-Za-z0-9._/@=-]+$ ]] || fail "远端环境文件包含不安全字符：${env_file}"
}

## 校验远端健康快照目录，拒绝根目录和系统级目录，避免把快照写入危险位置。
validate_remote_health_snapshot_dir() {
  local snapshot_dir="$1"
  [[ "${snapshot_dir}" == /* ]] || fail "远端健康快照目录必须是绝对路径：${snapshot_dir}"
  case "${snapshot_dir%/}" in
    ""|"/"|"/tmp"|"/var"|"/var/log"|"/opt"|"/etc")
      fail "远端健康快照目录不安全：${snapshot_dir}"
      ;;
  esac
  [[ "${snapshot_dir}" =~ ^/[A-Za-z0-9._/@=-]+$ ]] || fail "远端健康快照目录包含不安全字符：${snapshot_dir}"
}

## 校验 systemd 服务名，避免远端重启命令中混入路径或 shell 分隔符。
validate_service_name() {
  local name="$1"
  [[ -n "${name}" ]] || fail "服务名不能为空"
  [[ "${name}" =~ ^[A-Za-z0-9_.@-]+$ ]] || fail "服务名包含不安全字符：${name}"
}

## 校验健康检查 URL，只允许 HTTP/HTTPS 且不允许空格。
validate_health_url() {
  local url="$1"
  [[ "${url}" == http://* || "${url}" == https://* ]] || fail "健康检查地址必须以 http:// 或 https:// 开头"
  [[ "${url}" != *" "* ]] || fail "健康检查地址不能包含空格"
}

## 对远端命令参数做 POSIX 单引号转义，避免健康检查 URL 等配置被解释为 shell 片段。
shell_quote() {
  local escaped="${1//\'/\'\"\'\"\'}"
  printf "'%s'" "${escaped}"
}

[[ -n "${ARCHIVE_FILE}" ]] || fail "缺少发布归档路径"
[[ -f "${ARCHIVE_FILE}" ]] || fail "发布归档不存在：${ARCHIVE_FILE}"
verify_checksum "${ARCHIVE_FILE}"
validate_remote_target "${REMOTE_TARGET}"
validate_remote_release_dir "${REMOTE_RELEASE_DIR}"
validate_remote_app_dir "${REMOTE_APP_DIR}"
validate_service_name "${SYSTEMD_SERVICE}"
if is_true "${RELOAD_NGINX}"; then
  validate_service_name "${NGINX_SERVICE}"
fi
validate_health_url "${HEALTH_PROBE_URL}"
if is_true "${CAPTURE_HEALTH_SNAPSHOT}"; then
  validate_health_url "${INFO_PROBE_URL}"
  validate_remote_env_file "${REMOTE_ENV_FILE}"
  validate_remote_health_snapshot_dir "${REMOTE_HEALTH_SNAPSHOT_DIR}"
fi

remote_archive="${REMOTE_RELEASE_DIR%/}/$(basename "${ARCHIVE_FILE}")"
remote_snapshot_prefix="${REMOTE_HEALTH_SNAPSHOT_DIR%/}/matrixcode-health-"

if [[ -n "${MATRIXCODE_DEPLOY_SMOKE_ENV_FILE:-}" ]]; then
  bash "${ROOT_DIR}/scripts/smoke-production-release.sh" "${ARCHIVE_FILE}" "${MATRIXCODE_DEPLOY_SMOKE_ENV_FILE}"
fi

if is_true "${MATRIXCODE_DEPLOY_DRY_RUN:-false}"; then
  MATRIXCODE_REMOTE_RELEASE_DRY_RUN=true \
  MATRIXCODE_REMOTE_RELEASE_TARGET="${REMOTE_TARGET}" \
  MATRIXCODE_REMOTE_RELEASE_DIR="${REMOTE_RELEASE_DIR}" \
    bash "${ROOT_DIR}/scripts/upload-production-release.sh" "${ARCHIVE_FILE}"
  echo "远程安装 dry-run：$(shell_quote "${remote_archive}") -> $(shell_quote "${REMOTE_APP_DIR}")"
  echo "远程重启 dry-run：service $(shell_quote "${SYSTEMD_SERVICE}")，health $(shell_quote "${HEALTH_PROBE_URL}")"
  if is_true "${CAPTURE_HEALTH_SNAPSHOT}"; then
    echo "远程健康快照 dry-run：env $(shell_quote "${REMOTE_ENV_FILE}") -> $(shell_quote "${remote_snapshot_prefix}<utc>.md")，info $(shell_quote "${INFO_PROBE_URL}")"
  fi
  exit 0
fi

is_true "${MATRIXCODE_DEPLOY_CONFIRM:-false}" || fail "真实远程发布编排必须设置 MATRIXCODE_DEPLOY_CONFIRM=true"

command -v ssh >/dev/null 2>&1 || fail "未找到 ssh"

MATRIXCODE_REMOTE_RELEASE_CONFIRM=true \
MATRIXCODE_REMOTE_RELEASE_TARGET="${REMOTE_TARGET}" \
MATRIXCODE_REMOTE_RELEASE_DIR="${REMOTE_RELEASE_DIR}" \
  bash "${ROOT_DIR}/scripts/upload-production-release.sh" "${ARCHIVE_FILE}"

remote_tmp_prefix="/tmp/matrixcode-install.XXXXXX"
install_command="remote_tmp=\$(mktemp -d ${remote_tmp_prefix}) && tar -xzf $(shell_quote "${remote_archive}") -C \"\$remote_tmp\" && MATRIXCODE_INSTALL_CONFIRM=true \"\$remote_tmp/scripts/install-production-release.sh\" $(shell_quote "${remote_archive}") $(shell_quote "${REMOTE_APP_DIR}") && rm -rf \"\$remote_tmp\""
ssh "${REMOTE_TARGET}" "${install_command}"

restart_command="MATRIXCODE_RESTART_CONFIRM=true MATRIXCODE_SYSTEMD_SERVICE=$(shell_quote "${SYSTEMD_SERVICE}") MATRIXCODE_HEALTH_PROBE_URL=$(shell_quote "${HEALTH_PROBE_URL}") MATRIXCODE_RELOAD_NGINX=$(shell_quote "${RELOAD_NGINX}") MATRIXCODE_NGINX_SERVICE=$(shell_quote "${NGINX_SERVICE}") $(shell_quote "${REMOTE_APP_DIR%/}/scripts/restart-production-services.sh")"
ssh "${REMOTE_TARGET}" "${restart_command}"

if is_true "${CAPTURE_HEALTH_SNAPSHOT}"; then
  snapshot_command="mkdir -p $(shell_quote "${REMOTE_HEALTH_SNAPSHOT_DIR}") && snapshot_file=$(shell_quote "${remote_snapshot_prefix}")\$(date -u +%Y%m%d%H%M%S).md && MATRIXCODE_HEALTH_PROBE_URL_OVERRIDE=$(shell_quote "${HEALTH_PROBE_URL}") MATRIXCODE_INFO_PROBE_URL_OVERRIDE=$(shell_quote "${INFO_PROBE_URL}") $(shell_quote "${REMOTE_APP_DIR%/}/scripts/capture-production-health-snapshot.sh") $(shell_quote "${REMOTE_ENV_FILE}") \"\$snapshot_file\""
  ssh "${REMOTE_TARGET}" "${snapshot_command}"
  echo "远程健康快照完成：${REMOTE_TARGET}:${REMOTE_HEALTH_SNAPSHOT_DIR%/}"
fi

echo "生产发布编排完成：${REMOTE_TARGET}:${REMOTE_APP_DIR%/}"
