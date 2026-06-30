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
printf 'name=matrixcode-test\n' >"${TMP_DIR}/matrixcode-test.manifest"

if output="$(MATRIXCODE_REMOTE_RELEASE_DRY_RUN=true MATRIXCODE_REMOTE_RELEASE_TARGET=deploy@example.com MATRIXCODE_REMOTE_RELEASE_DIR=/ "${ROOT_DIR}/scripts/upload-production-release.sh" "${archive}" 2>&1)"; then
  echo "断言失败：远程复制脚本应拒绝危险远端目录" >&2
  echo "${output}" >&2
  exit 1
fi
assert_contains "${output}" "远端目录不安全"

missing_checksum="${TMP_DIR}/missing-checksum.tar.gz"
cp "${archive}" "${missing_checksum}"
if output="$(MATRIXCODE_REMOTE_RELEASE_DRY_RUN=true MATRIXCODE_REMOTE_RELEASE_TARGET=deploy@example.com "${ROOT_DIR}/scripts/upload-production-release.sh" "${missing_checksum}" 2>&1)"; then
  echo "断言失败：远程复制脚本应拒绝缺少 sha256 的归档" >&2
  echo "${output}" >&2
  exit 1
fi
assert_contains "${output}" "缺少校验文件"

output="$(MATRIXCODE_REMOTE_RELEASE_DRY_RUN=true MATRIXCODE_REMOTE_RELEASE_TARGET=deploy@example.com MATRIXCODE_REMOTE_RELEASE_DIR=/srv/matrixcode/releases "${ROOT_DIR}/scripts/upload-production-release.sh" "${archive}" 2>&1)"
assert_contains "${output}" "生产发布远程复制 dry-run 通过"
assert_contains "${output}" "deploy@example.com:/srv/matrixcode/releases"

if output="$(MATRIXCODE_REMOTE_RELEASE_TARGET=deploy@example.com MATRIXCODE_REMOTE_RELEASE_DIR=/srv/matrixcode/releases "${ROOT_DIR}/scripts/upload-production-release.sh" "${archive}" 2>&1)"; then
  echo "断言失败：真实远程复制必须显式确认" >&2
  echo "${output}" >&2
  exit 1
fi
assert_contains "${output}" "MATRIXCODE_REMOTE_RELEASE_CONFIRM=true"

fake_bin="${TMP_DIR}/bin"
mkdir -p "${fake_bin}"
cat >"${fake_bin}/ssh" <<'EOF_SSH'
#!/usr/bin/env bash
echo "ssh $*" >>"${MATRIXCODE_FAKE_REMOTE_LOG}"
EOF_SSH
cat >"${fake_bin}/scp" <<'EOF_SCP'
#!/usr/bin/env bash
echo "scp $*" >>"${MATRIXCODE_FAKE_REMOTE_LOG}"
EOF_SCP
chmod +x "${fake_bin}/ssh" "${fake_bin}/scp"

remote_log="${TMP_DIR}/remote.log"
output="$(MATRIXCODE_FAKE_REMOTE_LOG="${remote_log}" PATH="${fake_bin}:${PATH}" MATRIXCODE_REMOTE_RELEASE_CONFIRM=true MATRIXCODE_REMOTE_RELEASE_TARGET=deploy@example.com MATRIXCODE_REMOTE_RELEASE_DIR=/srv/matrixcode/releases "${ROOT_DIR}/scripts/upload-production-release.sh" "${archive}" 2>&1)"
assert_contains "${output}" "生产发布远程复制完成"
assert_file_exists "${remote_log}"
remote_commands="$(cat "${remote_log}")"
assert_contains "${remote_commands}" "ssh deploy@example.com"
assert_contains "${remote_commands}" "scp"
assert_contains "${remote_commands}" "matrixcode-test.tar.gz"
assert_contains "${remote_commands}" "matrixcode-test.tar.gz.sha256"
assert_contains "${remote_commands}" "matrixcode-test.manifest"

echo "生产发布远程复制脚本测试通过。"
