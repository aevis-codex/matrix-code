# MatrixCode 第 162 阶段：远程健康快照预检设计

## 目标

在执行真实远程发布前，提前验证健康快照所需的目标机条件，避免发布重启完成后才发现快照脚本缺失、生产 env 不可读或快照目录不可写。

## 决策

- `scripts/verify-remote-release-target.sh` 默认跟随 `MATRIXCODE_DEPLOY_CAPTURE_HEALTH_SNAPSHOT=true` 检查健康快照前置条件。
- 连接预检开启时，远端命令额外检查：
  - `curl` 可用。
  - `${MATRIXCODE_REMOTE_APP_DIR}/scripts/capture-production-health-snapshot.sh` 可执行。
  - `MATRIXCODE_REMOTE_ENV_FILE` 可读。
  - `MATRIXCODE_REMOTE_HEALTH_SNAPSHOT_DIR` 可创建且可写。
- 连接预检关闭时仍输出远端 env 文件、info 地址和快照目录，便于发布候选报告记录完整目标配置。
- 需要临时跳过快照预检时，显式设置 `MATRIXCODE_DEPLOY_CAPTURE_HEALTH_SNAPSHOT=false`。

## 验证

- 红灯：扩展 `verify-remote-release-target-test.sh`，确认旧预检缺少远端 env/快照目录输出和快照脚本检查。
- 绿灯：
  - `bash scripts/verify-remote-release-target-test.sh`
  - `bash scripts/verify-production-deployment-assets.sh`
  - `bash scripts/verify-production-readiness.sh`

## 与最初需求对齐

- 对齐“真实可上线运行”：把上线后证据文件的权限、脚本和目录问题前移到发布前。
- 对齐“自主验证闭环”：本阶段只增强预检和文档，不改变业务功能、数据库结构或 Agent 执行边界。
- 对齐第 161 阶段：远程发布已能生成健康快照，本阶段确保远程目标提前具备生成条件。
