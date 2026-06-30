#!/usr/bin/env bash
set -euo pipefail

SYSTEMD_SERVICE="${MATRIXCODE_SYSTEMD_SERVICE:-matrixcode}"
NGINX_SERVICE="${MATRIXCODE_NGINX_SERVICE:-nginx}"
HEALTH_PROBE_URL="${MATRIXCODE_HEALTH_PROBE_URL:-http://127.0.0.1:8080/actuator/health}"
HEALTH_PROBE_TIMEOUT_SECONDS="${MATRIXCODE_HEALTH_PROBE_TIMEOUT_SECONDS:-5}"

## 判断配置开关是否显式开启，兼容 true、TRUE 和 1 三种写法。
is_true() {
  [[ "${1:-}" == "true" || "${1:-}" == "TRUE" || "${1:-}" == "1" ]]
}

## 输出统一错误前缀并终止脚本，避免在确认缺失时触发真实重启。
fail() {
  echo "生产服务重启错误：$1" >&2
  exit 1
}

## 校验 systemd 服务名，避免服务名中混入 shell 分隔符或路径。
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

## 校验 curl 超时时间为正整数，避免错误配置导致健康检查阻塞过久。
validate_timeout() {
  local timeout="$1"
  [[ "${timeout}" =~ ^[1-9][0-9]*$ ]] || fail "健康检查超时时间必须为正整数：${timeout}"
}

validate_service_name "${SYSTEMD_SERVICE}"
if is_true "${MATRIXCODE_RELOAD_NGINX:-false}"; then
  validate_service_name "${NGINX_SERVICE}"
fi
validate_health_url "${HEALTH_PROBE_URL}"
validate_timeout "${HEALTH_PROBE_TIMEOUT_SECONDS}"

if is_true "${MATRIXCODE_RESTART_DRY_RUN:-false}"; then
  echo "生产服务重启 dry-run 通过：restart ${SYSTEMD_SERVICE}，health ${HEALTH_PROBE_URL}。"
  if is_true "${MATRIXCODE_RELOAD_NGINX:-false}"; then
    echo "生产服务重启 dry-run 将 reload ${NGINX_SERVICE}。"
  fi
  exit 0
fi

is_true "${MATRIXCODE_RESTART_CONFIRM:-false}" || fail "真实重启必须设置 MATRIXCODE_RESTART_CONFIRM=true"

command -v systemctl >/dev/null 2>&1 || fail "未找到 systemctl"
command -v curl >/dev/null 2>&1 || fail "未找到 curl"

systemctl restart "${SYSTEMD_SERVICE}"
curl -fsS --max-time "${HEALTH_PROBE_TIMEOUT_SECONDS}" "${HEALTH_PROBE_URL}" >/dev/null

if is_true "${MATRIXCODE_RELOAD_NGINX:-false}"; then
  systemctl reload "${NGINX_SERVICE}"
fi

echo "生产服务重启完成：${SYSTEMD_SERVICE}"
