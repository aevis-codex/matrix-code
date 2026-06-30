# MatrixCode 第 155 阶段：远程生产发布编排门禁

## 背景

第 154 阶段已经证明最终生产发布归档可以解压并通过包内启动入口完成生产 dry-run。剩余发布链路仍需要把远程复制、服务器侧安装、服务重启和健康探测串成一个可重复门禁，避免上线时依赖手工拼接多条命令。

## 目标

- 新增远程生产发布编排脚本，按顺序执行上传、远程安装、远程重启和健康探测。
- 默认支持 dry-run，不连接远端执行真实安装和重启。
- 真实发布必须显式设置确认开关，不保存 SSH 密钥、sudo 凭据或生产密钥。
- 将编排脚本纳入生产发布目录、发布归档、生产部署资产校验和生产就绪聚合门禁。

## 实现

- 新增 `scripts/deploy-production-release.sh`：
  - 校验发布归档和 `sha256`。
  - 校验 `MATRIXCODE_REMOTE_RELEASE_TARGET`、`MATRIXCODE_REMOTE_RELEASE_DIR` 和 `MATRIXCODE_REMOTE_APP_DIR`。
  - 可选执行 `MATRIXCODE_DEPLOY_SMOKE_ENV_FILE` 指向的发布包 smoke。
  - dry-run 模式复用 `upload-production-release.sh` 的 dry-run，并打印远程安装和重启计划。
  - 真实模式要求 `MATRIXCODE_DEPLOY_CONFIRM=true`，先上传归档，再通过 SSH 在远端解包临时安装脚本，最后调用目标应用目录内的重启脚本。
- 新增 `scripts/deploy-production-release-test.sh`，用 fake `ssh/scp` 验证 dry-run、确认门禁和远程命令编排。
- `build-production-server.sh`、`package-production-release.sh`、`package-production-release-test.sh`、`verify-production-deployment-assets.sh` 和 `verify-production-readiness.sh` 均纳入编排脚本。
- `ops/env/matrixcode.env.example` 预留远程应用目录、远程服务名、健康探测和编排确认开关。

## 验证

- 红灯：`bash scripts/deploy-production-release-test.sh` 先因缺少 `deploy-production-release.sh` 失败。
- 绿灯：`bash scripts/deploy-production-release-test.sh` 通过。
- 生产门禁：`bash scripts/verify-production-readiness.sh` 19/19 通过。
- 真实发布包 smoke：`matrixcode-deploy-smoke-20260629201257.tar.gz` 使用 `.env.local` 副本完成包内生产 dry-run，真实协议级检查 3 条通过。
- 远程编排 dry-run：同一发布归档输出远程复制、安装和重启计划，不连接真实远端执行安装。

## 回溯结论

发布链路从“归档可启动预检”推进到“上传、安装、重启、健康探测可编排”。该脚本仍不保存凭据、不绕过服务器侧显式确认边界；真实执行依赖部署环境已配置 SSH 权限和 systemd 权限。
