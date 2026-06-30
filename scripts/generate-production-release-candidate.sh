#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
ARCHIVE_FILE="${1:-}"
ENV_FILE="${2:-${MATRIXCODE_RELEASE_CANDIDATE_ENV_FILE:-}}"

## 判断配置开关是否显式开启，兼容 true、TRUE 和 1 三种写法。
is_true() {
  [[ "${1:-}" == "true" || "${1:-}" == "TRUE" || "${1:-}" == "1" ]]
}

## 输出统一错误前缀并终止脚本，便于 CI 和人工执行快速定位失败阶段。
fail() {
  echo "生产发布候选清单错误：$1" >&2
  exit 1
}

## 将输入文件转换为绝对路径，避免报告中出现相对路径导致交接后难以定位证据。
absolute_file() {
  local path="$1"
  local dir
  dir="$(dirname "${path}")"
  [[ -d "${dir}" ]] || fail "路径父目录不存在：${dir}"
  printf '%s/%s\n' "$(cd "${dir}" && pwd -P)" "$(basename "${path}")"
}

## 校验发布归档旁边的 sha256 文件，保证报告只基于可校验的候选包生成。
verify_checksum() {
  local archive="$1"
  local checksum="${archive}.sha256"
  local expected=""
  local actual=""
  [[ -f "${checksum}" ]] || fail "缺少校验文件 ${checksum}"
  if command -v sha256sum >/dev/null 2>&1; then
    expected="$(awk '{print $1; exit}' "${checksum}")"
    actual="$(sha256sum "${archive}" | awk '{print $1}')"
  elif command -v shasum >/dev/null 2>&1; then
    expected="$(awk '{print $1; exit}' "${checksum}")"
    actual="$(shasum -a 256 "${archive}" | awk '{print $1}')"
  else
    fail "未找到 sha256sum 或 shasum"
  fi
  [[ -n "${expected}" && "${expected}" == "${actual}" ]] || fail "发布归档 sha256 校验失败：${archive}"
}

## 从发布 manifest 中读取指定键；manifest 不存在时返回调用方给定的兜底值。
manifest_value() {
  local key="$1"
  local fallback="$2"
  local value=""
  if [[ -f "${MANIFEST_FILE}" ]]; then
    value="$(awk -F= -v expected="${key}" '$1 == expected {print substr($0, index($0, "=") + 1); exit}' "${MANIFEST_FILE}")"
  fi
  [[ -n "${value}" ]] && printf '%s\n' "${value}" || printf '%s\n' "${fallback}"
}

## 追加一行 Markdown 验证结果，报告只记录状态和日志路径，不内联完整日志。
append_result() {
  local label="$1"
  local status="$2"
  local note="$3"
  verification_rows="${verification_rows}| ${label} | ${status} | ${note} |
"
}

## 执行一个候选包验证命令，并把完整输出保存到受限权限日志文件。
run_check() {
  local key="$1"
  local label="$2"
  shift 2
  local log_file="${LOG_DIR}/${key}.log"
  if "$@" >"${log_file}" 2>&1; then
    chmod 600 "${log_file}"
    append_result "${label}" "PASS" "${log_file}"
  else
    chmod 600 "${log_file}" || true
    append_result "${label}" "FAIL" "${log_file}"
    fail "${label} 失败，详见 ${log_file}"
  fi
}

[[ -n "${ARCHIVE_FILE}" ]] || fail "缺少发布归档路径"
[[ -f "${ARCHIVE_FILE}" ]] || fail "发布归档不存在：${ARCHIVE_FILE}"

archive_file="$(absolute_file "${ARCHIVE_FILE}")"
verify_checksum "${archive_file}"

checksum_file="${archive_file}.sha256"
sha256_value="$(awk '{print $1; exit}' "${checksum_file}")"
archive_dir="$(dirname "${archive_file}")"
archive_name="$(basename "${archive_file}")"
base_name="${archive_name%.tar.gz}"
base_path="${archive_dir}/${base_name}"
MANIFEST_FILE="${base_path}.manifest"
REPORT_FILE="${MATRIXCODE_RELEASE_CANDIDATE_REPORT_FILE:-${base_path}.release-candidate.md}"
LOG_DIR="${MATRIXCODE_RELEASE_CANDIDATE_LOG_DIR:-${base_path}.release-candidate.d}"

release_name="$(manifest_value name "${base_name}")"
git_commit="$(manifest_value git_commit "$(git -C "${ROOT_DIR}" rev-parse --short HEAD 2>/dev/null || echo unknown)")"
created_at="$(date -u +%Y-%m-%dT%H:%M:%SZ)"
env_fingerprint="未提供"

mkdir -p "${LOG_DIR}" "$(dirname "${REPORT_FILE}")"
chmod 700 "${LOG_DIR}"

verification_rows=""
if is_true "${MATRIXCODE_RELEASE_CANDIDATE_SKIP_CHECKS:-false}"; then
  append_result "生产就绪聚合门禁" "SKIPPED" "MATRIXCODE_RELEASE_CANDIDATE_SKIP_CHECKS=true"
  append_result "发布包 smoke" "SKIPPED" "MATRIXCODE_RELEASE_CANDIDATE_SKIP_CHECKS=true"
  append_result "真实协议级检查" "SKIPPED" "MATRIXCODE_RELEASE_CANDIDATE_SKIP_CHECKS=true"
  append_result "远程发布目标预检" "SKIPPED" "MATRIXCODE_RELEASE_CANDIDATE_SKIP_CHECKS=true"
  append_result "远程发布 dry-run" "SKIPPED" "MATRIXCODE_RELEASE_CANDIDATE_SKIP_CHECKS=true"
else
  [[ -n "${ENV_FILE}" ]] || fail "缺少生产 env 文件路径"
  [[ -f "${ENV_FILE}" ]] || fail "生产 env 文件不存在：${ENV_FILE}"
  env_file="$(absolute_file "${ENV_FILE}")"
  if command -v sha256sum >/dev/null 2>&1; then
    env_fingerprint="$(sha256sum "${env_file}" | awk '{print $1}')"
  elif command -v shasum >/dev/null 2>&1; then
    env_fingerprint="$(shasum -a 256 "${env_file}" | awk '{print $1}')"
  fi
  run_check "production-readiness" "生产就绪聚合门禁" bash "${ROOT_DIR}/scripts/verify-production-readiness.sh"
  run_check "release-smoke" "发布包 smoke" bash "${ROOT_DIR}/scripts/smoke-production-release.sh" "${archive_file}" "${env_file}"
  run_check "real-runtime" "真实协议级检查" env MATRIXCODE_PROTOCOL_CHECK=true bash "${ROOT_DIR}/scripts/check-real-runtime.sh" "${env_file}"
  if [[ -n "${MATRIXCODE_REMOTE_RELEASE_TARGET:-}" ]]; then
    run_check "remote-release-target" "远程发布目标预检" bash "${ROOT_DIR}/scripts/verify-remote-release-target.sh"
    run_check "remote-deploy-dry-run" "远程发布 dry-run" env MATRIXCODE_DEPLOY_DRY_RUN=true bash "${ROOT_DIR}/scripts/deploy-production-release.sh" "${archive_file}"
  else
    append_result "远程发布目标预检" "SKIPPED" "未设置 MATRIXCODE_REMOTE_RELEASE_TARGET"
    append_result "远程发布 dry-run" "SKIPPED" "未设置 MATRIXCODE_REMOTE_RELEASE_TARGET"
  fi
fi

cat >"${REPORT_FILE}" <<EOF_REPORT
# MatrixCode 生产发布候选验收报告

## 候选包

- 发布名称：${release_name}
- 生成时间：${created_at}
- Git 提交：${git_commit}
- 发布归档：${archive_file}
- SHA256：${sha256_value}
- 校验文件：${checksum_file}
- 生产 env 指纹：${env_fingerprint}

## 验证结果

| 检查项 | 状态 | 证据 |
| --- | --- | --- |
${verification_rows}
## 上线执行入口

- 发布包 smoke：\`bash scripts/smoke-production-release.sh "${archive_file}" <env-file>\`
- 远程发布 dry-run：\`MATRIXCODE_DEPLOY_DRY_RUN=true bash scripts/deploy-production-release.sh "${archive_file}"\`
- 真实远程发布：\`MATRIXCODE_DEPLOY_CONFIRM=true bash scripts/deploy-production-release.sh "${archive_file}"\`
- 服务器侧回滚：\`MATRIXCODE_ROLLBACK_CONFIRM=true bash scripts/rollback-production-release.sh <release-archive> <app-dir>\`

## 安全说明

报告只记录归档路径、校验和、Git 提交、env 文件指纹和验证日志路径；不写入数据库密码、API Key、Sa-Token、模型密钥、完整 prompt、模型响应或工具输出。
EOF_REPORT

chmod 600 "${REPORT_FILE}"
echo "生产发布候选清单已生成：${REPORT_FILE}"
