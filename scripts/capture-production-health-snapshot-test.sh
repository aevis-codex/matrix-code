#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
TMP_DIR="$(mktemp -d)"
trap 'rm -rf "${TMP_DIR}"' EXIT

write_env() {
  local env_file="$1"
  local production_check="$2"
  local health_url="$3"
  local info_url="$4"
  cat >"${env_file}" <<EOF_ENV
MATRIXCODE_PRODUCTION_CHECK=${production_check}
MATRIXCODE_HEALTH_PROBE_URL=${health_url}
MATRIXCODE_INFO_PROBE_URL=${info_url}
MATRIXCODE_HEALTH_PROBE_TIMEOUT_SECONDS=2
MATRIXCODE_AUTH_REQUIRE_SA_TOKEN=true
MATRIXCODE_AUTH_SESSION_STORE=redis
MATRIXCODE_ROCKETMQ_EVENT_RELAY_ENABLED=false
MATRIXCODE_ROCKETMQ_PROTOCOL_CHECK=false
MATRIXCODE_DATABASE_PASSWORD=SuperSecret@123
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

assert_not_contains() {
  local haystack="$1"
  local needle="$2"
  if [[ "${haystack}" == *"${needle}"* ]]; then
    echo "断言失败：输出中不应包含 ${needle}" >&2
    echo "${haystack}" >&2
    exit 1
  fi
}

up_file="${TMP_DIR}/health-up.json"
down_file="${TMP_DIR}/health-down.json"
info_file="${TMP_DIR}/info.json"
printf '{"status":"UP"}' >"${up_file}"
printf '{"status":"DOWN"}' >"${down_file}"
printf '{"app":{"name":"matrixcode"}}' >"${info_file}"

bad_gate_env="${TMP_DIR}/bad-gate.env"
write_env "${bad_gate_env}" false "file://${up_file}" "file://${info_file}"
if output="$("${ROOT_DIR}/scripts/capture-production-health-snapshot.sh" "${bad_gate_env}" "${TMP_DIR}/bad.md" 2>&1)"; then
  echo "断言失败：健康快照应拒绝未开启生产门禁的配置" >&2
  echo "${output}" >&2
  exit 1
fi
assert_contains "${output}" "MATRIXCODE_PRODUCTION_CHECK 必须为 true"

good_env="${TMP_DIR}/good.env"
good_report="${TMP_DIR}/good.md"
write_env "${good_env}" true "file://${up_file}" "file://${info_file}"
output="$("${ROOT_DIR}/scripts/capture-production-health-snapshot.sh" "${good_env}" "${good_report}" 2>&1)"
assert_contains "${output}" "生产健康快照已生成"
report="$(cat "${good_report}")"
assert_contains "${report}" "# MatrixCode 生产健康快照"
assert_contains "${report}" "| Health Endpoint | PASS |"
assert_contains "${report}" "| Info Endpoint | PASS |"
assert_contains "${report}" "| Sa-Token 强制模式 | true |"
assert_contains "${report}" "| Sa-Token Session Store | redis |"
assert_contains "${report}" "| RocketMQ 事件中继 | false |"
assert_contains "${report}" "| RocketMQ 协议门禁 | false |"
assert_contains "${report}" "Env SHA256"
assert_not_contains "${report}" "SuperSecret@123"

override_env="${TMP_DIR}/override.env"
override_report="${TMP_DIR}/override.md"
write_env "${override_env}" true "file://${down_file}" "file://${info_file}"
if ! output="$(MATRIXCODE_HEALTH_PROBE_URL_OVERRIDE="file://${up_file}" MATRIXCODE_INFO_PROBE_URL_OVERRIDE="file://${info_file}" "${ROOT_DIR}/scripts/capture-production-health-snapshot.sh" "${override_env}" "${override_report}" 2>&1)"; then
  echo "断言失败：健康快照应允许发布编排覆盖健康地址" >&2
  echo "${output}" >&2
  exit 1
fi
assert_contains "${output}" "生产健康快照已生成"
report="$(cat "${override_report}")"
assert_contains "${report}" "| Health Endpoint | PASS |"

down_env="${TMP_DIR}/down.env"
down_report="${TMP_DIR}/down.md"
write_env "${down_env}" true "file://${down_file}" "file://${info_file}"
if output="$("${ROOT_DIR}/scripts/capture-production-health-snapshot.sh" "${down_env}" "${down_report}" 2>&1)"; then
  echo "断言失败：健康快照应在 DOWN 响应后返回失败" >&2
  echo "${output}" >&2
  exit 1
fi
assert_contains "${output}" "健康状态不是 UP"
report="$(cat "${down_report}")"
assert_contains "${report}" "| Health Endpoint | FAIL |"
assert_not_contains "${report}" "SuperSecret@123"

echo "生产健康快照脚本测试通过。"
