#!/usr/bin/env bash
set -euo pipefail

APP_HOME="${MATRIXCODE_APP_HOME:-/opt/matrixcode}"
ENV_FILE="${MATRIXCODE_ENV_FILE:-/etc/matrixcode/matrixcode.env}"
SERVER_JAR="${MATRIXCODE_SERVER_JAR:-${APP_HOME}/matrixcode-server.jar}"
JAVA_BIN="${JAVA_BIN:-java}"

is_true() {
  [[ "${1:-}" == "true" || "${1:-}" == "TRUE" || "${1:-}" == "1" ]]
}

if [[ ! -f "${ENV_FILE}" ]]; then
  echo "缺少生产环境文件：${ENV_FILE}" >&2
  exit 1
fi

if [[ ! -f "${SERVER_JAR}" ]]; then
  echo "缺少服务端 Jar：${SERVER_JAR}" >&2
  exit 1
fi

set -a
source "${ENV_FILE}"
set +a

if is_true "${MATRIXCODE_RUN_PREFLIGHT:-true}"; then
  PREFLIGHT_SCRIPT="${APP_HOME}/scripts/check-real-runtime.sh"
  if [[ ! -x "${PREFLIGHT_SCRIPT}" ]]; then
    echo "缺少可执行预检脚本：${PREFLIGHT_SCRIPT}" >&2
    exit 1
  fi
  "${PREFLIGHT_SCRIPT}" "${ENV_FILE}"
fi

if is_true "${MATRIXCODE_PRODUCTION_DRY_RUN:-false}"; then
  echo "MatrixCode 生产 Jar 启动干运行通过。"
  exit 0
fi

exec "${JAVA_BIN}" ${MATRIXCODE_JAVA_OPTS:-} -jar "${SERVER_JAR}"
