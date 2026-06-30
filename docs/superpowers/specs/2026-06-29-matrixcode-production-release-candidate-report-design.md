# MatrixCode 生产发布候选清单与验收报告设计

## 背景

第 154 阶段已经把最终发布归档 smoke 纳入门禁，第 155 阶段已经具备远程上传、安装、重启和健康探测编排。剩余缺口是发布候选包缺少一份可交接、可审计、可复查的验收报告；如果只依赖终端日志，发布后很难快速确认本次候选包对应的 Git 提交、归档校验和、门禁结果与真实协议检查证据。

## 目标

- 基于最终 `tar.gz` 发布归档、`.sha256` 和 `.manifest` 生成生产发布候选验收报告。
- 报告记录 Git 提交、归档路径、SHA256、生产 env 文件指纹、各项验证状态和日志路径。
- 默认执行生产就绪聚合门禁、发布包 smoke、真实协议级检查；远程发布 dry-run 在配置远程目标后执行。
- 报告和日志不写入数据库密码、API Key、Sa-Token、模型密钥、完整 prompt、模型响应或工具输出。
- 发布包和生产就绪聚合门禁必须包含候选清单脚本，避免源码树可用但最终归档缺失。

## 非目标

- 不自动执行真实远程发布。
- 不保存 SSH 密钥、sudo 凭据或生产 env 明文。
- 不替代生产变更审批、发布窗口确认和真实域名证书配置。

## 实现方案

- 新增 `scripts/generate-production-release-candidate.sh`：
  - 输入发布归档路径和可选 env 文件路径。
  - 校验归档旁边的 `.sha256`，并读取同名 `.manifest`。
  - 默认输出 `${release}.release-candidate.md`，日志输出到 `${release}.release-candidate.d/`。
  - 非跳过模式下执行 `verify-production-readiness.sh`、`smoke-production-release.sh`、`MATRIXCODE_PROTOCOL_CHECK=true check-real-runtime.sh`。
  - 仅在 `MATRIXCODE_REMOTE_RELEASE_TARGET` 存在时执行远程发布 dry-run。
- 新增 `scripts/generate-production-release-candidate-test.sh`：
  - 覆盖缺少归档路径、跳过模式报告生成、manifest 读取和报告关键字段。
- 更新发布包构建与归档校验：
  - `build-production-server.sh` 将候选清单脚本安装到发布目录。
  - `package-production-release.sh` 要求发布目录包含候选清单脚本。
  - `package-production-release-test.sh` 的 fake 发布目录补齐该脚本。
- 更新 `verify-production-readiness.sh`：
  - 聚合门禁从 19 项扩展到 20 项，第 11 项为生产发布候选清单脚本测试。

## 验证

- 红灯：实现前运行 `bash scripts/generate-production-release-candidate-test.sh`，因缺少候选清单脚本失败。
- 目标绿灯：`bash scripts/generate-production-release-candidate-test.sh` 通过。
- 关联验证：`bash scripts/package-production-release-test.sh`、`bash scripts/verify-production-deployment-assets.sh`、`bash scripts/verify-production-readiness.sh` 通过。
- 安全验证：`git diff --check`、真实凭据精确扫描和 staged secret 扫描通过。

## 回溯

- 对齐“真实可上线运行”：发布候选包从可构建、可 smoke、可远程编排，推进到可形成验收报告和证据目录。
- 对齐“每阶段验证后更新图谱”：本阶段更新生产手册、阶段成果、项目首页、阶段索引、模块地图、验证风险和部署运维图谱。
- 对齐“安全与低敏”：报告只保留指纹、校验和、日志路径和状态，不保存真实密钥或业务内容。
