#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
ENV_FILE="${ENV_FILE:-${1:-${ROOT_DIR}/.env.local}}"
FAILED=0

if [[ ! -f "${ENV_FILE}" ]]; then
  echo "缺少 ${ENV_FILE}，请先复制 .env.example 为 .env.local 并填入真实配置。"
  exit 1
fi

set -a
source "${ENV_FILE}"
set +a

SKIP_CONNECTIVITY_CHECK="${MATRIXCODE_SKIP_CONNECTIVITY_CHECK:-false}"
PROTOCOL_CHECK="${MATRIXCODE_PROTOCOL_CHECK:-false}"
PRODUCTION_CHECK="${MATRIXCODE_PRODUCTION_CHECK:-false}"
MAVEN_BIN="${MATRIXCODE_MAVEN_BIN:-/Users/Masons/Ai/Maven/bin/mvn}"
MAVEN_REPO="${MATRIXCODE_MAVEN_REPO:-/Users/Masons/Ai/Maven_Ai_Store}"

is_true() {
  [[ "${1:-}" == "true" || "${1:-}" == "TRUE" || "${1:-}" == "1" ]]
}

mark_failed() {
  echo "配置错误：$1"
  FAILED=1
}

require_value() {
  local name="$1"
  local value="${!name:-}"
  if [[ -z "${value}" ]]; then
    mark_failed "${name} 不能为空"
  fi
}

require_secret() {
  local name="$1"
  local value="${!name:-}"
  if [[ -z "${value}" || "${value}" == "change-me" ]]; then
    mark_failed "${name} 未配置真实值"
  fi
}

is_placeholder_secret() {
  local value="${1:-}"
  [[ -z "${value}" || "${value}" == "change-me" || "${value}" == change-me-* ]]
}

require_production_secret() {
  local name="$1"
  local value="${!name:-}"
  if is_placeholder_secret "${value}"; then
    mark_failed "${name} 必须配置生产真实值"
  fi
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

check_tcp() {
  local label="$1"
  local host="$2"
  local port="$3"
  local required="$4"

  if is_true "${SKIP_CONNECTIVITY_CHECK}"; then
    echo "跳过 ${label} 连通性检查：${host}:${port}"
    return
  fi

  if ! command -v nc >/dev/null 2>&1; then
    echo "未找到 nc，跳过 ${label} 连通性检查：${host}:${port}"
    return
  fi

  if nc -z -w 2 "${host}" "${port}" >/dev/null 2>&1; then
    echo "${label} 连通性正常：${host}:${port}"
    return
  fi

  if [[ "${required}" == "true" ]]; then
    mark_failed "${label} 无法连接：${host}:${port}"
  else
    echo "${label} 当前不可连接（已预留，不阻塞）：${host}:${port}"
  fi
}

run_protocol_checks() {
  if ! is_true "${PROTOCOL_CHECK}"; then
    return
  fi
  if is_true "${SKIP_CONNECTIVITY_CHECK}"; then
    echo "跳过真实协议级检查：已启用 MATRIXCODE_SKIP_CONNECTIVITY_CHECK。"
    return
  fi
  if [[ ! -x "${MAVEN_BIN}" ]]; then
    mark_failed "Maven 不可执行：${MAVEN_BIN}"
    return
  fi

  local tests="RealRuntimeIntegrationTest#真实Mysql可自动建库并执行Flyway迁移"
  if is_true "${MATRIXCODE_VECTOR_CONTEXT_ENABLED:-false}" && [[ "${MATRIXCODE_VECTOR_CONTEXT_STORE:-memory}" == "milvus" ]]; then
    tests="${tests}+真实Milvus可写入并召回向量上下文"
  fi
  if [[ "${MATRIXCODE_AUTH_SESSION_STORE:-memory}" == "redis" ]]; then
    tests="${tests},RealSaTokenRedisSessionIntegrationTest#真实Redis可承载SaToken会话数据"
  fi
  if is_true "${MATRIXCODE_ROCKETMQ_PROTOCOL_CHECK:-false}"; then
    tests="${tests}+真实RocketMq项目事件Topic可收发低敏消息"
  fi

  echo "执行真实协议级检查：${tests}"
  "${MAVEN_BIN}" \
    -Dmaven.repo.local="${MAVEN_REPO}" \
    -Dmatrixcode.real-runtime-test=true \
    -pl server \
    "-Dtest=${tests}" \
    test
}

run_production_checks() {
  if ! is_true "${PRODUCTION_CHECK}"; then
    return
  fi

  require_true_setting MATRIXCODE_AUTH_REQUIRE_SA_TOKEN
  require_equals_setting MATRIXCODE_AUTH_SESSION_STORE redis
  require_true_setting MATRIXCODE_PROTOCOL_CHECK
  require_production_secret MATRIXCODE_AUTH_ACTOR_TOKEN_SECRET
  require_production_secret MATRIXCODE_AUTH_BOOTSTRAP_TOKEN
  require_production_secret MATRIXCODE_EXECUTION_AGENTS_SHARED_SECRET
  require_value MATRIXCODE_AUTH_SESSION_REDIS_KEY_PREFIX
  require_value MATRIXCODE_REDIS_HOST
  require_value MATRIXCODE_REDIS_PORT
  if is_true "${MATRIXCODE_ROCKETMQ_EVENT_RELAY_ENABLED:-false}"; then
    require_true_setting MATRIXCODE_ROCKETMQ_PROTOCOL_CHECK
    require_value MATRIXCODE_ROCKETMQ_NAME_SERVER
    require_value MATRIXCODE_ROCKETMQ_TOPIC_PREFIX
    require_value MATRIXCODE_ROCKETMQ_EVENT_RELAY_TOPIC_SUFFIX
    require_value MATRIXCODE_ROCKETMQ_EVENT_RELAY_TAG
  fi

  if [[ "${FAILED}" -eq 0 ]]; then
    echo "生产上线门禁检查通过。"
  fi
}

require_value MATRIXCODE_PERSISTENCE_JDBC_URL
require_value MATRIXCODE_PERSISTENCE_JDBC_USERNAME
require_secret MATRIXCODE_PERSISTENCE_JDBC_PASSWORD

if is_true "${MATRIXCODE_QWEN_ENABLED:-false}"; then
  require_secret MATRIXCODE_QWEN_API_KEY
fi
if is_true "${MATRIXCODE_DEEPSEEK_ENABLED:-false}"; then
  require_secret MATRIXCODE_DEEPSEEK_API_KEY
fi
if is_true "${MATRIXCODE_KIMI_ENABLED:-false}"; then
  require_secret MATRIXCODE_KIMI_API_KEY
fi
if is_true "${MATRIXCODE_DOUBAO_ENABLED:-false}"; then
  require_secret MATRIXCODE_DOUBAO_API_KEY
fi

run_production_checks

MYSQL_HOST="${MATRIXCODE_MYSQL_HOST:-127.0.0.1}"
MYSQL_PORT="${MATRIXCODE_MYSQL_PORT:-3306}"
check_tcp "MySQL" "${MYSQL_HOST}" "${MYSQL_PORT}" true

if is_true "${MATRIXCODE_VECTOR_CONTEXT_ENABLED:-false}" && [[ "${MATRIXCODE_VECTOR_CONTEXT_STORE:-memory}" == "milvus" ]]; then
  require_value MATRIXCODE_MILVUS_HOST
  require_value MATRIXCODE_MILVUS_PORT
  require_value MATRIXCODE_MILVUS_DATABASE
  require_value MATRIXCODE_MILVUS_COLLECTION
  require_value MATRIXCODE_VECTOR_CONTEXT_DIMENSION
  check_tcp "Milvus" "${MATRIXCODE_MILVUS_HOST}" "${MATRIXCODE_MILVUS_PORT}" true
fi

REDIS_REQUIRED="false"
if is_true "${PRODUCTION_CHECK}" || [[ "${MATRIXCODE_AUTH_SESSION_STORE:-memory}" == "redis" ]]; then
  REDIS_REQUIRED="true"
fi
check_tcp "Redis" "${MATRIXCODE_REDIS_HOST:-127.0.0.1}" "${MATRIXCODE_REDIS_PORT:-6379}" "${REDIS_REQUIRED}"
if [[ -n "${MATRIXCODE_ROCKETMQ_NAME_SERVER:-}" ]]; then
  ROCKETMQ_HOST="${MATRIXCODE_ROCKETMQ_NAME_SERVER%%:*}"
  ROCKETMQ_PORT="${MATRIXCODE_ROCKETMQ_NAME_SERVER##*:}"
  check_tcp "RocketMQ" "${ROCKETMQ_HOST}" "${ROCKETMQ_PORT}" false
fi

if [[ "${FAILED}" -ne 0 ]]; then
  exit 1
fi

run_protocol_checks

if [[ "${FAILED}" -ne 0 ]]; then
  exit 1
fi

echo "真实运行配置检查通过。"
