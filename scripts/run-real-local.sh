#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
ENV_FILE="${ROOT_DIR}/.env.local"

if [[ ! -f "${ENV_FILE}" ]]; then
  echo "缺少 ${ENV_FILE}，请先复制 .env.example 为 .env.local 并填入真实密钥。"
  exit 1
fi

ENV_FILE="${ENV_FILE}" "${ROOT_DIR}/scripts/check-real-runtime.sh"

set -a
source "${ENV_FILE}"
set +a

JDBC_URL="${MATRIXCODE_PERSISTENCE_JDBC_URL:-}"
SERVER_PORT="${SERVER_PORT:-18080}"
export SERVER_PORT
MYSQL_HOST="${MATRIXCODE_MYSQL_HOST:-127.0.0.1}"
MYSQL_PORT="${MATRIXCODE_MYSQL_PORT:-3306}"
MYSQL_DATABASE="${MATRIXCODE_MYSQL_DATABASE:-matrix_code}"
MYSQL_USER="${MATRIXCODE_PERSISTENCE_JDBC_USERNAME:-root}"
MYSQL_PASSWORD="${MATRIXCODE_PERSISTENCE_JDBC_PASSWORD:-}"

if command -v mysql >/dev/null 2>&1; then
  MYSQL_PWD="${MYSQL_PASSWORD}" mysql \
    --host="${MYSQL_HOST}" \
    --port="${MYSQL_PORT}" \
    --user="${MYSQL_USER}" \
    --execute="create database if not exists \`${MYSQL_DATABASE}\` default character set utf8mb4 collate utf8mb4_unicode_ci;"
else
  echo "未找到 mysql CLI，跳过 CLI 建库；如已开启 MATRIXCODE_PERSISTENCE_JDBC_CREATE_DATABASE_IF_MISSING，服务启动时会通过 JDBC 自动建库并执行 Flyway。"
fi

if [[ -z "${JDBC_URL}" ]]; then
  echo "MATRIXCODE_PERSISTENCE_JDBC_URL 不能为空。"
  exit 1
fi

cd "${ROOT_DIR}"
/Users/Masons/Ai/Maven/bin/mvn \
  -Dmaven.repo.local=/Users/Masons/Ai/Maven_Ai_Store \
  -pl server \
  spring-boot:run
