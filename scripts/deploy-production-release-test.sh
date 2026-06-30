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

release_root="${TMP_DIR}/release-root"
mkdir -p "${release_root}"
printf 'release\n' >"${release_root}/matrixcode-server.jar"

archive="${TMP_DIR}/matrixcode-test.tar.gz"
tar -czf "${archive}" -C "${release_root}" .
if command -v sha256sum >/dev/null 2>&1; then
  sha256sum "${archive}" >"${archive}.sha256"
else
  shasum -a 256 "${archive}" >"${archive}.sha256"
fi

if output="$(MATRIXCODE_DEPLOY_DRY_RUN=true MATRIXCODE_REMOTE_RELEASE_TARGET=deploy@example.com MATRIXCODE_REMOTE_APP_DIR=/ "${ROOT_DIR}/scripts/deploy-production-release.sh" "${archive}" 2>&1)"; then
  echo "断言失败：远程发布编排应拒绝危险应用目录" >&2
  echo "${output}" >&2
  exit 1
fi
assert_contains "${output}" "应用目录不安全"

output="$(MATRIXCODE_DEPLOY_DRY_RUN=true MATRIXCODE_REMOTE_RELEASE_TARGET=deploy@example.com MATRIXCODE_REMOTE_RELEASE_DIR=/srv/matrixcode/releases MATRIXCODE_REMOTE_APP_DIR=/srv/matrixcode/app "${ROOT_DIR}/scripts/deploy-production-release.sh" "${archive}" 2>&1)"
assert_contains "${output}" "生产发布远程复制 dry-run 通过"
assert_contains "${output}" "远程安装 dry-run"
assert_contains "${output}" "远程重启 dry-run"
assert_contains "${output}" "远程健康快照 dry-run"

if output="$(MATRIXCODE_REMOTE_RELEASE_TARGET=deploy@example.com MATRIXCODE_REMOTE_RELEASE_DIR=/srv/matrixcode/releases MATRIXCODE_REMOTE_APP_DIR=/srv/matrixcode/app "${ROOT_DIR}/scripts/deploy-production-release.sh" "${archive}" 2>&1)"; then
  echo "断言失败：真实远程发布编排必须显式确认" >&2
  echo "${output}" >&2
  exit 1
fi
assert_contains "${output}" "MATRIXCODE_DEPLOY_CONFIRM=true"

fake_bin="${TMP_DIR}/bin"
mkdir -p "${fake_bin}"
cat >"${fake_bin}/ssh" <<'EOF_SSH'
#!/usr/bin/env bash
echo "ssh $*" >>"${MATRIXCODE_FAKE_DEPLOY_LOG}"
EOF_SSH
cat >"${fake_bin}/scp" <<'EOF_SCP'
#!/usr/bin/env bash
echo "scp $*" >>"${MATRIXCODE_FAKE_DEPLOY_LOG}"
EOF_SCP
chmod +x "${fake_bin}/ssh" "${fake_bin}/scp"

deploy_log="${TMP_DIR}/deploy.log"
output="$(MATRIXCODE_FAKE_DEPLOY_LOG="${deploy_log}" PATH="${fake_bin}:${PATH}" MATRIXCODE_DEPLOY_CONFIRM=true MATRIXCODE_REMOTE_RELEASE_TARGET=deploy@example.com MATRIXCODE_REMOTE_RELEASE_DIR=/srv/matrixcode/releases MATRIXCODE_REMOTE_APP_DIR=/srv/matrixcode/app MATRIXCODE_REMOTE_HEALTH_PROBE_URL=http://127.0.0.1:8080/actuator/health "${ROOT_DIR}/scripts/deploy-production-release.sh" "${archive}" 2>&1)"
assert_contains "${output}" "生产发布编排完成"
assert_file_exists "${deploy_log}"
deploy_commands="$(cat "${deploy_log}")"
assert_contains "${deploy_commands}" "ssh deploy@example.com"
assert_contains "${deploy_commands}" "scp"
assert_contains "${deploy_commands}" "install-production-release.sh"
assert_contains "${deploy_commands}" "restart-production-services.sh"
assert_contains "${deploy_commands}" "capture-production-health-snapshot.sh"
assert_contains "${deploy_commands}" "/var/log/matrixcode/health-snapshots"
assert_contains "${deploy_commands}" "matrixcode-test.tar.gz"

echo "生产发布远程编排脚本测试通过。"
