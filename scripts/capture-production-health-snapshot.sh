#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
ENV_FILE="${1:-${ENV_FILE:-${ROOT_DIR}/.env.local}}"
OUTPUT_PATH="${2:-${MATRIXCODE_HEALTH_SNAPSHOT_OUTPUT:-}}"

is_true() {
  [[ "${1:-}" == "true" || "${1:-}" == "TRUE" || "${1:-}" == "1" ]]
}

fail() {
  echo "MatrixCode 生产健康快照配置错误：$1" >&2
  exit 1
}

single_line() {
  local value="${1:-}"
  value="${value//$'\r'/ }"
  value="${value//$'\n'/ }"
  value="${value//$'\t'/ }"
  printf '%s' "${value}"
}

safe_url() {
  local value="${1:-}"
  if [[ "${value}" == *\?* ]]; then
    printf '%s?redacted' "${value%%\?*}"
  else
    printf '%s' "${value}"
  fi
}

sha256_file() {
  local file="$1"
  if command -v shasum >/dev/null 2>&1; then
    shasum -a 256 "${file}" | awk '{print $1}'
    return 0
  fi
  if command -v sha256sum >/dev/null 2>&1; then
    sha256sum "${file}" | awk '{print $1}'
    return 0
  fi
  printf 'unavailable'
}

git_commit() {
  if git -C "${ROOT_DIR}" rev-parse --is-inside-work-tree >/dev/null 2>&1; then
    git -C "${ROOT_DIR}" rev-parse --short HEAD
  else
    printf '%s' "${MATRIXCODE_RELEASE_COMMIT:-unknown}"
  fi
}

if [[ ! -f "${ENV_FILE}" ]]; then
  fail "缺少环境文件 ${ENV_FILE}"
fi

set -a
source "${ENV_FILE}"
set +a

is_true "${MATRIXCODE_PRODUCTION_CHECK:-false}" || fail "MATRIXCODE_PRODUCTION_CHECK 必须为 true"

if [[ -n "${MATRIXCODE_HEALTH_PROBE_URL_OVERRIDE:-}" ]]; then
  MATRIXCODE_HEALTH_PROBE_URL="${MATRIXCODE_HEALTH_PROBE_URL_OVERRIDE}"
fi
if [[ -n "${MATRIXCODE_INFO_PROBE_URL_OVERRIDE:-}" ]]; then
  MATRIXCODE_INFO_PROBE_URL="${MATRIXCODE_INFO_PROBE_URL_OVERRIDE}"
fi

MATRIXCODE_HEALTH_PROBE_TIMEOUT_SECONDS="${MATRIXCODE_HEALTH_PROBE_TIMEOUT_SECONDS:-5}"
if [[ ! "${MATRIXCODE_HEALTH_PROBE_TIMEOUT_SECONDS}" =~ ^[0-9]+$ || "${MATRIXCODE_HEALTH_PROBE_TIMEOUT_SECONDS}" -lt 1 ]]; then
  fail "MATRIXCODE_HEALTH_PROBE_TIMEOUT_SECONDS 必须为正整数"
fi

if ! command -v curl >/dev/null 2>&1; then
  fail "未找到 curl"
fi

server_port="${SERVER_PORT:-8080}"
health_url="${MATRIXCODE_HEALTH_PROBE_URL:-http://127.0.0.1:${server_port}/actuator/health}"
info_url="${MATRIXCODE_INFO_PROBE_URL:-${health_url%/health}/info}"
timestamp="${MATRIXCODE_HEALTH_SNAPSHOT_NOW:-$(date -u +%Y-%m-%dT%H:%M:%SZ)}"

if [[ -z "${OUTPUT_PATH}" ]]; then
  file_timestamp="${timestamp//[:TZ-]/}"
  OUTPUT_PATH="${ROOT_DIR}/dist/health-snapshots/matrixcode-health-${file_timestamp}.md"
fi
mkdir -p "$(dirname "${OUTPUT_PATH}")"

health_status="FAIL"
health_detail="未执行"
if health_response="$(curl -fsS --max-time "${MATRIXCODE_HEALTH_PROBE_TIMEOUT_SECONDS}" "${health_url}" 2>&1)"; then
  case "${health_response}" in
    *'"status":"UP"'*|*'"status": "UP"'*)
      health_status="PASS"
      health_detail="$(safe_url "${health_url}") 返回 UP"
      ;;
    *)
      health_status="FAIL"
      health_detail="$(safe_url "${health_url}") 响应不是 UP"
      ;;
  esac
else
  health_status="FAIL"
  health_detail="$(safe_url "${health_url}") 请求失败：$(single_line "${health_response}")"
fi

info_status="WARN"
info_detail="未执行"
if info_response="$(curl -fsS --max-time "${MATRIXCODE_HEALTH_PROBE_TIMEOUT_SECONDS}" "${info_url}" 2>&1)"; then
  info_status="PASS"
  info_detail="$(safe_url "${info_url}") 可访问"
else
  info_status="WARN"
  info_detail="$(safe_url "${info_url}") 不可访问：$(single_line "${info_response}")"
fi

env_sha256="$(sha256_file "${ENV_FILE}")"
commit="$(git_commit)"

cat >"${OUTPUT_PATH}" <<EOF_REPORT
# MatrixCode 生产健康快照

- Generated At: ${timestamp}
- Git Commit: ${commit}
- Env SHA256: ${env_sha256}

| Check | Status | Detail |
| --- | --- | --- |
| Health Endpoint | ${health_status} | ${health_detail} |
| Info Endpoint | ${info_status} | ${info_detail} |

| Config | Value |
| --- | --- |
| Sa-Token 强制模式 | ${MATRIXCODE_AUTH_REQUIRE_SA_TOKEN:-unset} |
| Sa-Token Session Store | ${MATRIXCODE_AUTH_SESSION_STORE:-unset} |
| RocketMQ 事件中继 | ${MATRIXCODE_ROCKETMQ_EVENT_RELAY_ENABLED:-unset} |
| RocketMQ 协议门禁 | ${MATRIXCODE_ROCKETMQ_PROTOCOL_CHECK:-unset} |

## 低敏边界

- 本快照只记录健康状态、Git 提交、env 文件 SHA256 和关键布尔配置。
- 本快照不记录数据库密码、API Key、Sa-Token、模型响应、完整 prompt、向量正文或工具输出。
EOF_REPORT

echo "生产健康快照已生成：${OUTPUT_PATH}"
if [[ "${health_status}" != "PASS" ]]; then
  echo "MatrixCode 生产健康快照失败：健康状态不是 UP。"
  exit 1
fi
