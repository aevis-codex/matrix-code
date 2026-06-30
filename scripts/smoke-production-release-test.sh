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
mkdir -p "${release_root}/bin" "${release_root}/scripts"
touch "${release_root}/matrixcode-server.jar"
cp "${ROOT_DIR}/ops/bin/run-matrixcode-server.sh" "${release_root}/bin/run-matrixcode-server.sh"
cat >"${release_root}/scripts/check-real-runtime.sh" <<'EOF_CHECK'
#!/usr/bin/env bash
echo "fake preflight ok"
echo "真实运行配置检查通过。"
EOF_CHECK
chmod +x "${release_root}/bin/run-matrixcode-server.sh" "${release_root}/scripts/check-real-runtime.sh"

archive="${TMP_DIR}/matrixcode-smoke.tar.gz"
tar -czf "${archive}" -C "${release_root}" .
if command -v sha256sum >/dev/null 2>&1; then
  sha256sum "${archive}" >"${archive}.sha256"
else
  shasum -a 256 "${archive}" >"${archive}.sha256"
fi

env_file="${TMP_DIR}/matrixcode.env"
cat >"${env_file}" <<'EOF_ENV'
MATRIXCODE_RUN_PREFLIGHT=true
MATRIXCODE_PRODUCTION_DRY_RUN=false
EOF_ENV

output="$("${ROOT_DIR}/scripts/smoke-production-release.sh" "${archive}" "${env_file}" 2>&1)"
assert_contains "${output}" "fake preflight ok"
assert_contains "${output}" "MatrixCode 生产 Jar 启动干运行通过。"
assert_contains "${output}" "生产发布包 smoke 通过"

extract_dir="${TMP_DIR}/extract-check"
mkdir -p "${extract_dir}"
tar -xzf "${archive}" -C "${extract_dir}"
assert_file_exists "${extract_dir}/matrixcode-server.jar"
assert_file_exists "${extract_dir}/bin/run-matrixcode-server.sh"
assert_file_exists "${extract_dir}/scripts/check-real-runtime.sh"

echo "生产发布包 smoke 脚本测试通过。"
