#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
TMP_DIR="$(mktemp -d)"
trap 'rm -rf "${TMP_DIR}"' EXIT

write_env() {
  local env_file="$1"
  local production_check="$2"
  cat >"${env_file}" <<EOF_ENV
MATRIXCODE_PERSISTENCE_JDBC_URL=jdbc:mysql://127.0.0.1:3306/matrix_code
MATRIXCODE_PERSISTENCE_JDBC_USERNAME=matrixcode
MATRIXCODE_PERSISTENCE_JDBC_PASSWORD=real-db-password
MATRIXCODE_AUTH_REQUIRE_SA_TOKEN=true
MATRIXCODE_AUTH_SESSION_STORE=redis
MATRIXCODE_AUTH_SESSION_REDIS_KEY_PREFIX=matrixcode:sa-token:
MATRIXCODE_AUTH_ACTOR_TOKEN_SECRET=real-actor-token-secret-at-least-32
MATRIXCODE_AUTH_BOOTSTRAP_TOKEN=real-bootstrap-token
MATRIXCODE_EXECUTION_AGENTS_SHARED_SECRET=real-execution-agent-secret
MATRIXCODE_PRODUCTION_CHECK=${production_check}
MATRIXCODE_PROTOCOL_CHECK=true
MATRIXCODE_SKIP_CONNECTIVITY_CHECK=true
MATRIXCODE_QWEN_ENABLED=false
MATRIXCODE_DEEPSEEK_ENABLED=false
MATRIXCODE_KIMI_ENABLED=false
MATRIXCODE_DOUBAO_ENABLED=false
MATRIXCODE_VECTOR_CONTEXT_ENABLED=false
MATRIXCODE_REDIS_HOST=127.0.0.1
MATRIXCODE_REDIS_PORT=6379
EOF_ENV
}

assert_contains() {
  local haystack="$1"
  local needle="$2"
  if [[ "${haystack}" != *"${needle}"* ]]; then
    echo "断言失败：输出中缺少 ${needle}" >&2
    echo "${haystack}" >&2
    exit 1
  fi
}

bad_env="${TMP_DIR}/bad.env"
write_env "${bad_env}" false
if output="$(MATRIXCODE_PRODUCTION_DRY_RUN=true MATRIXCODE_PRODUCTION_ALLOW_SKIP_CONNECTIVITY=true "${ROOT_DIR}/scripts/run-production-local.sh" "${bad_env}" 2>&1)"; then
  echo "断言失败：生产启动脚本应拒绝未开启生产门禁的配置" >&2
  echo "${output}" >&2
  exit 1
fi
assert_contains "${output}" "MATRIXCODE_PRODUCTION_CHECK 必须为 true"

skip_env="${TMP_DIR}/skip.env"
write_env "${skip_env}" true
if output="$(MATRIXCODE_PRODUCTION_DRY_RUN=true "${ROOT_DIR}/scripts/run-production-local.sh" "${skip_env}" 2>&1)"; then
  echo "断言失败：生产启动脚本默认应拒绝跳过连通性检查" >&2
  echo "${output}" >&2
  exit 1
fi
assert_contains "${output}" "生产启动不允许跳过连通性检查"

good_env="${TMP_DIR}/good.env"
write_env "${good_env}" true
output="$(MATRIXCODE_PRODUCTION_DRY_RUN=true MATRIXCODE_PRODUCTION_ALLOW_SKIP_CONNECTIVITY=true "${ROOT_DIR}/scripts/run-production-local.sh" "${good_env}" 2>&1)"
assert_contains "${output}" "生产启动门禁检查通过。"
assert_contains "${output}" "生产启动干运行通过。"

echo "生产启动脚本测试通过。"
