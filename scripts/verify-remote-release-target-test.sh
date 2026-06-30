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

assert_file_exists() {
  local path="$1"
  [[ -f "${path}" ]] || {
    echo "断言失败：文件应存在 ${path}" >&2
    exit 1
  }
}

[[ -x "${ROOT_DIR}/scripts/verify-remote-release-target.sh" ]] || {
  echo "断言失败：缺少远程发布目标预检脚本" >&2
  exit 1
}

if output="$("${ROOT_DIR}/scripts/verify-remote-release-target.sh" 2>&1)"; then
  echo "断言失败：远程发布目标预检应拒绝空目标" >&2
  echo "${output}" >&2
  exit 1
fi
assert_contains "${output}" "MATRIXCODE_REMOTE_RELEASE_TARGET 不能为空"

if output="$(MATRIXCODE_REMOTE_RELEASE_TARGET=deploy@example.com MATRIXCODE_REMOTE_APP_DIR=/ "${ROOT_DIR}/scripts/verify-remote-release-target.sh" 2>&1)"; then
  echo "断言失败：远程发布目标预检应拒绝危险应用目录" >&2
  echo "${output}" >&2
  exit 1
fi
assert_contains "${output}" "应用目录不安全"

output="$(MATRIXCODE_REMOTE_RELEASE_TARGET=deploy@example.com MATRIXCODE_REMOTE_RELEASE_DIR=/srv/matrixcode/releases MATRIXCODE_REMOTE_APP_DIR=/srv/matrixcode/app "${ROOT_DIR}/scripts/verify-remote-release-target.sh" 2>&1)"
assert_contains "${output}" "远程发布目标预检通过"
assert_contains "${output}" "SSH 连接检查：SKIPPED"
assert_contains "${output}" "远端环境文件：/etc/matrixcode/matrixcode.env"
assert_contains "${output}" "远端健康快照目录：/var/log/matrixcode/health-snapshots"

fake_bin="${TMP_DIR}/bin"
mkdir -p "${fake_bin}"
cat >"${fake_bin}/ssh" <<'EOF_SSH'
#!/usr/bin/env bash
echo "ssh $*" >>"${MATRIXCODE_FAKE_REMOTE_PREFLIGHT_LOG}"
EOF_SSH
chmod +x "${fake_bin}/ssh"

preflight_log="${TMP_DIR}/remote-preflight.log"
output="$(MATRIXCODE_FAKE_REMOTE_PREFLIGHT_LOG="${preflight_log}" PATH="${fake_bin}:${PATH}" MATRIXCODE_REMOTE_RELEASE_PREFLIGHT_CONNECT=true MATRIXCODE_REMOTE_RELEASE_TARGET=deploy@example.com MATRIXCODE_REMOTE_RELEASE_DIR=/srv/matrixcode/releases MATRIXCODE_REMOTE_APP_DIR=/srv/matrixcode/app "${ROOT_DIR}/scripts/verify-remote-release-target.sh" 2>&1)"
assert_contains "${output}" "远程 SSH 预检通过"
assert_file_exists "${preflight_log}"
preflight_commands="$(cat "${preflight_log}")"
assert_contains "${preflight_commands}" "ssh"
assert_contains "${preflight_commands}" "command -v tar"
assert_contains "${preflight_commands}" "command -v systemctl"
assert_contains "${preflight_commands}" "command -v curl"
assert_contains "${preflight_commands}" "capture-production-health-snapshot.sh"
assert_contains "${preflight_commands}" "test -r"
assert_contains "${preflight_commands}" "/etc/matrixcode/matrixcode.env"
assert_contains "${preflight_commands}" "test -w"
assert_contains "${preflight_commands}" "/var/log/matrixcode/health-snapshots"

echo "远程发布目标预检脚本测试通过。"
