#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
ENV_FILE="${1:-${ENV_FILE:-${ROOT_DIR}/.env.local}}"
ARG_SOURCE_DIR="${2:-}"
ARG_TARGET="${3:-}"

PRESET_TARGET_DEFINED=false
PRESET_TARGET_VALUE=""
if [[ "${MATRIXCODE_BACKUP_OFFSITE_TARGET+x}" == "x" ]]; then
  PRESET_TARGET_DEFINED=true
  PRESET_TARGET_VALUE="${MATRIXCODE_BACKUP_OFFSITE_TARGET}"
fi

## 判断配置开关是否显式开启，兼容 true、TRUE 和 1 三种写法。
is_true() {
  [[ "${1:-}" == "true" || "${1:-}" == "TRUE" || "${1:-}" == "1" ]]
}

## 输出统一错误前缀并终止脚本，避免继续执行真实同步动作。
fail() {
  echo "MySQL 备份异地同步配置错误：$1" >&2
  exit 1
}

## 校验必需环境变量非空，主要用于生产门禁和数据库名前缀。
require_value() {
  local name="$1"
  local value="${!name:-}"
  [[ -n "${value}" ]] || fail "${name} 不能为空"
}

## 判断目标是否为 rsync 远端地址；本地绝对路径即使包含目录层级也不会被误判。
is_remote_target() {
  local target="$1"
  [[ "${target}" == *:* && "${target}" != /* ]]
}

## 拒绝高风险本地目标，防止把备份同步到系统根目录、项目目录或源目录本身。
reject_unsafe_local_target() {
  local target="$1"
  local source_dir="$2"

  [[ "${target}" == /* ]] || fail "本地目标目录必须使用绝对路径：${target}"

  case "${target%/}" in
    ""|"/"|"/tmp"|"/var"|"/var/backups"|"/opt"|"/opt/matrixcode")
      fail "目标目录不安全：${target}"
      ;;
  esac

  if [[ "${target%/}" == "${ROOT_DIR%/}" || "${target%/}" == "${ROOT_DIR%/}/"* ]]; then
    fail "目标目录不安全，不能指向项目目录：${target}"
  fi

  if [[ "${target%/}" == "${source_dir%/}" ]]; then
    fail "目标目录不安全，不能与源备份目录相同：${target}"
  fi
}

## 按数据库名前缀收集可同步文件，只返回当前库的压缩备份和 sha256 校验文件。
collect_backup_files() {
  local source_dir="$1"
  local database="$2"

  find "${source_dir}" -maxdepth 1 -type f \( \
    -name "${database}-*.sql.gz" \
    -o -name "${database}-*.sql.gz.sha256" \
  \) | sort
}

## dry-run 时输出低敏文件名清单，便于运维确认同步范围。
print_file_list() {
  local file
  for file in "$@"; do
    echo "  - $(basename "${file}")"
  done
}

if [[ ! -f "${ENV_FILE}" ]]; then
  fail "缺少环境文件 ${ENV_FILE}"
fi

set -a
source "${ENV_FILE}"
set +a

is_true "${MATRIXCODE_PRODUCTION_CHECK:-false}" || fail "MATRIXCODE_PRODUCTION_CHECK 必须为 true"
require_value MATRIXCODE_MYSQL_DATABASE

source_dir="${ARG_SOURCE_DIR:-${MATRIXCODE_BACKUP_DIR:-/var/backups/matrixcode/mysql}}"
if [[ -n "${ARG_TARGET}" ]]; then
  target="${ARG_TARGET}"
elif [[ "${PRESET_TARGET_DEFINED}" == "true" ]]; then
  target="${PRESET_TARGET_VALUE}"
else
  target="${MATRIXCODE_BACKUP_OFFSITE_TARGET:-}"
fi

[[ -n "${target}" ]] || fail "MATRIXCODE_BACKUP_OFFSITE_TARGET 不能为空"
[[ -d "${source_dir}" ]] || fail "源备份目录不存在：${source_dir}"

if ! is_remote_target "${target}"; then
  reject_unsafe_local_target "${target}" "${source_dir}"
fi

backup_files=()
while IFS= read -r path; do
  backup_files+=("${path}")
done < <(collect_backup_files "${source_dir}" "${MATRIXCODE_MYSQL_DATABASE}")
if [[ "${#backup_files[@]}" -eq 0 ]]; then
  fail "未找到可同步备份文件：${source_dir}/${MATRIXCODE_MYSQL_DATABASE}-*.sql.gz"
fi

if is_true "${MATRIXCODE_BACKUP_OFFSITE_DRY_RUN:-false}"; then
  echo "MySQL 备份异地同步干运行通过：${source_dir} -> ${target}"
  print_file_list "${backup_files[@]}"
  exit 0
fi

is_true "${MATRIXCODE_BACKUP_OFFSITE_CONFIRM:-false}" || fail "MATRIXCODE_BACKUP_OFFSITE_CONFIRM 必须为 true"

if is_remote_target "${target}"; then
  if ! command -v rsync >/dev/null 2>&1; then
    fail "未找到 rsync，请先在生产机器安装 rsync"
  fi

  rsync -az -- "${backup_files[@]}" "${target%/}/"
else
  mkdir -p "${target}"
  chmod 700 "${target}"

  for file in "${backup_files[@]}"; do
    cp -p "${file}" "${target}/"
    chmod 600 "${target}/$(basename "${file}")"
  done
fi

echo "MySQL 备份异地同步完成：${source_dir} -> ${target}，文件数 ${#backup_files[@]}。"
