#!/usr/bin/env bash
set -euo pipefail

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
CONNECT_TIMEOUT_SECONDS="${MATRIXCODE_REMOTE_RELEASE_PREFLIGHT_TIMEOUT_SECONDS:-5}"

## 判断配置开关是否显式开启，兼容 true、TRUE 和 1 三种写法。
is_true() {
  [[ "${1:-}" == "true" || "${1:-}" == "TRUE" || "${1:-}" == "1" ]]
}

## 输出统一错误前缀并终止脚本，便于候选报告和 CI 快速定位失败原因。
fail() {
  echo "远程发布目标预检错误：$1" >&2
  exit 1
}

## 校验 SSH 目标只作为 ssh/scp 目标使用，不允许混入路径、空格或 shell 分隔符。
validate_remote_target() {
  local remote_target="$1"
  [[ -n "${remote_target}" ]] || fail "MATRIXCODE_REMOTE_RELEASE_TARGET 不能为空"
  [[ "${remote_target}" != *"/"* ]] || fail "远端目标不能包含路径：${remote_target}"
  [[ "${remote_target}" != *" "* ]] || fail "远端目标不能包含空格：${remote_target}"
  [[ "${remote_target}" != *";"* && "${remote_target}" != *"|"* && "${remote_target}" != *"&"* ]] || fail "远端目标包含不安全字符：${remote_target}"
}

## 校验远端发布暂存目录，拒绝系统根目录和 MatrixCode 应用目录本身。
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

## 校验远端应用目录，真实发布会替换该目录，因此必须拒绝根目录和系统级目录。
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

## 校验远端环境文件路径，健康快照预检会读取该文件，因此只接受普通绝对路径。
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

## 校验远端健康快照目录，避免预检或真实发布把快照写入系统根目录。
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

## 校验 systemd 服务名，避免远程命令拼接时出现路径或 shell 分隔符。
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

## 校验 SSH 连接超时时间为小整数，避免被当作 ssh 参数片段注入。
validate_timeout() {
  local timeout="$1"
  [[ "${timeout}" =~ ^[0-9]+$ ]] || fail "SSH 预检超时时间必须是正整数"
  (( timeout >= 1 && timeout <= 60 )) || fail "SSH 预检超时时间必须在 1 到 60 秒之间"
}

## 对远端命令参数做 POSIX 单引号转义，避免目录和 URL 被解释为 shell 片段。
shell_quote() {
  local escaped="${1//\'/\'\"\'\"\'}"
  printf "'%s'" "${escaped}"
}

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
validate_timeout "${CONNECT_TIMEOUT_SECONDS}"

if is_true "${MATRIXCODE_REMOTE_RELEASE_PREFLIGHT_CONNECT:-false}"; then
  command -v ssh >/dev/null 2>&1 || fail "未找到 ssh"
  remote_command="command -v tar >/dev/null && command -v mktemp >/dev/null && command -v systemctl >/dev/null && test -d $(shell_quote "$(dirname "${REMOTE_APP_DIR}")")"
  if is_true "${CAPTURE_HEALTH_SNAPSHOT}"; then
    remote_command="${remote_command} && command -v curl >/dev/null && test -x $(shell_quote "${REMOTE_APP_DIR%/}/scripts/capture-production-health-snapshot.sh") && test -r $(shell_quote "${REMOTE_ENV_FILE}") && mkdir -p $(shell_quote "${REMOTE_HEALTH_SNAPSHOT_DIR}") && test -w $(shell_quote "${REMOTE_HEALTH_SNAPSHOT_DIR}")"
  fi
  ssh -o BatchMode=yes -o ConnectTimeout="${CONNECT_TIMEOUT_SECONDS}" "${REMOTE_TARGET}" "${remote_command}"
  echo "远程 SSH 预检通过：${REMOTE_TARGET}"
else
  echo "SSH 连接检查：SKIPPED"
fi

echo "远程发布目标预检通过：${REMOTE_TARGET}"
echo "远端发布目录：${REMOTE_RELEASE_DIR%/}"
echo "远端应用目录：${REMOTE_APP_DIR%/}"
echo "远端 systemd 服务：${SYSTEMD_SERVICE}"
echo "远端健康检查：${HEALTH_PROBE_URL}"
if is_true "${CAPTURE_HEALTH_SNAPSHOT}"; then
  echo "远端 info 检查：${INFO_PROBE_URL}"
  echo "远端环境文件：${REMOTE_ENV_FILE}"
  echo "远端健康快照目录：${REMOTE_HEALTH_SNAPSHOT_DIR%/}"
else
  echo "远端健康快照预检：SKIPPED"
fi
