#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
DIST_DIR="${MATRIXCODE_DIST_DIR:-${ROOT_DIR}/dist/matrixcode-server}"
RELEASE_DIR="${MATRIXCODE_RELEASE_DIR:-${ROOT_DIR}/dist/releases}"
RELEASE_NAME="${MATRIXCODE_RELEASE_NAME:-matrixcode-server-$(date +%Y%m%d%H%M%S)}"

is_true() {
  [[ "${1:-}" == "true" || "${1:-}" == "TRUE" || "${1:-}" == "1" ]]
}

fail() {
  echo "生产发布归档错误：$1" >&2
  exit 1
}

assert_file() {
  local path="$1"
  [[ -f "${DIST_DIR}/${path}" ]] || fail "发布目录缺少 ${path}"
}

if [[ ! "${RELEASE_NAME}" =~ ^[A-Za-z0-9._-]+$ ]]; then
  fail "MATRIXCODE_RELEASE_NAME 只能包含字母、数字、点、下划线和中划线"
fi

if ! is_true "${MATRIXCODE_RELEASE_SKIP_BUILD:-false}"; then
  bash "${ROOT_DIR}/scripts/verify-production-deployment-assets.sh"
  bash "${ROOT_DIR}/scripts/verify-tls-assets.sh"
  bash "${ROOT_DIR}/scripts/build-production-server.sh"
fi

[[ -d "${DIST_DIR}" ]] || fail "发布目录不存在：${DIST_DIR}"
assert_file matrixcode-server.jar
assert_file bin/run-matrixcode-server.sh
assert_file scripts/check-real-runtime.sh
assert_file scripts/backup-production-mysql.sh
assert_file scripts/prune-production-mysql-backups.sh
assert_file scripts/run-production-mysql-backup.sh
assert_file scripts/sync-production-mysql-backups.sh
assert_file scripts/probe-production-health.sh
assert_file scripts/capture-production-health-snapshot.sh
assert_file scripts/install-production-release.sh
assert_file scripts/upload-production-release.sh
assert_file scripts/deploy-production-release.sh
assert_file scripts/verify-remote-release-target.sh
assert_file scripts/restart-production-services.sh
assert_file scripts/rollback-production-release.sh
assert_file scripts/smoke-production-release.sh
assert_file scripts/generate-production-release-candidate.sh
assert_file ops/systemd/matrixcode.service
assert_file ops/systemd/matrixcode-mysql-backup.service
assert_file ops/systemd/matrixcode-mysql-backup.timer
assert_file ops/systemd/matrixcode-health-probe.service
assert_file ops/systemd/matrixcode-health-probe.timer
assert_file ops/nginx/matrixcode.conf
assert_file ops/nginx/matrixcode-https.conf
assert_file ops/env/matrixcode.env.example

mkdir -p "${RELEASE_DIR}"

archive_file="${RELEASE_DIR}/${RELEASE_NAME}.tar.gz"
manifest_file="${RELEASE_DIR}/${RELEASE_NAME}.manifest"
checksum_file="${archive_file}.sha256"

if [[ -e "${archive_file}" || -e "${manifest_file}" || -e "${checksum_file}" ]]; then
  fail "发布归档已存在：${RELEASE_NAME}"
fi

commit_id="$(git -C "${ROOT_DIR}" rev-parse --short HEAD 2>/dev/null || echo unknown)"
created_at="$(date -u +%Y-%m-%dT%H:%M:%SZ)"

tar -czf "${archive_file}" -C "${DIST_DIR}" .
chmod 600 "${archive_file}"

if command -v sha256sum >/dev/null 2>&1; then
  sha256sum "${archive_file}" >"${checksum_file}"
elif command -v shasum >/dev/null 2>&1; then
  shasum -a 256 "${archive_file}" >"${checksum_file}"
else
  fail "未找到 sha256sum 或 shasum"
fi
chmod 600 "${checksum_file}"

cat >"${manifest_file}" <<EOF_MANIFEST
name=${RELEASE_NAME}
created_at=${created_at}
git_commit=${commit_id}
dist_dir=${DIST_DIR}
archive=${archive_file}
checksum=${checksum_file}
EOF_MANIFEST
chmod 600 "${manifest_file}"

echo "生产发布归档完成：${archive_file}"
