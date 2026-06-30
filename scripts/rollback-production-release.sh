#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
PREVIOUS_DIR="${1:-}"
TARGET_DIR="${2:-/opt/matrixcode}"

## 判断高风险动作开关是否显式开启，兼容 true、TRUE 和 1。
is_true() {
  [[ "${1:-}" == "true" || "${1:-}" == "TRUE" || "${1:-}" == "1" ]]
}

## 输出统一错误前缀并终止脚本，避免在确认缺失时修改生产目录。
fail() {
  echo "生产发布回滚错误：$1" >&2
  exit 1
}

## 对审计 JSON 字段做最小转义，避免路径或原因中的引号破坏 JSONL。
json_escape() {
  printf '%s' "$1" | sed 's/\\/\\\\/g; s/"/\\"/g'
}

## 在配置审计日志时写入一行 JSONL，供后续平台审计或日志采集器读取。
write_audit_record() {
  local status="$1"
  local failed_dir="$2"
  local audit_log="${MATRIXCODE_ROLLBACK_AUDIT_LOG:-}"
  [[ -n "${audit_log}" ]] || return 0

  local audit_parent
  audit_parent="$(dirname "${audit_log}")"
  [[ -d "${audit_parent}" ]] || fail "回滚审计日志父目录不存在：${audit_parent}"

  local occurred_at
  occurred_at="$(date -u +%Y-%m-%dT%H:%M:%SZ)"
  printf '{"action":"rollback","status":"%s","occurredAt":"%s","previousDir":"%s","targetDir":"%s","failedDir":"%s"}\n' \
    "$(json_escape "${status}")" \
    "$(json_escape "${occurred_at}")" \
    "$(json_escape "${PREVIOUS_DIR}")" \
    "$(json_escape "${TARGET_DIR}")" \
    "$(json_escape "${failed_dir}")" >>"${audit_log}"
}

## 解析目标目录的物理路径，目标目录不存在时也能基于父目录得到稳定路径。
resolve_parent_target() {
  local target="$1"
  [[ "${target}" == "/" ]] && {
    printf '/\n'
    return 0
  }
  local parent
  parent="$(dirname "${target}")"
  [[ -d "${parent}" ]] || fail "目标父目录不存在：${parent}"
  (cd "${parent}" && printf '%s/%s\n' "$(pwd -P)" "$(basename "${target}")")
}

## 解析必须已存在的目录路径，用于校验上一版发布目录。
resolve_existing_dir() {
  local dir="$1"
  [[ -d "${dir}" ]] || fail "上一版目录不存在：${dir}"
  (cd "${dir}" && pwd -P)
}

## 拒绝会误伤系统目录、临时目录或仓库目录的目标路径。
reject_dangerous_target() {
  local target="$1"
  case "${target}" in
    /|/tmp|/var|/var/backups|/opt|"${ROOT_DIR}")
      fail "回滚目标目录过于危险：${target}"
      ;;
  esac
}

## 校验上一版目录具备最小可启动发布结构，避免把错误目录回滚为生产版本。
assert_release_dir() {
  local dir="$1"
  [[ -f "${dir}/matrixcode-server.jar" ]] || fail "上一版目录缺少 matrixcode-server.jar"
  [[ -f "${dir}/bin/run-matrixcode-server.sh" ]] || fail "上一版目录缺少启动脚本"
  [[ -f "${dir}/ops/env/matrixcode.env.example" ]] || fail "上一版目录缺少生产 env 示例"
}

[[ -n "${PREVIOUS_DIR}" ]] || fail "缺少上一版发布目录路径"

resolved_previous="$(resolve_existing_dir "${PREVIOUS_DIR}")"
resolved_target="$(resolve_parent_target "${TARGET_DIR}")"
reject_dangerous_target "${resolved_target}"
[[ "${resolved_previous}" != "${resolved_target}" ]] || fail "上一版目录不能与目标目录相同"
assert_release_dir "${resolved_previous}"

if is_true "${MATRIXCODE_ROLLBACK_DRY_RUN:-false}"; then
  echo "生产发布回滚 dry-run 通过：${resolved_previous} -> ${resolved_target}"
  exit 0
fi

is_true "${MATRIXCODE_ROLLBACK_CONFIRM:-false}" || fail "真实回滚必须设置 MATRIXCODE_ROLLBACK_CONFIRM=true"

timestamp="$(date +%Y%m%d%H%M%S)"
failed_dir=""
if [[ -e "${resolved_target}" ]]; then
  failed_dir="${resolved_target}.failed.${timestamp}"
  [[ ! -e "${failed_dir}" ]] || fail "失败版本备份目录已存在：${failed_dir}"
  mv "${resolved_target}" "${failed_dir}"
  echo "已备份当前版本：${failed_dir}"
fi

mkdir -p "${resolved_target}"
cp -R "${resolved_previous}/." "${resolved_target}/"

write_audit_record "SUCCEEDED" "${failed_dir}"

echo "生产发布回滚完成：${resolved_previous} -> ${resolved_target}"
