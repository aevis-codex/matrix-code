#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "${ROOT_DIR}"

echo "1/22 检查 shell 语法"
bash -n scripts/*.sh ops/bin/run-matrixcode-server.sh

echo "2/22 检查生产上线门禁脚本"
bash scripts/check-real-runtime-production-test.sh

echo "3/22 检查生产启动脚本"
bash scripts/run-production-local-test.sh

echo "4/22 检查 MySQL 备份脚本"
bash scripts/backup-production-mysql-test.sh

echo "5/22 检查 MySQL 备份轮转脚本"
bash scripts/prune-production-mysql-backups-test.sh

echo "6/22 检查 MySQL 定时备份任务"
bash scripts/run-production-mysql-backup-test.sh

echo "7/22 检查 MySQL 备份异地同步脚本"
bash scripts/sync-production-mysql-backups-test.sh

echo "8/22 检查生产健康探测"
bash scripts/probe-production-health-test.sh

echo "9/22 检查生产健康快照"
bash scripts/capture-production-health-snapshot-test.sh

echo "10/22 检查发布归档"
bash scripts/package-production-release-test.sh

echo "11/22 检查生产发布包 smoke"
bash scripts/smoke-production-release-test.sh

echo "12/22 检查远程发布目标预检"
bash scripts/verify-remote-release-target-test.sh

echo "13/22 检查生产发布候选清单"
bash scripts/generate-production-release-candidate-test.sh

echo "14/22 检查服务器侧安装脚本"
bash scripts/install-production-release-test.sh

echo "15/22 检查远程发布包复制脚本"
bash scripts/upload-production-release-test.sh

echo "16/22 检查远程发布编排脚本"
bash scripts/deploy-production-release-test.sh

echo "17/22 检查服务器侧重启脚本"
bash scripts/restart-production-services-test.sh

echo "18/22 检查服务器侧回滚脚本"
bash scripts/rollback-production-release-test.sh

echo "19/22 检查 TLS 资产"
bash scripts/verify-tls-assets.sh

echo "20/22 检查生产部署资产"
bash scripts/verify-production-deployment-assets.sh

echo "21/22 检查 CI 资产"
bash scripts/verify-ci-assets.sh

echo "22/22 检查 JDBC 快照边界"
bash scripts/verify-jdbc-snapshot-boundary.sh

if git rev-parse --is-inside-work-tree >/dev/null 2>&1; then
  git diff --check
fi

echo "生产就绪聚合门禁通过。"
