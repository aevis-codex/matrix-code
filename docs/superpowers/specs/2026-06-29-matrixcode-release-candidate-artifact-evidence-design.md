# MatrixCode 第 164 阶段：生产发布候选实物验收设计

## 目标

基于当前 `master` 提交生成真实生产发布归档和低敏发布候选报告，确认上线前核心证据链不是只存在于脚本测试中。

## 决策

- 通过 `scripts/package-production-release.sh` 生成生产发布归档。
- 通过 `scripts/generate-production-release-candidate.sh` 生成发布候选验收报告。
- 新增 `docs/deployment/release-candidate-log.md`，记录低敏候选包摘要、校验和和门禁状态。
- 不提交 `dist/` 产物和候选报告原文件；完整日志只保留在本机 `dist/releases/*.release-candidate.d/`。

## 本次候选包

- 发布名称：`matrixcode-stage164-20260629220712`
- Git 提交：`0d30afe`
- SHA256：`361460c0b8f3fb4bc6c0c91c8abf71e7e1819f9ca77b15803cd8d3af3f5c790b`
- 生产就绪聚合门禁：PASS
- 发布包 smoke：PASS
- 真实协议级检查：PASS
- 远程发布目标预检：SKIPPED，未设置 `MATRIXCODE_REMOTE_RELEASE_TARGET`
- 远程发布 dry-run：SKIPPED，未设置 `MATRIXCODE_REMOTE_RELEASE_TARGET`

## 验证

- `MATRIXCODE_RELEASE_NAME=matrixcode-stage164-20260629220712 bash scripts/package-production-release.sh`
- `bash scripts/generate-production-release-candidate.sh dist/releases/matrixcode-stage164-20260629220712.tar.gz .env.local`
- `bash scripts/verify-production-deployment-assets.sh`
- `bash scripts/verify-production-readiness.sh`

## 与最初需求对齐

- 对齐“真实可上线运行”：当前提交已经生成可交付归档，并通过生产就绪、发布包 smoke 和真实协议级检查。
- 对齐安全边界：发布候选记录不写真实密钥，只记录低敏状态、归档名、校验和和 Git 提交。
- 当前缺口：真实远程发布仍需要设置 `MATRIXCODE_REMOTE_RELEASE_TARGET` 并提供目标服务器最小 SSH/systemd 权限。
