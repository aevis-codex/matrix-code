#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
ENV_FILE="${1:-${ENV_FILE:-${ROOT_DIR}/.env.local}}"
FAILED=0

if [[ ! -f "${ENV_FILE}" ]]; then
  echo "缺少 ${ENV_FILE}，请先复制 .env.example 为 .env.local 并填入生产配置。"
  exit 1
fi

set -a
source "${ENV_FILE}"
set +a

is_true() {
  [[ "${1:-}" == "true" || "${1:-}" == "TRUE" || "${1:-}" == "1" ]]
}

mark_failed() {
  echo "生产启动配置错误：$1"
  FAILED=1
}

require_true_setting() {
  local name="$1"
  local value="${!name:-}"
  if ! is_true "${value}"; then
    mark_failed "${name} 必须为 true"
  fi
}

require_equals_setting() {
  local name="$1"
  local expected="$2"
  local value="${!name:-}"
  if [[ "${value}" != "${expected}" ]]; then
    mark_failed "${name} 必须为 ${expected}"
  fi
}

require_true_setting MATRIXCODE_PRODUCTION_CHECK
require_true_setting MATRIXCODE_PROTOCOL_CHECK
require_true_setting MATRIXCODE_AUTH_REQUIRE_SA_TOKEN
require_equals_setting MATRIXCODE_AUTH_SESSION_STORE redis

if is_true "${MATRIXCODE_SKIP_CONNECTIVITY_CHECK:-false}" \
  && ! is_true "${MATRIXCODE_PRODUCTION_ALLOW_SKIP_CONNECTIVITY:-false}"; then
  mark_failed "生产启动不允许跳过连通性检查；仅脚本测试可设置 MATRIXCODE_PRODUCTION_ALLOW_SKIP_CONNECTIVITY=true"
fi

if [[ "${FAILED}" -ne 0 ]]; then
  exit 1
fi

"${ROOT_DIR}/scripts/check-real-runtime.sh" "${ENV_FILE}"

echo "生产启动门禁检查通过。"

if is_true "${MATRIXCODE_PRODUCTION_DRY_RUN:-false}"; then
  echo "生产启动干运行通过。"
  exit 0
fi

SERVER_PORT="${SERVER_PORT:-8080}"
MAVEN_BIN="${MATRIXCODE_MAVEN_BIN:-/Users/Masons/Ai/Maven/bin/mvn}"
MAVEN_REPO="${MATRIXCODE_MAVEN_REPO:-/Users/Masons/Ai/Maven_Ai_Store}"
export SERVER_PORT

cd "${ROOT_DIR}"
"${MAVEN_BIN}" \
  -Dmaven.repo.local="${MAVEN_REPO}" \
  -pl server \
  spring-boot:run
