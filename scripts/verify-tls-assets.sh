#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
TLS_CONF="${ROOT_DIR}/ops/nginx/matrixcode-https.conf"
TLS_DOC="${ROOT_DIR}/docs/deployment/tls-and-domain.md"

fail() {
  echo "TLS 资产验证失败：$1" >&2
  exit 1
}

assert_file() {
  local path="$1"
  [[ -f "${ROOT_DIR}/${path}" ]] || fail "缺少 ${path}"
}

assert_contains() {
  local file="$1"
  local text="$2"
  grep -Fq -- "${text}" "${file}" || fail "${file} 缺少 ${text}"
}

assert_file ops/nginx/matrixcode-https.conf
assert_file docs/deployment/tls-and-domain.md

assert_contains "${TLS_CONF}" 'listen 80;'
assert_contains "${TLS_CONF}" 'return 301 https://$host$request_uri;'
assert_contains "${TLS_CONF}" 'listen 443 ssl http2;'
assert_contains "${TLS_CONF}" 'ssl_certificate /etc/letsencrypt/live/matrixcode.example.com/fullchain.pem;'
assert_contains "${TLS_CONF}" 'ssl_certificate_key /etc/letsencrypt/live/matrixcode.example.com/privkey.pem;'
assert_contains "${TLS_CONF}" 'Strict-Transport-Security'
assert_contains "${TLS_CONF}" 'proxy_buffering off'
assert_contains "${TLS_CONF}" 'location /api/'
assert_contains "${TLS_CONF}" 'location = /actuator/health'
assert_contains "${TLS_CONF}" 'location /actuator/'
assert_contains "${TLS_DOC}" 'matrixcode.example.com'
assert_contains "${TLS_DOC}" 'certbot'
assert_contains "${TLS_DOC}" 'nginx -t'
assert_contains "${TLS_DOC}" 'curl -fsS https://'

echo "TLS 资产验证通过。"
