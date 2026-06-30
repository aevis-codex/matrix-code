#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"

fail() {
  echo "JDBC 快照边界验证失败：$1" >&2
  exit 1
}

assert_contains() {
  local path="$1"
  local text="$2"
  grep -Fq -- "${text}" "${ROOT_DIR}/${path}" || fail "${path} 缺少 ${text}"
}

assert_contains server/src/main/resources/application.yml 'legacy-snapshot-writes-enabled: ${MATRIXCODE_PERSISTENCE_JDBC_LEGACY_SNAPSHOT_WRITES_ENABLED:false}'
assert_contains server/src/main/java/com/matrixcode/persistence/application/PersistenceModeProperties.java 'legacySnapshotWritesEnabled'
assert_contains server/src/main/java/com/matrixcode/persistence/application/JdbcWorkbenchStateStore.java 'if (!legacySnapshotWritesEnabled)'
assert_contains server/src/test/java/com/matrixcode/persistence/JdbcWorkbenchStateStoreTest.java '默认关闭历史Workbench快照写入'
assert_contains server/src/test/java/com/matrixcode/persistence/JdbcPersistenceSpringTest.java '.doesNotContain("workbench-state", "runtime-notifications", "local-execution")'
assert_contains .env.example 'MATRIXCODE_PERSISTENCE_JDBC_LEGACY_SNAPSHOT_WRITES_ENABLED=false'
assert_contains ops/env/matrixcode.env.example 'MATRIXCODE_PERSISTENCE_JDBC_LEGACY_SNAPSHOT_WRITES_ENABLED=false'
assert_contains docs/development/local-run.md '第 150 阶段后，JDBC 主路径不再向 `matrixcode_state_snapshots` 写入'

echo "JDBC 快照边界验证通过。"
