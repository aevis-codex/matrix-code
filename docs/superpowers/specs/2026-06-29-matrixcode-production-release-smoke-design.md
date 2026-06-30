# MatrixCode 第 154 阶段：生产发布包 Smoke 复验

## 背景

第 120 到 153 阶段已经补齐生产启动、发布归档、安装、复制、重启、回滚、生产门禁和真实协议检查。上线前仍需要一个可重复执行的发布包 smoke：从最终 tar.gz 归档解压，使用包内启动脚本和真实 env 做生产 dry-run，确认归档不是“能打出来但启动路径不可用”的半成品。

## 目标

- 新增发布包 smoke 脚本，验证归档校验、包内布局、包内启动脚本和包内真实运行检查脚本。
- smoke 过程强制 dry-run，不启动长期 Java 进程。
- 将 smoke 脚本纳入生产发布目录和生产就绪聚合门禁。

## 实现

- 新增 `scripts/smoke-production-release.sh`：
  - 校验 `${archive}.sha256`。
  - 解压归档到临时目录。
  - 检查 `matrixcode-server.jar`、`bin/run-matrixcode-server.sh` 和 `scripts/check-real-runtime.sh`。
  - 复制 env 文件并追加 `MATRIXCODE_PRODUCTION_DRY_RUN=true`，再执行包内启动脚本。
- 新增 `scripts/smoke-production-release-test.sh`，使用 fake preflight 验证包内启动 dry-run。
- `build-production-server.sh` 和 `package-production-release.sh` 将 smoke 脚本纳入发布资产。
- `verify-production-readiness.sh` 从 17 项扩展为 18 项。

## 验证

- 红灯：`bash scripts/smoke-production-release-test.sh` 先因缺少 `smoke-production-release.sh` 失败。
- 绿灯：`bash scripts/smoke-production-release-test.sh` 通过。
- 生产门禁：`bash scripts/verify-production-readiness.sh` 18/18 通过。
- 真实发布包 smoke：`package-production-release.sh` 生成 `matrixcode-smoke-20260629195807.tar.gz`；`smoke-production-release.sh dist/releases/matrixcode-smoke-20260629195807.tar.gz .env.local` 通过，包内真实协议级检查 3 条通过，真实 MySQL `matrix_code` Flyway 维持 v150.1，MySQL/Milvus/Redis/RocketMQ 连通。

## 回溯结论

生产发布链路从“脚本和归档分别可验证”推进到“最终归档可解压并用包内启动入口完成真实生产 dry-run”。后续仍需在目标服务器上执行安装、重启和健康探测复验，或补齐目标服务器 SSH 用户、端口、应用目录和服务管理方式后自动化执行。
