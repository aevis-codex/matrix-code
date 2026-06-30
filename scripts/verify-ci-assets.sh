#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
WORKFLOW="${ROOT_DIR}/.github/workflows/ci.yml"

fail() {
  echo "CI 资产验证失败：$1" >&2
  exit 1
}

assert_contains() {
  local text="$1"
  grep -Fq -- "${text}" "${WORKFLOW}" || fail "ci.yml 缺少 ${text}"
}

[[ -f "${WORKFLOW}" ]] || fail "缺少 .github/workflows/ci.yml"

assert_contains 'branches:'
assert_contains '- master'
assert_contains 'Server tests'
assert_contains 'actions/setup-java@v4'
assert_contains 'java-version: "21"'
assert_contains 'mvn -B -pl server test'
assert_contains 'Desktop tests and build'
assert_contains 'actions/setup-node@v4'
assert_contains 'node-version: "22"'
assert_contains 'npm ci'
assert_contains 'npm test'
assert_contains 'npm run build'
assert_contains 'Script gates'
assert_contains 'bash scripts/check-real-runtime-production-test.sh'
assert_contains 'bash scripts/run-production-local-test.sh'
assert_contains 'bash scripts/verify-production-deployment-assets.sh'
assert_contains 'bash scripts/backup-production-mysql-test.sh'
assert_contains 'bash scripts/prune-production-mysql-backups-test.sh'
assert_contains 'bash scripts/run-production-mysql-backup-test.sh'
assert_contains 'bash scripts/probe-production-health-test.sh'
assert_contains 'bash scripts/package-production-release-test.sh'
assert_contains 'bash scripts/install-production-release-test.sh'
assert_contains 'bash scripts/verify-tls-assets.sh'
assert_contains 'bash scripts/verify-production-readiness.sh'
assert_contains 'bash scripts/verify-ci-assets.sh'

echo "CI 资产验证通过。"
