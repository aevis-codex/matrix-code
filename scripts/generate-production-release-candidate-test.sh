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

[[ -x "${ROOT_DIR}/scripts/generate-production-release-candidate.sh" ]] || {
  echo "断言失败：缺少生产发布候选清单脚本" >&2
  exit 1
}

payload_dir="${TMP_DIR}/payload"
mkdir -p "${payload_dir}"
echo "matrixcode" >"${payload_dir}/matrixcode-server.jar"
archive_file="${TMP_DIR}/matrixcode-rc.tar.gz"
tar -czf "${archive_file}" -C "${payload_dir}" .

if command -v sha256sum >/dev/null 2>&1; then
  sha256sum "${archive_file}" >"${archive_file}.sha256"
else
  shasum -a 256 "${archive_file}" >"${archive_file}.sha256"
fi

cat >"${TMP_DIR}/matrixcode-rc.manifest" <<EOF_MANIFEST
name=matrixcode-rc
git_commit=abc123
archive=${archive_file}
EOF_MANIFEST

env_file="${TMP_DIR}/matrixcode.env"
cat >"${env_file}" <<EOF_ENV
MATRIXCODE_MYSQL_HOST=127.0.0.1
EOF_ENV

if output="$("${ROOT_DIR}/scripts/generate-production-release-candidate.sh" 2>&1)"; then
  echo "断言失败：候选清单脚本应拒绝缺少发布归档路径" >&2
  echo "${output}" >&2
  exit 1
fi
assert_contains "${output}" "缺少发布归档路径"

output="$(MATRIXCODE_RELEASE_CANDIDATE_SKIP_CHECKS=true "${ROOT_DIR}/scripts/generate-production-release-candidate.sh" "${archive_file}" "${env_file}" 2>&1)"
assert_contains "${output}" "生产发布候选清单已生成"

report_file="${TMP_DIR}/matrixcode-rc.release-candidate.md"
assert_file_exists "${report_file}"
report="$(cat "${report_file}")"
assert_contains "${report}" "MatrixCode 生产发布候选验收报告"
assert_contains "${report}" "matrixcode-rc.tar.gz"
assert_contains "${report}" "生产就绪聚合门禁 | SKIPPED"
assert_contains "${report}" "发布包 smoke | SKIPPED"
assert_contains "${report}" "真实协议级检查 | SKIPPED"
assert_contains "${report}" "远程发布目标预检 | SKIPPED"
assert_contains "${report}" "远程发布 dry-run | SKIPPED"
assert_contains "${report}" "abc123"

echo "生产发布候选清单脚本测试通过。"
