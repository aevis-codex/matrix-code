#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
TMP_DIR="$(mktemp -d)"
trap 'rm -rf "${TMP_DIR}"' EXIT

write_env() {
  local env_file="$1"
  local production_check="$2"
  local health_url="$3"
  cat >"${env_file}" <<EOF_ENV
MATRIXCODE_PRODUCTION_CHECK=${production_check}
MATRIXCODE_HEALTH_PROBE_URL=${health_url}
MATRIXCODE_HEALTH_PROBE_TIMEOUT_SECONDS=2
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

up_file="${TMP_DIR}/health-up.json"
down_file="${TMP_DIR}/health-down.json"
printf '{"status":"UP"}' >"${up_file}"
printf '{"status":"DOWN"}' >"${down_file}"

bad_gate_env="${TMP_DIR}/bad-gate.env"
write_env "${bad_gate_env}" false "file://${up_file}"
if output="$("${ROOT_DIR}/scripts/probe-production-health.sh" "${bad_gate_env}" 2>&1)"; then
  echo "断言失败：健康探测应拒绝未开启生产门禁的配置" >&2
  echo "${output}" >&2
  exit 1
fi
assert_contains "${output}" "MATRIXCODE_PRODUCTION_CHECK 必须为 true"

good_env="${TMP_DIR}/good.env"
write_env "${good_env}" true "file://${up_file}"
output="$("${ROOT_DIR}/scripts/probe-production-health.sh" "${good_env}" 2>&1)"
assert_contains "${output}" "MatrixCode 健康检查通过"

dry_run_env="${TMP_DIR}/dry-run.env"
write_env "${dry_run_env}" true "file://${up_file}"
output="$(MATRIXCODE_HEALTH_PROBE_DRY_RUN=true "${ROOT_DIR}/scripts/probe-production-health.sh" "${dry_run_env}" 2>&1)"
assert_contains "${output}" "生产健康探测 dry-run 通过"

down_env="${TMP_DIR}/down.env"
write_env "${down_env}" true "file://${down_file}"
if output="$("${ROOT_DIR}/scripts/probe-production-health.sh" "${down_env}" 2>&1)"; then
  echo "断言失败：健康探测应拒绝 DOWN 响应" >&2
  echo "${output}" >&2
  exit 1
fi
assert_contains "${output}" "健康探测失败"
assert_contains "${output}" "响应不是 UP"

fake_bin="${TMP_DIR}/bin"
mkdir -p "${fake_bin}"
cat >"${fake_bin}/curl" <<EOF_CURL
#!/usr/bin/env bash
set -euo pipefail

url="\${!#}"
data=""
headers=""
while [[ "\$#" -gt 0 ]]; do
  case "\$1" in
    --data)
      shift
      data="\$1"
      ;;
    -H)
      shift
      headers="\${headers}\$1
"
      ;;
  esac
  shift || true
done

case "\${url}" in
  file://*)
    cat "\${url#file://}"
    ;;
  https://alert.example/*)
    count_file="${TMP_DIR}/alert-count"
    count=0
    [[ -f "\${count_file}" ]] && count="\$(cat "\${count_file}")"
    count="\$((count + 1))"
    printf '%s' "\${count}" >"\${count_file}"
    printf '%s' "\${data}" >"${TMP_DIR}/alert-payload.json"
    printf '%s' "\${headers}" >"${TMP_DIR}/alert-headers.txt"
    if [[ "\${count}" -lt 2 ]]; then
      exit 22
    fi
    ;;
  *)
    echo "unexpected curl url: \${url}" >&2
    exit 2
    ;;
esac
EOF_CURL
chmod +x "${fake_bin}/curl"

silence_env="${TMP_DIR}/silence.env"
write_env "${silence_env}" true "file://${down_file}"
cat >>"${silence_env}" <<EOF_ENV
MATRIXCODE_ALERT_WEBHOOK_URL=https://alert.example/webhook
MATRIXCODE_ALERT_SILENCE_UNTIL_EPOCH_SECONDS=2000
MATRIXCODE_ALERT_NOW_EPOCH_SECONDS=1000
MATRIXCODE_ALERT_SILENCE_REASON=production-release
EOF_ENV

if output="$(PATH="${fake_bin}:${PATH}" "${ROOT_DIR}/scripts/probe-production-health.sh" "${silence_env}" 2>&1)"; then
  echo "断言失败：静默窗口内健康探测失败仍应返回失败" >&2
  echo "${output}" >&2
  exit 1
fi
assert_contains "${output}" "告警静默中"
assert_contains "${output}" "production-release"
[[ ! -f "${TMP_DIR}/alert-count" ]] || {
  echo "断言失败：静默窗口内不应发送告警 webhook" >&2
  exit 1
}

alert_env="${TMP_DIR}/alert.env"
write_env "${alert_env}" true "file://${down_file}"
cat >>"${alert_env}" <<EOF_ENV
MATRIXCODE_ALERT_WEBHOOK_URL=https://alert.example/webhook
MATRIXCODE_ALERT_WEBHOOK_SECRET=test-alert-secret
MATRIXCODE_ALERT_WEBHOOK_RETRIES=2
MATRIXCODE_ALERT_WEBHOOK_RETRY_DELAY_SECONDS=0
EOF_ENV

if output="$(PATH="${fake_bin}:${PATH}" "${ROOT_DIR}/scripts/probe-production-health.sh" "${alert_env}" 2>&1)"; then
  echo "断言失败：健康探测应在 DOWN 响应后返回失败" >&2
  echo "${output}" >&2
  exit 1
fi
assert_contains "${output}" "响应不是 UP"
alert_count="$(cat "${TMP_DIR}/alert-count")"
[[ "${alert_count}" == "2" ]] || {
  echo "断言失败：告警 webhook 应重试 2 次，实际 ${alert_count}" >&2
  exit 1
}
assert_contains "$(cat "${TMP_DIR}/alert-headers.txt")" "X-MatrixCode-Alert-Signature: sha256="
assert_contains "$(cat "${TMP_DIR}/alert-payload.json")" '"service":"matrixcode"'
assert_contains "$(cat "${TMP_DIR}/alert-payload.json")" '"status":"DOWN"'

unsupported_format_env="${TMP_DIR}/unsupported-format.env"
write_env "${unsupported_format_env}" true "file://${up_file}"
cat >>"${unsupported_format_env}" <<EOF_ENV
MATRIXCODE_ALERT_WEBHOOK_FORMAT=pagerduty
EOF_ENV

if output="$("${ROOT_DIR}/scripts/probe-production-health.sh" "${unsupported_format_env}" 2>&1)"; then
  echo "断言失败：健康探测应拒绝不支持的告警平台格式" >&2
  echo "${output}" >&2
  exit 1
fi
assert_contains "${output}" "MATRIXCODE_ALERT_WEBHOOK_FORMAT 只支持"

rm -f "${TMP_DIR}/alert-count" "${TMP_DIR}/alert-payload.json" "${TMP_DIR}/alert-headers.txt"
dingtalk_env="${TMP_DIR}/dingtalk.env"
write_env "${dingtalk_env}" true "file://${down_file}"
cat >>"${dingtalk_env}" <<EOF_ENV
MATRIXCODE_ALERT_WEBHOOK_URL=https://alert.example/dingtalk
MATRIXCODE_ALERT_WEBHOOK_FORMAT=dingtalk
MATRIXCODE_ALERT_SEVERITY=P2
MATRIXCODE_ALERT_ESCALATION_OWNER=ops-primary
MATRIXCODE_ALERT_RUNBOOK_URL=https://runbook.example/matrixcode
EOF_ENV

if output="$(PATH="${fake_bin}:${PATH}" "${ROOT_DIR}/scripts/probe-production-health.sh" "${dingtalk_env}" 2>&1)"; then
  echo "断言失败：健康探测应在 DOWN 响应后返回失败" >&2
  echo "${output}" >&2
  exit 1
fi
assert_contains "$(cat "${TMP_DIR}/alert-payload.json")" '"msgtype":"text"'
assert_contains "$(cat "${TMP_DIR}/alert-payload.json")" '"text":{"content":"'
assert_contains "$(cat "${TMP_DIR}/alert-payload.json")" '级别：P2'
assert_contains "$(cat "${TMP_DIR}/alert-payload.json")" '值班：ops-primary'
assert_contains "$(cat "${TMP_DIR}/alert-payload.json")" '手册：https://runbook.example/matrixcode'

rm -f "${TMP_DIR}/alert-count" "${TMP_DIR}/alert-payload.json" "${TMP_DIR}/alert-headers.txt"
feishu_env="${TMP_DIR}/feishu.env"
write_env "${feishu_env}" true "file://${down_file}"
cat >>"${feishu_env}" <<EOF_ENV
MATRIXCODE_ALERT_WEBHOOK_URL=https://alert.example/feishu
MATRIXCODE_ALERT_WEBHOOK_FORMAT=feishu
MATRIXCODE_ALERT_SEVERITY=P1
EOF_ENV

if output="$(PATH="${fake_bin}:${PATH}" "${ROOT_DIR}/scripts/probe-production-health.sh" "${feishu_env}" 2>&1)"; then
  echo "断言失败：健康探测应在 DOWN 响应后返回失败" >&2
  echo "${output}" >&2
  exit 1
fi
assert_contains "$(cat "${TMP_DIR}/alert-payload.json")" '"msg_type":"text"'
assert_contains "$(cat "${TMP_DIR}/alert-payload.json")" '"content":{"text":"'
assert_contains "$(cat "${TMP_DIR}/alert-payload.json")" '级别：P1'

echo "生产健康探测脚本测试通过。"
