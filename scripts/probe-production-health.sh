#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
ENV_FILE="${1:-${ENV_FILE:-${ROOT_DIR}/.env.local}}"

is_true() {
  [[ "${1:-}" == "true" || "${1:-}" == "TRUE" || "${1:-}" == "1" ]]
}

fail() {
  echo "MatrixCode 生产健康探测配置错误：$1" >&2
  exit 1
}

json_escape() {
  local value="${1:-}"
  value="${value//\\/\\\\}"
  value="${value//\"/\\\"}"
  value="${value//$'\r'/\\r}"
  value="${value//$'\n'/\\n}"
  value="${value//$'\t'/\\t}"
  printf '%s' "${value}"
}

single_line() {
  local value="${1:-}"
  value="${value//$'\r'/ }"
  value="${value//$'\n'/ }"
  value="${value//$'\t'/ }"
  printf '%s' "${value}"
}

hmac_sha256() {
  local payload="$1"
  local secret="$2"
  printf '%s' "${payload}" | openssl dgst -sha256 -hmac "${secret}" -r | awk '{print $1}'
}

## 判断当前时间是否仍处在告警静默窗口内；静默只跳过 webhook，不改变健康检查失败结果。
alert_silenced() {
  [[ "${MATRIXCODE_ALERT_SILENCE_UNTIL_EPOCH_SECONDS}" -gt "${MATRIXCODE_ALERT_NOW_EPOCH_SECONDS}" ]]
}

alert_text() {
  local summary="$1"
  local details="$2"
  local text
  text="$(printf 'MatrixCode 告警\n服务：matrixcode\n状态：DOWN\n级别：%s\n摘要：%s\n详情：%s' \
    "${MATRIXCODE_ALERT_SEVERITY}" \
    "${summary}" \
    "$(single_line "${details}")")"
  if [[ -n "${MATRIXCODE_ALERT_ESCALATION_OWNER:-}" ]]; then
    text="${text}"$'\n'"值班：${MATRIXCODE_ALERT_ESCALATION_OWNER}"
  fi
  if [[ -n "${MATRIXCODE_ALERT_RUNBOOK_URL:-}" ]]; then
    text="${text}"$'\n'"手册：${MATRIXCODE_ALERT_RUNBOOK_URL}"
  fi
  printf '%s' "${text}"
}

alert_payload() {
  local summary="$1"
  local details="$2"
  local text
  text="$(alert_text "${summary}" "${details}")"
  case "${MATRIXCODE_ALERT_WEBHOOK_FORMAT}" in
    matrixcode)
      local payload
      payload="{\"service\":\"matrixcode\",\"status\":\"DOWN\",\"severity\":\"$(json_escape "${MATRIXCODE_ALERT_SEVERITY}")\",\"summary\":\"$(json_escape "${summary}")\",\"details\":\"$(json_escape "$(single_line "${details}")")\""
      if [[ -n "${MATRIXCODE_ALERT_ESCALATION_OWNER:-}" ]]; then
        payload="${payload},\"escalationOwner\":\"$(json_escape "${MATRIXCODE_ALERT_ESCALATION_OWNER}")\""
      fi
      if [[ -n "${MATRIXCODE_ALERT_RUNBOOK_URL:-}" ]]; then
        payload="${payload},\"runbookUrl\":\"$(json_escape "${MATRIXCODE_ALERT_RUNBOOK_URL}")\""
      fi
      payload="${payload}}"
      printf '%s' "${payload}"
      ;;
    dingtalk|wecom)
      printf '{"msgtype":"text","text":{"content":"%s"}}' "$(json_escape "${text}")"
      ;;
    feishu)
      printf '{"msg_type":"text","content":{"text":"%s"}}' "$(json_escape "${text}")"
      ;;
    slack)
      printf '{"text":"%s"}' "$(json_escape "${text}")"
      ;;
  esac
}

send_alert() {
  local summary="$1"
  local details="$2"
  if alert_silenced; then
    echo "健康探测失败，告警静默中：${MATRIXCODE_ALERT_SILENCE_REASON:-未填写原因}，直到 epoch ${MATRIXCODE_ALERT_SILENCE_UNTIL_EPOCH_SECONDS}。"
    return 0
  fi

  local webhook="${MATRIXCODE_ALERT_WEBHOOK_URL:-}"
  [[ -n "${webhook}" ]] || {
    echo "健康探测失败，但 MATRIXCODE_ALERT_WEBHOOK_URL 未配置。"
    return 0
  }

  local payload
  payload="$(alert_payload "${summary}" "${details}")"
  local headers=(-H 'Content-Type: application/json')
  if [[ -n "${MATRIXCODE_ALERT_WEBHOOK_SECRET:-}" ]]; then
    headers+=(-H "X-MatrixCode-Alert-Signature: sha256=$(hmac_sha256 "${payload}" "${MATRIXCODE_ALERT_WEBHOOK_SECRET}")")
  fi

  local attempt
  for ((attempt = 1; attempt <= MATRIXCODE_ALERT_WEBHOOK_RETRIES; attempt++)); do
    if curl -fsS \
      --max-time "${MATRIXCODE_HEALTH_PROBE_TIMEOUT_SECONDS}" \
      "${headers[@]}" \
      -X POST \
      --data "${payload}" \
      "${webhook}" >/dev/null; then
      return 0
    fi
    if [[ "${attempt}" -lt "${MATRIXCODE_ALERT_WEBHOOK_RETRIES}" && "${MATRIXCODE_ALERT_WEBHOOK_RETRY_DELAY_SECONDS}" -gt 0 ]]; then
      sleep "${MATRIXCODE_ALERT_WEBHOOK_RETRY_DELAY_SECONDS}"
    fi
  done
  if [[ "${MATRIXCODE_ALERT_WEBHOOK_RETRIES}" -gt 1 ]]; then
    echo "健康探测失败，且告警 webhook 发送失败（已尝试 ${MATRIXCODE_ALERT_WEBHOOK_RETRIES} 次）。"
  else
    echo "健康探测失败，且告警 webhook 发送失败。"
  fi
}

if [[ ! -f "${ENV_FILE}" ]]; then
  fail "缺少环境文件 ${ENV_FILE}"
fi

set -a
source "${ENV_FILE}"
set +a

is_true "${MATRIXCODE_PRODUCTION_CHECK:-false}" || fail "MATRIXCODE_PRODUCTION_CHECK 必须为 true"

MATRIXCODE_HEALTH_PROBE_TIMEOUT_SECONDS="${MATRIXCODE_HEALTH_PROBE_TIMEOUT_SECONDS:-5}"
if [[ ! "${MATRIXCODE_HEALTH_PROBE_TIMEOUT_SECONDS}" =~ ^[0-9]+$ || "${MATRIXCODE_HEALTH_PROBE_TIMEOUT_SECONDS}" -lt 1 ]]; then
  fail "MATRIXCODE_HEALTH_PROBE_TIMEOUT_SECONDS 必须为正整数"
fi
MATRIXCODE_ALERT_WEBHOOK_RETRIES="${MATRIXCODE_ALERT_WEBHOOK_RETRIES:-1}"
if [[ ! "${MATRIXCODE_ALERT_WEBHOOK_RETRIES}" =~ ^[0-9]+$ || "${MATRIXCODE_ALERT_WEBHOOK_RETRIES}" -lt 1 ]]; then
  fail "MATRIXCODE_ALERT_WEBHOOK_RETRIES 必须为正整数"
fi
MATRIXCODE_ALERT_WEBHOOK_RETRY_DELAY_SECONDS="${MATRIXCODE_ALERT_WEBHOOK_RETRY_DELAY_SECONDS:-1}"
if [[ ! "${MATRIXCODE_ALERT_WEBHOOK_RETRY_DELAY_SECONDS}" =~ ^[0-9]+$ ]]; then
  fail "MATRIXCODE_ALERT_WEBHOOK_RETRY_DELAY_SECONDS 必须为非负整数"
fi
MATRIXCODE_ALERT_WEBHOOK_FORMAT="${MATRIXCODE_ALERT_WEBHOOK_FORMAT:-matrixcode}"
case "${MATRIXCODE_ALERT_WEBHOOK_FORMAT}" in
  matrixcode|dingtalk|wecom|feishu|slack)
    ;;
  *)
    fail "MATRIXCODE_ALERT_WEBHOOK_FORMAT 只支持 matrixcode、dingtalk、wecom、feishu 或 slack"
    ;;
esac
MATRIXCODE_ALERT_SEVERITY="${MATRIXCODE_ALERT_SEVERITY:-P1}"
case "${MATRIXCODE_ALERT_SEVERITY}" in
  P1|P2|P3)
    ;;
  *)
    fail "MATRIXCODE_ALERT_SEVERITY 只支持 P1、P2 或 P3"
    ;;
esac
MATRIXCODE_ALERT_SILENCE_UNTIL_EPOCH_SECONDS="${MATRIXCODE_ALERT_SILENCE_UNTIL_EPOCH_SECONDS:-0}"
if [[ ! "${MATRIXCODE_ALERT_SILENCE_UNTIL_EPOCH_SECONDS}" =~ ^[0-9]+$ ]]; then
  fail "MATRIXCODE_ALERT_SILENCE_UNTIL_EPOCH_SECONDS 必须为非负整数"
fi
MATRIXCODE_ALERT_NOW_EPOCH_SECONDS="${MATRIXCODE_ALERT_NOW_EPOCH_SECONDS:-$(date +%s)}"
if [[ ! "${MATRIXCODE_ALERT_NOW_EPOCH_SECONDS}" =~ ^[0-9]+$ ]]; then
  fail "MATRIXCODE_ALERT_NOW_EPOCH_SECONDS 必须为非负整数"
fi
if [[ -n "${MATRIXCODE_ALERT_WEBHOOK_SECRET:-}" ]] && ! command -v openssl >/dev/null 2>&1; then
  fail "配置 MATRIXCODE_ALERT_WEBHOOK_SECRET 时必须安装 openssl"
fi

server_port="${SERVER_PORT:-8080}"
health_url="${MATRIXCODE_HEALTH_PROBE_URL:-http://127.0.0.1:${server_port}/actuator/health}"

if is_true "${MATRIXCODE_HEALTH_PROBE_DRY_RUN:-false}"; then
  echo "生产健康探测 dry-run 通过：${health_url}，超时 ${MATRIXCODE_HEALTH_PROBE_TIMEOUT_SECONDS}s。"
  exit 0
fi

if ! command -v curl >/dev/null 2>&1; then
  fail "未找到 curl"
fi

if ! response="$(curl -fsS --max-time "${MATRIXCODE_HEALTH_PROBE_TIMEOUT_SECONDS}" "${health_url}" 2>&1)"; then
  send_alert "MatrixCode health endpoint request failed" "${response}"
  echo "MatrixCode 健康检查失败：请求 ${health_url} 失败。"
  exit 1
fi

case "${response}" in
  *'"status":"UP"'*|*'"status": "UP"'*)
    echo "MatrixCode 健康检查通过：${health_url}"
    ;;
  *)
    send_alert "MatrixCode health status is not UP" "${response}"
    echo "MatrixCode 健康检查失败：响应不是 UP。"
    exit 1
    ;;
esac
