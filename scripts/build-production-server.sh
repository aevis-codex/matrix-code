#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
DIST_DIR="${MATRIXCODE_DIST_DIR:-${ROOT_DIR}/dist/matrixcode-server}"
MAVEN_BIN="${MATRIXCODE_MAVEN_BIN:-/Users/Masons/Ai/Maven/bin/mvn}"
MAVEN_REPO="${MATRIXCODE_MAVEN_REPO:-/Users/Masons/Ai/Maven_Ai_Store}"

mkdir -p \
  "${DIST_DIR}/bin" \
  "${DIST_DIR}/scripts" \
  "${DIST_DIR}/ops/systemd" \
  "${DIST_DIR}/ops/nginx" \
  "${DIST_DIR}/ops/env" \
  "${DIST_DIR}/desktop"

"${MAVEN_BIN}" \
  -Dmaven.repo.local="${MAVEN_REPO}" \
  -pl server \
  -DskipTests \
  package

jar_candidates=("${ROOT_DIR}"/server/target/matrixcode-server-*.jar)
if [[ ! -f "${jar_candidates[0]}" ]]; then
  echo "未找到服务端 Jar，请先确认 Maven package 是否成功。"
  exit 1
fi

install -m 0644 "${jar_candidates[0]}" "${DIST_DIR}/matrixcode-server.jar"
install -m 0755 "${ROOT_DIR}/ops/bin/run-matrixcode-server.sh" "${DIST_DIR}/bin/run-matrixcode-server.sh"
install -m 0755 "${ROOT_DIR}/scripts/check-real-runtime.sh" "${DIST_DIR}/scripts/check-real-runtime.sh"
install -m 0755 "${ROOT_DIR}/scripts/backup-production-mysql.sh" "${DIST_DIR}/scripts/backup-production-mysql.sh"
install -m 0755 "${ROOT_DIR}/scripts/prune-production-mysql-backups.sh" "${DIST_DIR}/scripts/prune-production-mysql-backups.sh"
install -m 0755 "${ROOT_DIR}/scripts/run-production-mysql-backup.sh" "${DIST_DIR}/scripts/run-production-mysql-backup.sh"
install -m 0755 "${ROOT_DIR}/scripts/sync-production-mysql-backups.sh" "${DIST_DIR}/scripts/sync-production-mysql-backups.sh"
install -m 0755 "${ROOT_DIR}/scripts/probe-production-health.sh" "${DIST_DIR}/scripts/probe-production-health.sh"
install -m 0755 "${ROOT_DIR}/scripts/capture-production-health-snapshot.sh" "${DIST_DIR}/scripts/capture-production-health-snapshot.sh"
install -m 0755 "${ROOT_DIR}/scripts/install-production-release.sh" "${DIST_DIR}/scripts/install-production-release.sh"
install -m 0755 "${ROOT_DIR}/scripts/upload-production-release.sh" "${DIST_DIR}/scripts/upload-production-release.sh"
install -m 0755 "${ROOT_DIR}/scripts/deploy-production-release.sh" "${DIST_DIR}/scripts/deploy-production-release.sh"
install -m 0755 "${ROOT_DIR}/scripts/verify-remote-release-target.sh" "${DIST_DIR}/scripts/verify-remote-release-target.sh"
install -m 0755 "${ROOT_DIR}/scripts/restart-production-services.sh" "${DIST_DIR}/scripts/restart-production-services.sh"
install -m 0755 "${ROOT_DIR}/scripts/rollback-production-release.sh" "${DIST_DIR}/scripts/rollback-production-release.sh"
install -m 0755 "${ROOT_DIR}/scripts/smoke-production-release.sh" "${DIST_DIR}/scripts/smoke-production-release.sh"
install -m 0755 "${ROOT_DIR}/scripts/generate-production-release-candidate.sh" "${DIST_DIR}/scripts/generate-production-release-candidate.sh"
install -m 0644 "${ROOT_DIR}/ops/systemd/matrixcode.service" "${DIST_DIR}/ops/systemd/matrixcode.service"
install -m 0644 "${ROOT_DIR}/ops/systemd/matrixcode-mysql-backup.service" "${DIST_DIR}/ops/systemd/matrixcode-mysql-backup.service"
install -m 0644 "${ROOT_DIR}/ops/systemd/matrixcode-mysql-backup.timer" "${DIST_DIR}/ops/systemd/matrixcode-mysql-backup.timer"
install -m 0644 "${ROOT_DIR}/ops/systemd/matrixcode-health-probe.service" "${DIST_DIR}/ops/systemd/matrixcode-health-probe.service"
install -m 0644 "${ROOT_DIR}/ops/systemd/matrixcode-health-probe.timer" "${DIST_DIR}/ops/systemd/matrixcode-health-probe.timer"
install -m 0644 "${ROOT_DIR}/ops/nginx/matrixcode.conf" "${DIST_DIR}/ops/nginx/matrixcode.conf"
install -m 0644 "${ROOT_DIR}/ops/nginx/matrixcode-https.conf" "${DIST_DIR}/ops/nginx/matrixcode-https.conf"
install -m 0644 "${ROOT_DIR}/ops/env/matrixcode.env.example" "${DIST_DIR}/ops/env/matrixcode.env.example"

if [[ "${MATRIXCODE_BUILD_DESKTOP:-true}" == "true" ]]; then
  npm --prefix "${ROOT_DIR}/desktop" run build
fi

if [[ -d "${ROOT_DIR}/desktop/dist" ]]; then
  cp -R "${ROOT_DIR}/desktop/dist/." "${DIST_DIR}/desktop/"
fi

echo "生产发布目录已生成：${DIST_DIR}"
