#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
TMP_DIR="$(mktemp -d)"
trap 'rm -rf "${TMP_DIR}"' EXIT

assert_contains() {
  local haystack="$1"
  local needle="$2"
  if [[ "${haystack}" != *"${needle}"* ]]; then
    echo "断言失败：输出中缺少 ${needle}" >&2
    echo "${haystack}" >&2
    exit 1
  fi
}

if output="$(MATRIXCODE_RESTART_DRY_RUN=true MATRIXCODE_SYSTEMD_SERVICE="bad;name" "${ROOT_DIR}/scripts/restart-production-services.sh" 2>&1)"; then
  echo "断言失败：重启脚本应拒绝不安全服务名" >&2
  echo "${output}" >&2
  exit 1
fi
assert_contains "${output}" "服务名包含不安全字符"

output="$(MATRIXCODE_RESTART_DRY_RUN=true "${ROOT_DIR}/scripts/restart-production-services.sh" 2>&1)"
assert_contains "${output}" "生产服务重启 dry-run 通过"
assert_contains "${output}" "matrixcode"

if output="$("${ROOT_DIR}/scripts/restart-production-services.sh" 2>&1)"; then
  echo "断言失败：真实重启必须显式确认" >&2
  echo "${output}" >&2
  exit 1
fi
assert_contains "${output}" "MATRIXCODE_RESTART_CONFIRM=true"

fake_bin="${TMP_DIR}/bin"
mkdir -p "${fake_bin}"
cat >"${fake_bin}/systemctl" <<'EOF_SYSTEMCTL'
#!/usr/bin/env bash
echo "systemctl $*" >>"${MATRIXCODE_FAKE_RESTART_LOG}"
EOF_SYSTEMCTL
cat >"${fake_bin}/curl" <<'EOF_CURL'
#!/usr/bin/env bash
echo "curl $*" >>"${MATRIXCODE_FAKE_RESTART_LOG}"
printf '{"status":"UP"}\n'
EOF_CURL
chmod +x "${fake_bin}/systemctl" "${fake_bin}/curl"

restart_log="${TMP_DIR}/restart.log"
output="$(MATRIXCODE_FAKE_RESTART_LOG="${restart_log}" PATH="${fake_bin}:${PATH}" MATRIXCODE_RESTART_CONFIRM=true MATRIXCODE_RELOAD_NGINX=true "${ROOT_DIR}/scripts/restart-production-services.sh" 2>&1)"
assert_contains "${output}" "生产服务重启完成"
restart_commands="$(cat "${restart_log}")"
assert_contains "${restart_commands}" "systemctl restart matrixcode"
assert_contains "${restart_commands}" "curl -fsS"
assert_contains "${restart_commands}" "systemctl reload nginx"

echo "生产服务重启脚本测试通过。"
