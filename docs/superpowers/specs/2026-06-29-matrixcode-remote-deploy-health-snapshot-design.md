# MatrixCode 第 161 阶段：远程发布健康快照编排设计

## 目标

远程发布编排在完成归档上传、服务器侧安装和服务重启后，自动生成一份低敏生产健康快照，减少上线验收依赖人工复制命令的风险。

## 决策

- `scripts/deploy-production-release.sh` 默认开启远程健康快照，使用 `MATRIXCODE_DEPLOY_CAPTURE_HEALTH_SNAPSHOT=false` 显式跳过。
- 新增远程配置：
  - `MATRIXCODE_REMOTE_INFO_PROBE_URL`
  - `MATRIXCODE_REMOTE_ENV_FILE`
  - `MATRIXCODE_REMOTE_HEALTH_SNAPSHOT_DIR`
- 快照脚本新增 `MATRIXCODE_HEALTH_PROBE_URL_OVERRIDE` 和 `MATRIXCODE_INFO_PROBE_URL_OVERRIDE`，保证发布编排传入的探测地址不会被远程 env 文件默认值覆盖。
- 快照目录和 env 文件路径必须是普通绝对路径，拒绝根目录、系统级目录和包含 shell 分隔风险的路径。

## 验证

- 红灯：先扩展 `deploy-production-release-test.sh`，确认当前 dry-run 缺少“远程健康快照”输出。
- 红灯：先扩展 `capture-production-health-snapshot-test.sh`，确认健康地址 override 未生效时快照仍读取 env 中的 DOWN 地址。
- 绿灯：
  - `bash scripts/capture-production-health-snapshot-test.sh`
  - `bash scripts/deploy-production-release-test.sh`
  - `bash scripts/verify-production-deployment-assets.sh`
  - `bash scripts/verify-production-readiness.sh`

## 与最初需求对齐

- 对齐“真实可上线运行”：远程发布链路从“重启后终端健康输出”升级为“可归档低敏快照”。
- 对齐“每阶段回溯”：本阶段不改变业务功能、Agent 逻辑、数据库结构或大模型调用，只补齐发布验收闭环。
- 对齐“真实依赖”：仍依赖生产 env 中的 MySQL、Milvus、Redis、RocketMQ 和模型配置，快照只记录低敏状态，不暴露密钥。
