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

fake_dist="${TMP_DIR}/matrixcode-server"
mkdir -p \
  "${fake_dist}/bin" \
  "${fake_dist}/scripts" \
  "${fake_dist}/ops/systemd" \
  "${fake_dist}/ops/nginx" \
  "${fake_dist}/ops/env"
touch \
  "${fake_dist}/matrixcode-server.jar" \
  "${fake_dist}/bin/run-matrixcode-server.sh" \
  "${fake_dist}/scripts/check-real-runtime.sh" \
  "${fake_dist}/scripts/backup-production-mysql.sh" \
  "${fake_dist}/scripts/prune-production-mysql-backups.sh" \
  "${fake_dist}/scripts/run-production-mysql-backup.sh" \
  "${fake_dist}/scripts/sync-production-mysql-backups.sh" \
  "${fake_dist}/scripts/probe-production-health.sh" \
  "${fake_dist}/scripts/capture-production-health-snapshot.sh" \
  "${fake_dist}/scripts/install-production-release.sh" \
  "${fake_dist}/scripts/upload-production-release.sh" \
  "${fake_dist}/scripts/deploy-production-release.sh" \
  "${fake_dist}/scripts/verify-remote-release-target.sh" \
  "${fake_dist}/scripts/restart-production-services.sh" \
  "${fake_dist}/scripts/rollback-production-release.sh" \
  "${fake_dist}/scripts/smoke-production-release.sh" \
  "${fake_dist}/scripts/generate-production-release-candidate.sh" \
  "${fake_dist}/ops/systemd/matrixcode.service" \
  "${fake_dist}/ops/systemd/matrixcode-mysql-backup.service" \
  "${fake_dist}/ops/systemd/matrixcode-mysql-backup.timer" \
  "${fake_dist}/ops/systemd/matrixcode-health-probe.service" \
  "${fake_dist}/ops/systemd/matrixcode-health-probe.timer" \
  "${fake_dist}/ops/nginx/matrixcode.conf" \
  "${fake_dist}/ops/nginx/matrixcode-https.conf" \
  "${fake_dist}/ops/env/matrixcode.env.example"

release_dir="${TMP_DIR}/releases"
if output="$(MATRIXCODE_RELEASE_SKIP_BUILD=true MATRIXCODE_DIST_DIR="${fake_dist}" MATRIXCODE_RELEASE_DIR="${release_dir}" MATRIXCODE_RELEASE_NAME="../bad" "${ROOT_DIR}/scripts/package-production-release.sh" 2>&1)"; then
  echo "断言失败：发布归档脚本应拒绝不安全 release 名称" >&2
  echo "${output}" >&2
  exit 1
fi
assert_contains "${output}" "只能包含字母"

output="$(MATRIXCODE_RELEASE_SKIP_BUILD=true MATRIXCODE_DIST_DIR="${fake_dist}" MATRIXCODE_RELEASE_DIR="${release_dir}" MATRIXCODE_RELEASE_NAME="matrixcode-test" "${ROOT_DIR}/scripts/package-production-release.sh" 2>&1)"
assert_contains "${output}" "生产发布归档完成"
assert_file_exists "${release_dir}/matrixcode-test.tar.gz"
assert_file_exists "${release_dir}/matrixcode-test.tar.gz.sha256"
assert_file_exists "${release_dir}/matrixcode-test.manifest"

manifest="$(cat "${release_dir}/matrixcode-test.manifest")"
assert_contains "${manifest}" "name=matrixcode-test"
assert_contains "${manifest}" "git_commit="
assert_contains "${manifest}" "archive=${release_dir}/matrixcode-test.tar.gz"

if output="$(MATRIXCODE_RELEASE_SKIP_BUILD=true MATRIXCODE_DIST_DIR="${fake_dist}" MATRIXCODE_RELEASE_DIR="${release_dir}" MATRIXCODE_RELEASE_NAME="matrixcode-test" "${ROOT_DIR}/scripts/package-production-release.sh" 2>&1)"; then
  echo "断言失败：发布归档脚本应拒绝覆盖已有归档" >&2
  echo "${output}" >&2
  exit 1
fi
assert_contains "${output}" "发布归档已存在"

echo "生产发布归档脚本测试通过。"
