#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
TMP_DIR="$(mktemp -d)"
trap 'rm -rf "${TMP_DIR}"' EXIT

write_env() {
  local env_file="$1"
  local require_sa_token="$2"
  cat >"${env_file}" <<EOF_ENV
MATRIXCODE_PERSISTENCE_JDBC_URL=jdbc:mysql://127.0.0.1:3306/matrix_code
MATRIXCODE_PERSISTENCE_JDBC_USERNAME=matrixcode
MATRIXCODE_PERSISTENCE_JDBC_PASSWORD=real-db-password
MATRIXCODE_AUTH_REQUIRE_SA_TOKEN=${require_sa_token}
MATRIXCODE_AUTH_SESSION_STORE=redis
MATRIXCODE_AUTH_SESSION_REDIS_KEY_PREFIX=matrixcode:sa-token:
MATRIXCODE_AUTH_ACTOR_TOKEN_SECRET=real-actor-token-secret-at-least-32
MATRIXCODE_AUTH_BOOTSTRAP_TOKEN=real-bootstrap-token
MATRIXCODE_EXECUTION_AGENTS_SHARED_SECRET=real-execution-agent-secret
MATRIXCODE_PRODUCTION_CHECK=true
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

run_production_check() {
  local env_file="$1"
  "${ROOT_DIR}/scripts/check-real-runtime.sh" "${env_file}" 2>&1
}

write_fake_runtime_tools() {
  local bin_dir="$1"
  mkdir -p "${bin_dir}"
  cat >"${bin_dir}/nc" <<'EOF_NC'
#!/usr/bin/env bash
exit 0
EOF_NC
  cat >"${bin_dir}/mvn" <<'EOF_MVN'
#!/usr/bin/env bash
printf '%s\n' "$*"
exit 0
EOF_MVN
  chmod +x "${bin_dir}/nc" "${bin_dir}/mvn"
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
if output="$(run_production_check "${bad_env}")"; then
  echo "断言失败：生产门禁应拒绝未开启强制 Sa-Token 的配置" >&2
  echo "${output}" >&2
  exit 1
fi
assert_contains "${output}" "MATRIXCODE_AUTH_REQUIRE_SA_TOKEN 必须为 true"

good_env="${TMP_DIR}/good.env"
write_env "${good_env}" true
output="$(run_production_check "${good_env}")"
assert_contains "${output}" "生产上线门禁检查通过。"
assert_contains "${output}" "真实运行配置检查通过。"

bad_rocketmq_env="${TMP_DIR}/bad-rocketmq.env"
write_env "${bad_rocketmq_env}" true
cat >>"${bad_rocketmq_env}" <<EOF_ENV
MATRIXCODE_ROCKETMQ_EVENT_RELAY_ENABLED=true
MATRIXCODE_ROCKETMQ_PROTOCOL_CHECK=false
MATRIXCODE_ROCKETMQ_NAME_SERVER=127.0.0.1:9876
MATRIXCODE_ROCKETMQ_TOPIC_PREFIX=matrixcode
MATRIXCODE_ROCKETMQ_EVENT_RELAY_TOPIC_SUFFIX=project-events
MATRIXCODE_ROCKETMQ_EVENT_RELAY_TAG=project-event
EOF_ENV
if output="$(run_production_check "${bad_rocketmq_env}")"; then
  echo "断言失败：生产门禁应拒绝开启 RocketMQ 中继但未开启协议门禁的配置" >&2
  echo "${output}" >&2
  exit 1
fi
assert_contains "${output}" "MATRIXCODE_ROCKETMQ_PROTOCOL_CHECK 必须为 true"

fake_bin="${TMP_DIR}/bin"
write_fake_runtime_tools "${fake_bin}"

protocol_env="${TMP_DIR}/protocol.env"
write_env "${protocol_env}" true
cat >>"${protocol_env}" <<EOF_ENV
MATRIXCODE_SKIP_CONNECTIVITY_CHECK=false
MATRIXCODE_MAVEN_BIN=${fake_bin}/mvn
MATRIXCODE_MAVEN_REPO=${TMP_DIR}/m2
MATRIXCODE_ROCKETMQ_EVENT_RELAY_ENABLED=true
MATRIXCODE_ROCKETMQ_PROTOCOL_CHECK=true
MATRIXCODE_ROCKETMQ_NAME_SERVER=127.0.0.1:9876
MATRIXCODE_ROCKETMQ_TOPIC_PREFIX=matrixcode
MATRIXCODE_ROCKETMQ_EVENT_RELAY_TOPIC_SUFFIX=project-events
MATRIXCODE_ROCKETMQ_EVENT_RELAY_TAG=project-event
EOF_ENV
output="$(PATH="${fake_bin}:${PATH}" run_production_check "${protocol_env}")"
assert_contains "${output}" "RealRuntimeIntegrationTest#真实Mysql可自动建库并执行Flyway迁移"
assert_contains "${output}" "RealSaTokenRedisSessionIntegrationTest#真实Redis可承载SaToken会话数据"
assert_contains "${output}" "真实RocketMq项目事件Topic可收发低敏消息"

echo "生产上线门禁脚本测试通过。"
